package com.modelviewer.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Holds the OpenGL GPU resources (VAO + VBOs) for one uploaded model.
 */
public final class GpuModel {

    private final int vao;
    private final int positionVbo;
    private final int colorVbo;
    private final int normalVbo;
    private final int uvVbo;
    private final int ebo;
    private final int indexCount;

    // 🔥 NEW: store index type explicitly
    private final int indexType;

    private boolean freed = false;

    public GpuModel(int vao,
                    int positionVbo,
                    int colorVbo,
                    int normalVbo,
                    int uvVbo,
                    int ebo,
                    int indexCount,
                    int indexType) {

        this.vao         = vao;
        this.positionVbo = positionVbo;
        this.colorVbo    = colorVbo;
        this.normalVbo   = normalVbo;
        this.uvVbo       = uvVbo;
        this.ebo         = ebo;
        this.indexCount  = indexCount;
        this.indexType   = indexType; // ✅ critical
    }

    public void draw(boolean wireframe) {
        if (freed) return;

        glBindVertexArray(vao);

        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }

        // ✅ Always matches buffer type
        glDrawElements(GL_TRIANGLES, indexCount, indexType, 0L);

        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        glBindVertexArray(0);
    }

    public void free() {
        if (freed) return;
        freed = true;

        glDeleteVertexArrays(vao);
        glDeleteBuffers(positionVbo);
        glDeleteBuffers(colorVbo);
        glDeleteBuffers(normalVbo);
        glDeleteBuffers(uvVbo);
        glDeleteBuffers(ebo);
    }

    public boolean isFreed() { return freed; }
    public int getIndexCount() { return indexCount; }
}