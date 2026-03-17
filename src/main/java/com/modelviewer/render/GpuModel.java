package com.modelviewer.render;

import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Holds the OpenGL GPU resources (VAO + VBOs) for one uploaded model.
 *
 * Lifecycle:
 *   1. Created by {@link MeshUploader#upload}
 *   2. Stored in the {@link Renderer} LRU cache
 *   3. Drawn with {@link #draw}
 *   4. Freed with {@link #free} when evicted from the LRU cache
 *
 * Attribute layout (matches model.vert):
 *   location 0 : position  (vec3)
 *   location 1 : colour    (vec3, normalised RGB)
 *   location 2 : normal    (vec3)
 *   location 3 : uv        (vec2, texture coordinates)
 */
public final class GpuModel {

    /** Vertex Array Object — records all VBO bindings and attribute pointers. */
    private final int vao;

    /** VBO: per-vertex positions (x, y, z as floats). */
    private final int positionVbo;

    /** VBO: per-vertex colours (r, g, b as floats in [0,1]). */
    private final int colorVbo;

    /** VBO: per-vertex normals (nx, ny, nz as floats). */
    private final int normalVbo;

    /** VBO: per-vertex UV texture coordinates (u, v as floats). */
    private final int uvVbo;

    /** Element Buffer Object: triangle index triples (GL_UNSIGNED_INT). */
    private final int ebo;

    /** Total number of indices (= faceCount * 3). */
    private final int indexCount;

    /** Whether this model's GPU resources have been released. */
    private boolean freed = false;

    public GpuModel(int vao, int positionVbo, int colorVbo, int normalVbo, int uvVbo, int ebo, int indexCount) {
        this.vao         = vao;
        this.positionVbo = positionVbo;
        this.colorVbo    = colorVbo;
        this.normalVbo   = normalVbo;
        this.uvVbo       = uvVbo;
        this.ebo         = ebo;
        this.indexCount  = indexCount;
    }

    /**
     * Issues the draw call for this model.
     * Assumes the shader program is already active.
     *
     * @param wireframe if true, renders using GL_LINE polygon mode
     */
    public void draw(boolean wireframe) {
        if (freed) return;
        glBindVertexArray(vao);
        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, 0L);
        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);  // restore
        }
        glBindVertexArray(0);
    }

    /**
     * Releases all GPU resources.
     * Must be called from the OpenGL thread.
     */
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

    public boolean isFreed()    { return freed;      }
    public int     getIndexCount() { return indexCount; }
}
