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

public final class MeshUploader {

    private static final Logger log = LoggerFactory.getLogger(MeshUploader.class);

    private MeshUploader() {}

    public static GpuModel upload(ModelMesh mesh) {
        int vCount = mesh.vertexCount;
        int fCount = mesh.faceCount;

        final float INV128 = 1.0f / 128.0f;

        float[] renderX = new float[vCount];
        float[] renderY = new float[vCount];
        float[] renderZ = new float[vCount];

        float minRenderX = Float.MAX_VALUE, maxRenderX = -Float.MAX_VALUE;
        float minRenderY = Float.MAX_VALUE, maxRenderY = -Float.MAX_VALUE;
        float minRenderZ = Float.MAX_VALUE, maxRenderZ = -Float.MAX_VALUE;

        // ✅ FIXED AXIS CONVERSION (OSRS → Y-UP)
        for (int i = 0; i < vCount; i++) {
            float x = mesh.vertexX[i] * INV128;
            float y = mesh.vertexY[i] * INV128;
            float z = mesh.vertexZ[i] * INV128;

            float finalX = -x;
            float finalY = z;   // ← CRITICAL FIX
            float finalZ = -y;

            renderX[i] = finalX;
            renderY[i] = finalY;
            renderZ[i] = finalZ;

            if (finalX < minRenderX) minRenderX = finalX;
            if (finalX > maxRenderX) maxRenderX = finalX;
            if (finalY < minRenderY) minRenderY = finalY;
            if (finalY > maxRenderY) maxRenderY = finalY;
            if (finalZ < minRenderZ) minRenderZ = finalZ;
            if (finalZ > maxRenderZ) maxRenderZ = finalZ;
        }

        float centerX = (minRenderX + maxRenderX) * 0.5f;
        float centerZ = (minRenderZ + maxRenderZ) * 0.5f;
        float groundOffset = minRenderY;

        FloatBuffer positions = BufferUtils.createFloatBuffer(vCount * 3);
        for (int i = 0; i < vCount; i++) {
            positions.put(renderX[i] - centerX);
            positions.put(renderY[i] - groundOffset);
            positions.put(renderZ[i] - centerZ);
        }
        positions.flip();

        float[] nx = new float[vCount];
        float[] ny = new float[vCount];
        float[] nz = new float[vCount];

        for (int i = 0; i < fCount; i++) {
            int ai = mesh.faceVertexA[i];
            int bi = mesh.faceVertexB[i];
            int ci = mesh.faceVertexC[i];
            if (ai < 0 || bi < 0 || ci < 0 || ai >= vCount || bi >= vCount || ci >= vCount) continue;

            float ax = mesh.vertexX[bi] - mesh.vertexX[ai];
            float ay = mesh.vertexY[bi] - mesh.vertexY[ai];
            float az = mesh.vertexZ[bi] - mesh.vertexZ[ai];
            float bx = mesh.vertexX[ci] - mesh.vertexX[ai];
            float by = mesh.vertexY[ci] - mesh.vertexY[ai];
            float bz = mesh.vertexZ[ci] - mesh.vertexZ[ai];

            float fnx = ay * bz - az * by;
            float fny = az * bx - ax * bz;
            float fnz = ax * by - ay * bx;

            nx[ai] += fnx; ny[ai] += fny; nz[ai] += fnz;
            nx[bi] += fnx; ny[bi] += fny; nz[bi] += fnz;
            nx[ci] += fnx; ny[ci] += fny; nz[ci] += fnz;
        }

        FloatBuffer normals = BufferUtils.createFloatBuffer(vCount * 3);
        for (int i = 0; i < vCount; i++) {
            float len = (float) Math.sqrt(nx[i]*nx[i] + ny[i]*ny[i] + nz[i]*nz[i]);
            if (len > 0f) {
                float finalNx = -nx[i];
                float finalNy = nz[i];   // ← CRITICAL FIX
                float finalNz = -ny[i];

                normals.put(finalNx / len);
                normals.put(finalNy / len);
                normals.put(finalNz / len);
            } else {
                normals.put(0f).put(1f).put(0f);
            }
        }
        normals.flip();

        float[] uvU = new float[vCount];
        float[] uvV = new float[vCount];

        float rangeX = (mesh.maxX != mesh.minX) ? (float)(mesh.maxX - mesh.minX) : 1.0f;
        float rangeZ = (mesh.maxZ != mesh.minZ) ? (float)(mesh.maxZ - mesh.minZ) : 1.0f;

        for (int i = 0; i < fCount; i++) {
            int ai = mesh.faceVertexA[i];
            int bi = mesh.faceVertexB[i];
            int ci = mesh.faceVertexC[i];
            if (ai < 0 || bi < 0 || ci < 0 || ai >= vCount || bi >= vCount || ci >= vCount) continue;

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

        float[] colorR = new float[vCount];
        float[] colorG = new float[vCount];
        float[] colorB = new float[vCount];
        int[] colorCount = new int[vCount];

        for (int i = 0; i < fCount; i++) {
            int rgb = ModelDecoder.hslToRgb(mesh.faceColors[i] & 0xFFFF);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >> 8) & 0xFF) / 255f;
            float b = (rgb & 0xFF) / 255f;

            for (int v : new int[]{mesh.faceVertexA[i], mesh.faceVertexB[i], mesh.faceVertexC[i]}) {
                if (v < 0 || v >= vCount) continue;
                colorR[v] += r;
                colorG[v] += g;
                colorB[v] += b;
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

        ShortBuffer indices = BufferUtils.createShortBuffer(fCount * 3);
        for (int i = 0; i < fCount; i++) {
            indices.put((short) mesh.faceVertexA[i]);
            indices.put((short) mesh.faceVertexB[i]);
            indices.put((short) mesh.faceVertexC[i]);
        }
        indices.flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int posVbo = uploadFloatVBO(positions, 0, 3);
        int colVbo = uploadFloatVBO(colors, 1, 3);
        int norVbo = uploadFloatVBO(normals, 2, 3);
        int uvVbo = uploadFloatVBO(uvBuffer, 3, 2);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glBindVertexArray(0);

        return new GpuModel(vao, posVbo, colVbo, norVbo, uvVbo, ebo, fCount * 3);
    }

    private static int uploadFloatVBO(FloatBuffer data, int attribIndex, int components) {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(attribIndex, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(attribIndex);
        return vbo;
    }

    public static GpuModel createTestTriangle() {
        FloatBuffer positions = BufferUtils.createFloatBuffer(9)
                .put(new float[]{
                        -0.7f, -0.7f, 0f,
                        0.7f, -0.7f, 0f,
                        0f,   0.7f,  0f
                });
        positions.flip();

        FloatBuffer colors = BufferUtils.createFloatBuffer(9)
                .put(new float[]{
                        1f, 1f, 0f,
                        1f, 1f, 0f,
                        1f, 1f, 0f
                });
        colors.flip();

        FloatBuffer normals = BufferUtils.createFloatBuffer(9)
                .put(new float[]{
                        0f, 0f, 1f,
                        0f, 0f, 1f,
                        0f, 0f, 1f
                });
        normals.flip();

        FloatBuffer uvs = BufferUtils.createFloatBuffer(6)
                .put(new float[]{
                        0f, 0f,
                        1f, 0f,
                        0.5f, 1f
                });
        uvs.flip();

        IntBuffer indices = BufferUtils.createIntBuffer(3)
                .put(new int[]{0, 1, 2});
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
}