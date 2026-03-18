package com.modelviewer.render;

import com.modelviewer.model.ModelMesh;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public final class MeshUploader {

    private MeshUploader() {}

    public static GpuModel upload(ModelMesh mesh) {
        int vCount = mesh.vertexCount;
        int fCount = mesh.faceCount;

        final float SCALE = 1.0f / 512.0f;

        float[] renderX = new float[vCount];
        float[] renderY = new float[vCount];
        float[] renderZ = new float[vCount];

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        // ── Axis conversion + scale ─────────────────────────────
        for (int i = 0; i < vCount; i++) {
            float x = mesh.vertexX[i] * SCALE;
            float y = mesh.vertexY[i] * SCALE;
            float z = mesh.vertexZ[i] * SCALE;

            float finalX = x;
            float finalY = z;
            float finalZ = -y;

            renderX[i] = finalX;
            renderY[i] = finalY;
            renderZ[i] = finalZ;

            if (finalX < minX) minX = finalX;
            if (finalX > maxX) maxX = finalX;
            if (finalY < minY) minY = finalY;
            if (finalY > maxY) maxY = finalY;
            if (finalZ < minZ) minZ = finalZ;
            if (finalZ > maxZ) maxZ = finalZ;
        }

        // ── Center + ground ─────────────────────────────────────
        float centerX = (minX + maxX) * 0.5f;
        float centerZ = (minZ + maxZ) * 0.5f;
        float baseY   = minY - 0.01f;

        // ── Per-face expansion ──────────────────────────────────
        int newVertexCount = fCount * 3;

        FloatBuffer positions = BufferUtils.createFloatBuffer(newVertexCount * 3);
        FloatBuffer normals   = BufferUtils.createFloatBuffer(newVertexCount * 3);
        FloatBuffer colors    = BufferUtils.createFloatBuffer(newVertexCount * 3);

        for (int i = 0; i < fCount; i++) {
            int a = mesh.faceVertexA[i];
            int b = mesh.faceVertexB[i];
            int c = mesh.faceVertexC[i];

            if (a < 0 || b < 0 || c < 0 ||
                    a >= vCount || b >= vCount || c >= vCount) {
                continue;
            }

            // Transformed positions
            float ax = renderX[a] - centerX;
            float ay = renderY[a] - baseY;
            float az = renderZ[a] - centerZ;

            float bx = renderX[b] - centerX;
            float by = renderY[b] - baseY;
            float bz = renderZ[b] - centerZ;

            float cx = renderX[c] - centerX;
            float cy = renderY[c] - baseY;
            float cz = renderZ[c] - centerZ;

            // ── Compute normal (correct space) ───────────────────
            float ux = bx - ax;
            float uy = by - ay;
            float uz = bz - az;

            float vx = cx - ax;
            float vy = cy - ay;
            float vz = cz - az;

            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;

            // Normalize
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (len != 0f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }

            // ── Fix inconsistent winding (robust version) ───────
            // Ensure normals point upward-ish (Y-up world)
            if (ny < 0f) {
                // swap B and C
                float tx = bx, ty = by, tz = bz;
                bx = cx; by = cy; bz = cz;
                cx = tx; cy = ty; cz = tz;

                // recompute normal
                ux = bx - ax;
                uy = by - ay;
                uz = bz - az;

                vx = cx - ax;
                vy = cy - ay;
                vz = cz - az;

                nx = uy * vz - uz * vy;
                ny = uz * vx - ux * vz;
                nz = ux * vy - uy * vx;

                len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
                if (len != 0f) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
            }

            // ── Positions ───────────────────────────────────────
            positions.put(ax).put(ay).put(az);
            positions.put(bx).put(by).put(bz);
            positions.put(cx).put(cy).put(cz);

            // ── Normals ─────────────────────────────────────────
            for (int j = 0; j < 3; j++) {
                normals.put(nx).put(ny).put(nz);
            }

            // ── Colors (placeholder) ────────────────────────────
            for (int j = 0; j < 3; j++) {
                colors.put(1f).put(1f).put(1f);
            }
        }

        positions.flip();
        normals.flip();
        colors.flip();

        // ── Sequential indices ──────────────────────────────────
        IntBuffer indices = BufferUtils.createIntBuffer(newVertexCount);
        for (int i = 0; i < newVertexCount; i++) {
            indices.put(i);
        }
        indices.flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        uploadFloatVBO(positions, 0, 3);
        uploadFloatVBO(colors, 1, 3);
        uploadFloatVBO(normals, 2, 3);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glBindVertexArray(0);

        return new GpuModel(vao, 0, 0, 0, 0, ebo, newVertexCount);
    }

    private static void uploadFloatVBO(FloatBuffer data, int attribIndex, int components) {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(attribIndex, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(attribIndex);
    }

}
