package com.modelviewer.render;

import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Converts a {@link ModelMesh} into a {@link GpuModel} by uploading vertex
 * data to OpenGL VBOs and an index EBO.
 *
 * Data layout produced (one float per component, packed):
 *
 *   Position VBO  : [ x0, y0, z0,  x1, y1, z1, ... ]   (GL_ARRAY_BUFFER)
 *   Colour VBO    : [ r0, g0, b0,  r1, g1, b1, ... ]   (GL_ARRAY_BUFFER)
 *   Normal VBO    : [ nx0, ny0, nz0, ... ]              (GL_ARRAY_BUFFER)
 *   UV VBO        : [ u0, v0,  u1, v1, ... ]            (GL_ARRAY_BUFFER)
 *   EBO           : [ a0, b0, c0,  a1, b1, c1, ... ]   (GL_ELEMENT_ARRAY_BUFFER)
 *
 * Colours are computed from per-face HSL values: the same colour is assigned
 * to all three vertices of each face (flat shading).
 *
 * Normals are computed as the face normal (cross product of two edges), then
 * averaged at each shared vertex (smooth shading).
 *
 * UV coordinates are computed per-vertex:
 *   - If mesh.texFaceP != null and the face has a texture, uses the OSRS
 *     barycentric method with the texture triangle reference frame.
 *   - Otherwise, uses planar projection (u = normalised X, v = normalised Z).
 *
 * All vertex positions are scaled into tile units (1.0 = one game tile)
 * and centered at the world origin:
 *   1. Convert from fixed-point (1/128 tile units) to float tile units.
 *   2. Translate so the bounding-box centre is at the world origin.
 * The camera then frames the model based on its actual bounding radius.
 */
public final class MeshUploader {

    private static final Logger log = LoggerFactory.getLogger(MeshUploader.class);

    private MeshUploader() {}

