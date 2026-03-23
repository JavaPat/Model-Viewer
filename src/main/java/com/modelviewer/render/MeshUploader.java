package com.modelviewer.render;

import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public final class MeshUploader {

    private static final Logger log = LoggerFactory.getLogger(MeshUploader.class);

    private MeshUploader() {}

    public static GpuModel upload(ModelMesh mesh) {

        int fCount = mesh.faceCount;
        final float SCALE = 1.0f / 512.0f;

        // Expand vertices per-face so each triangle carries its own flat color.
        // Shared vertices between faces would otherwise fight over a single color value.
        int expanded = fCount * 3;

        FloatBuffer positions = BufferUtils.createFloatBuffer(expanded * 3);
        FloatBuffer normals   = BufferUtils.createFloatBuffer(expanded * 3);
        FloatBuffer colors    = BufferUtils.createFloatBuffer(expanded * 3);

        int vCount = mesh.vertexCount;
        int skipped = 0;
        for (int i = 0; i < fCount; i++) {
            int ia = mesh.faceVertexA[i];
            int ib = mesh.faceVertexB[i];
            int ic = mesh.faceVertexC[i];

            // Skip faces that reference out-of-range vertices (decoder bug safety net)
            if (ia < 0 || ia >= vCount || ib < 0 || ib >= vCount || ic < 0 || ic >= vCount) {
                skipped++;
                positions.put(0f).put(0f).put(0f);
                positions.put(0f).put(0f).put(0f);
                positions.put(0f).put(0f).put(0f);
                colors.put(0f).put(0f).put(0f);
                colors.put(0f).put(0f).put(0f);
                colors.put(0f).put(0f).put(0f);
                normals.put(0f).put(1f).put(0f);
                normals.put(0f).put(1f).put(0f);
                normals.put(0f).put(1f).put(0f);
                continue;
            }

            float ax = mesh.vertexX[ia] * SCALE, ay = -mesh.vertexY[ia] * SCALE, az = mesh.vertexZ[ia] * SCALE;
            float bx = mesh.vertexX[ib] * SCALE, by = -mesh.vertexY[ib] * SCALE, bz = mesh.vertexZ[ib] * SCALE;
            float cx = mesh.vertexX[ic] * SCALE, cy = -mesh.vertexY[ic] * SCALE, cz = mesh.vertexZ[ic] * SCALE;

            // Face normal via cross product of two edges
            float ex1 = bx - ax, ey1 = by - ay, ez1 = bz - az;
            float ex2 = cx - ax, ey2 = cy - ay, ez2 = cz - az;
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0) { nx /= len; ny /= len; nz /= len; }
            else         { nx = 0f;   ny = 1f;   nz = 0f;   }

            // HSL face color → RGB
            float r = 0.67f, g = 0.67f, b = 0.67f;
            if (mesh.faceColors != null && i < mesh.faceColors.length) {
                int rgb = ModelDecoder.hslToRgb(mesh.faceColors[i] & 0xFFFF);
                r = ((rgb >> 16) & 0xFF) / 255f;
                g = ((rgb >>  8) & 0xFF) / 255f;
                b = ( rgb        & 0xFF) / 255f;
            }

            positions.put(ax).put(ay).put(az);
            positions.put(bx).put(by).put(bz);
            positions.put(cx).put(cy).put(cz);

            colors.put(r).put(g).put(b);
            colors.put(r).put(g).put(b);
            colors.put(r).put(g).put(b);

            normals.put(nx).put(ny).put(nz);
            normals.put(nx).put(ny).put(nz);
            normals.put(nx).put(ny).put(nz);
        }

        if (skipped > 0) {
            log.warn("Model {} upload: skipped {}/{} faces with out-of-range indices (vertexCount={})",
                    mesh.modelId, skipped, fCount, vCount);
        } else {
            log.info("Model {} upload: all {} faces valid (vertexCount={})", mesh.modelId, fCount, vCount);
        }

        positions.flip();
        colors.flip();
        normals.flip();

        // Sequential index buffer (no vertex sharing)
        IntBuffer indices = BufferUtils.createIntBuffer(expanded);
        for (int i = 0; i < expanded; i++) indices.put(i);
        indices.flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int posVbo = uploadFloatVBO(positions, 0, 3);
        int colVbo = uploadFloatVBO(colors,    1, 3);
        int norVbo = uploadFloatVBO(normals,   2, 3);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glBindVertexArray(0);

        return new GpuModel(vao, posVbo, colVbo, norVbo, 0, ebo, expanded, GL_UNSIGNED_INT);
    }

    private static int uploadFloatVBO(FloatBuffer data, int attribIndex, int components) {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(attribIndex, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(attribIndex);
        return vbo;
    }

}