    /**
     * Uploads the mesh to the GPU and returns a {@link GpuModel} ready to draw.
     *
     * Must be called from the OpenGL thread.
     *
     * @param mesh decoded model data (not modified)
     * @return uploaded GPU model
     */
    public static GpuModel upload(ModelMesh mesh) {
        int vCount = mesh.vertexCount;
        int fCount = mesh.faceCount;

        // ── Scale + center ──────────────────────────────────────────────────
        // OSRS coordinates are in units of 1/128 of a game tile. Convert to
        // tile units so intermediate floats stay in a reasonable range.
        final float INV128 = 1.0f / 128.0f;

        // Step 1 — bounding-box centre in tile units
        float centerX = mesh.centerX() * INV128;  // (minX + maxX) / 2 / 128
        float centerY = mesh.centerY() * INV128;
        float centerZ = mesh.centerZ() * INV128;

        // ── Positions ────────────────────────────────────────────────────────
        // Formula: scaled = (raw / 128 - centre)
        // OSRS axes: X=east/west, Y=up, Z=north/south.
        // Renderer axes: X=right, Y=up, Z=forward.
        // Map OSRS -> renderer: (x, y, z) = (X, Z, -Y).
        //
        // After axis remapping and centering, compute the minimum renderer-Y so
        // the model can be shifted upward until its lowest vertex sits on Y = 0.
        float[] vy = new float[vCount];
        float minY = Float.POSITIVE_INFINITY;
        for (int i = 0; i < vCount; i++) {
            vy[i] = mesh.vertexZ[i] * INV128 - centerZ;
            if (vy[i] < minY) minY = vy[i];
        }
        float offsetY = -minY;  // align lowest vertex to grid plane at Y = 0

        FloatBuffer positions = BufferUtils.createFloatBuffer(vCount * 3);
        for (int i = 0; i < vCount; i++) {
            positions.put( (mesh.vertexX[i] * INV128 - centerX));
            positions.put( vy[i] + offsetY);
            positions.put(-(mesh.vertexY[i] * INV128 - centerY));
        }
        positions.flip();

        // ── Face normals → vertex normals (smooth shading) ───────────────────
        float[] nx = new float[vCount];
        float[] ny = new float[vCount];
        float[] nz = new float[vCount];

        for (int i = 0; i < fCount; i++) {
            int ai = mesh.faceVertexA[i];
            int bi = mesh.faceVertexB[i];
            int ci = mesh.faceVertexC[i];
            if (ai < 0 || bi < 0 || ci < 0 || ai >= vCount || bi >= vCount || ci >= vCount) continue;

            // Edge vectors
            float ax = mesh.vertexX[bi] - mesh.vertexX[ai];
            float ay = mesh.vertexY[bi] - mesh.vertexY[ai];
            float az = mesh.vertexZ[bi] - mesh.vertexZ[ai];
            float bx = mesh.vertexX[ci] - mesh.vertexX[ai];
            float by = mesh.vertexY[ci] - mesh.vertexY[ai];
            float bz = mesh.vertexZ[ci] - mesh.vertexZ[ai];

            // Cross product (face normal)
            float fnx = ay * bz - az * by;
            float fny = az * bx - ax * bz;
            float fnz = ax * by - ay * bx;

            // Accumulate into vertex normals
            nx[ai] += fnx; ny[ai] += fny; nz[ai] += fnz;
            nx[bi] += fnx; ny[bi] += fny; nz[bi] += fnz;
            nx[ci] += fnx; ny[ci] += fny; nz[ci] += fnz;
        }

        // Map normals into renderer space: (nx, ny, nz) = (X, Z, -Y).
        FloatBuffer normals = BufferUtils.createFloatBuffer(vCount * 3);
        for (int i = 0; i < vCount; i++) {
            float len = (float) Math.sqrt(nx[i]*nx[i] + ny[i]*ny[i] + nz[i]*nz[i]);
            if (len > 0f) {
                normals.put( nx[i] / len);
                normals.put( nz[i] / len);
                normals.put(-ny[i] / len);
            } else {
                normals.put(0f).put(1f).put(0f);  // default up
            }
        }
        normals.flip();

        // ── UV coordinates ───────────────────────────────────────────────────
        // Initialise to zero for all vertices; textured faces override below.
        float[] uvU = new float[vCount];
        float[] uvV = new float[vCount];

        boolean hasTexTriangles = mesh.texFaceP != null && mesh.texFaceP.length > 0;
        int texFaceCount = hasTexTriangles ? mesh.texFaceP.length : 0;

        // Determine planar projection range (used for fallback)
        float rangeX = (mesh.maxX != mesh.minX) ? (float)(mesh.maxX - mesh.minX) : 1.0f;
        float rangeZ = (mesh.maxZ != mesh.minZ) ? (float)(mesh.maxZ - mesh.minZ) : 1.0f;

        for (int i = 0; i < fCount; i++) {
            int ai = mesh.faceVertexA[i];
            int bi = mesh.faceVertexB[i];
            int ci = mesh.faceVertexC[i];
            if (ai < 0 || bi < 0 || ci < 0 || ai >= vCount || bi >= vCount || ci >= vCount) continue;

            boolean faceTextured = mesh.faceTextureIds != null
                    && i < mesh.faceTextureIds.length
                    && mesh.faceTextureIds[i] >= 0;

            if (hasTexTriangles && faceTextured) {
                // OSRS barycentric UV via texture triangle reference frame
                int ti = i % texFaceCount;
                int P = mesh.texFaceP[ti];
                int Q = mesh.texFaceQ[ti];
                int R = mesh.texFaceR[ti];

                if (P >= 0 && P < vCount && Q >= 0 && Q < vCount && R >= 0 && R < vCount) {
                    float dpx = mesh.vertexX[P] - mesh.vertexX[R];
                    float dpz = mesh.vertexZ[P] - mesh.vertexZ[R];
                    float dqx = mesh.vertexX[Q] - mesh.vertexX[R];
                    float dqz = mesh.vertexZ[Q] - mesh.vertexZ[R];
                    float denom = dpx * dqz - dpz * dqx;

                    if (Math.abs(denom) > 0.0001f) {
                        for (int v : new int[]{ai, bi, ci}) {
                            float dvx = mesh.vertexX[v] - mesh.vertexX[R];
                            float dvz = mesh.vertexZ[v] - mesh.vertexZ[R];
                            uvU[v] = (dvx * dqz - dvz * dqx) / denom;
                            uvV[v] = (dvz * dpx - dvx * dpz) / denom;
                        }
                        continue;
                    }
                }
            }

            // Planar projection fallback
            for (int v : new int[]{ai, bi, ci}) {
                uvU[v] = (mesh.vertexX[v] - mesh.minX) / rangeX;
                uvV[v] = (mesh.vertexZ[v] - mesh.minZ) / rangeZ;
            }
        }

        FloatBuffer uvBuffer = BufferUtils.createFloatBuffer(vCount * 2);
        for (int i = 0; i < vCount; i++) {
            uvBuffer.put(uvU[i]);
            uvBuffer.put(uvV[i]);
        }
        uvBuffer.flip();

        // ── Vertex colours (per-face HSL → per-vertex RGB) ───────────────────
        // We write the same colour to all 3 vertices of each face
        float[] colorR = new float[vCount];
        float[] colorG = new float[vCount];
        float[] colorB = new float[vCount];
        int[]   colorCount = new int[vCount];  // for averaging if vertex is shared

        for (int i = 0; i < fCount; i++) {
            int rgb = ModelDecoder.hslToRgb(mesh.faceColors[i] & 0xFFFF);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >>  8) & 0xFF) / 255f;
            float b = ( rgb        & 0xFF) / 255f;

            for (int v : new int[]{mesh.faceVertexA[i], mesh.faceVertexB[i], mesh.faceVertexC[i]}) {
                if (v < 0 || v >= vCount) continue;
                colorR[v] += r; colorG[v] += g; colorB[v] += b;
                colorCount[v]++;
            }
        }

        FloatBuffer colors = BufferUtils.createFloatBuffer(vCount * 3);
        for (int i = 0; i < vCount; i++) {
            int cnt = Math.max(1, colorCount[i]);
            colors.put(colorR[i] / cnt);
            colors.put(colorG[i] / cnt);
            colors.put(colorB[i] / cnt);
        }
        colors.flip();

        // ── Indices (GL_UNSIGNED_SHORT — 2 bytes per index, max 65535 vertices) ─
        ShortBuffer indices = BufferUtils.createShortBuffer(fCount * 3);
        int badFaces = 0;
        for (int i = 0; i < fCount; i++) {
            int ai = mesh.faceVertexA[i];
            int bi = mesh.faceVertexB[i];
            int ci = mesh.faceVertexC[i];
            if (ai >= 0 && ai < vCount && bi >= 0 && bi < vCount && ci >= 0 && ci < vCount) {
                // Cast to short — OSRS vertex counts fit in uint16 (≤ 65535).
                // Java short is signed but the bit pattern is identical to uint16;
                // OpenGL reads these with GL_UNSIGNED_SHORT so values > 32767 are fine.
                indices.put((short) ai).put((short) bi).put((short) ci);
            } else {
                indices.put((short) 0).put((short) 0).put((short) 0);
                badFaces++;
            }
        }
        if (badFaces > 0) {
            log.warn("Model {} — {}/{} faces had out-of-range indices (vCount={}) — decode bug?",
                    mesh.modelId, badFaces, fCount, vCount);
        }

        // Log first 5 triangles so decode output is visible without a debugger.
        if (log.isDebugEnabled()) {
            int lim = Math.min(5, fCount);
            StringBuilder sb = new StringBuilder("Model ").append(mesh.modelId).append(" first triangles:");
            for (int i = 0; i < lim; i++) {
                sb.append(String.format(" [%d](%d,%d,%d)", i,
                        mesh.faceVertexA[i], mesh.faceVertexB[i], mesh.faceVertexC[i]));
            }
            log.debug(sb.toString());
        } else {
            // Always log the first triangle at INFO so it appears in the debug panel.
            if (fCount > 0) {
                log.info("Model {} first triangle: ({},{},{}) vCount={}",
                        mesh.modelId,
                        mesh.faceVertexA[0], mesh.faceVertexB[0], mesh.faceVertexC[0],
                        vCount);
            }
        }

        indices.flip();

        // ── Diagnostic: verify scaled position bounds ────────────────────────
        // Raw OSRS bounds come from ModelMesh; scaled bounds are read back
        // from the positions buffer (tile units).
        {
            float nMinX = Float.MAX_VALUE, nMaxX = -Float.MAX_VALUE;
            float nMinY = Float.MAX_VALUE, nMaxY = -Float.MAX_VALUE;
            float nMinZ = Float.MAX_VALUE, nMaxZ = -Float.MAX_VALUE;
            positions.rewind();
            while (positions.hasRemaining()) {
                float x = positions.get(), y = positions.get(), z = positions.get();
                if (x < nMinX) nMinX = x; if (x > nMaxX) nMaxX = x;
                if (y < nMinY) nMinY = y; if (y > nMaxY) nMaxY = y;
                if (z < nMinZ) nMinZ = z; if (z > nMaxZ) nMaxZ = z;
            }
            positions.rewind();
            log.info("Model {} — {} verts {} faces | raw OSRS: X[{},{}] Y[{},{}] Z[{},{}] "
                   + "| tiles: X[{},{}] Y[{},{}] Z[{},{}] "
                   + "| scaled: X[{},{}] Y[{},{}] Z[{},{}]",
                    mesh.modelId, vCount, fCount,
                    mesh.minX, mesh.maxX, mesh.minY, mesh.maxY, mesh.minZ, mesh.maxZ,
                    String.format("%.2f", mesh.minX * INV128), String.format("%.2f", mesh.maxX * INV128),
                    String.format("%.2f", mesh.minY * INV128), String.format("%.2f", mesh.maxY * INV128),
                    String.format("%.2f", mesh.minZ * INV128), String.format("%.2f", mesh.maxZ * INV128),
                    String.format("%.3f", nMinX), String.format("%.3f", nMaxX),
                    String.format("%.3f", nMinY), String.format("%.3f", nMaxY),
                    String.format("%.3f", nMinZ), String.format("%.3f", nMaxZ));
        }

        // ── Upload to GPU ─────────────────────────────────────────────────────
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int posVbo = uploadFloatVBO(positions, 0, 3);
        int colVbo = uploadFloatVBO(colors,    1, 3);
        int norVbo = uploadFloatVBO(normals,   2, 3);
        int uvVbo  = uploadFloatVBO(uvBuffer,  3, 2);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glBindVertexArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

        return new GpuModel(vao, posVbo, colVbo, norVbo, uvVbo, ebo, fCount * 3);
    }

    /**
     * Creates a hardcoded yellow triangle in clip/NDC space for pipeline testing.
     * Identity MVP is used when drawing — positions ARE clip-space positions.
     * Vertices: lower-left (−0.7,−0.7), lower-right (0.7,−0.7), top-center (0,0.7).
     */
    public static GpuModel createTestTriangle() {
        FloatBuffer positions = BufferUtils.createFloatBuffer(9)
                .put(new float[]{-0.7f, -0.7f, 0f,   0.7f, -0.7f, 0f,   0f, 0.7f, 0f});
        positions.flip();
        FloatBuffer colors = BufferUtils.createFloatBuffer(9)
                .put(new float[]{1f, 1f, 0f,   1f, 1f, 0f,   1f, 1f, 0f});  // yellow
        colors.flip();
        FloatBuffer normals = BufferUtils.createFloatBuffer(9)
                .put(new float[]{0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f});
        normals.flip();
        FloatBuffer uvs = BufferUtils.createFloatBuffer(6)
                .put(new float[]{0f, 0f,   1f, 0f,   0.5f, 1f});
        uvs.flip();
        IntBuffer indices = BufferUtils.createIntBuffer(3).put(new int[]{0, 1, 2});
        indices.flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int posVbo = uploadFloatVBO(positions, 0, 3);
        int colVbo = uploadFloatVBO(colors,    1, 3);
        int norVbo = uploadFloatVBO(normals,   2, 3);
        int uvVbo  = uploadFloatVBO(uvs,       3, 2);
        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glBindVertexArray(0);
        return new GpuModel(vao, posVbo, colVbo, norVbo, uvVbo, ebo, 3);
    }

    private static int uploadFloatVBO(FloatBuffer data, int attribIndex, int components) {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        // Stride is explicit (components * 4 bytes) — tightly packed, one attribute per VBO.
        glVertexAttribPointer(attribIndex, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(attribIndex);
        return vbo;
    }
}
