package com.modelviewer.render;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public final class Grid {

    private static final int   SIZE   = 100;
    private static final float STEP   = 1.0f;

    // ✅ FIX: move grid slightly below world
    private static final float GRID_Y = -0.01f;

    private static final float AXIS_LEN = 10f;

    private int gridVao         = 0;
    private int gridVbo         = 0;
    private int gridColorVbo    = 0;
    private int gridVertexCount = 0;

    private int axisVao      = 0;
    private int axisVbo      = 0;
    private int axisColorVbo = 0;

    private volatile boolean visible = true;

    public void init() {
        buildGrid();
        buildAxes();
    }

    public void free() {
        if (gridVao      != 0) { glDeleteVertexArrays(gridVao);   gridVao      = 0; }
        if (gridVbo      != 0) { glDeleteBuffers(gridVbo);        gridVbo      = 0; }
        if (gridColorVbo != 0) { glDeleteBuffers(gridColorVbo);   gridColorVbo = 0; }
        if (axisVao      != 0) { glDeleteVertexArrays(axisVao);   axisVao      = 0; }
        if (axisVbo      != 0) { glDeleteBuffers(axisVbo);        axisVbo      = 0; }
        if (axisColorVbo != 0) { glDeleteBuffers(axisColorVbo);   axisColorVbo = 0; }
    }

    public void render(ShaderProgram shader, float[] mvp, float cameraX, float cameraZ) {
        if (!visible) return;

        float gx = (float) Math.floor(cameraX);
        float gz = (float) Math.floor(cameraZ);

        // ✅ FIX: include GRID_Y in translation
        float[] T = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                gx, GRID_Y, gz, 1
        };

        float[] gridMVP = Camera.multiply(mvp, T);

        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);

        shader.use();

        float[] identity = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };

        shader.setUniformMatrix4f("uMVP", gridMVP);
        shader.setUniformMatrix4f("uModel", identity);
        shader.setUniform1i("uRenderMode", 2);
        shader.setUniform1i("uBypassMvp", 0);

        glBindVertexArray(gridVao);
        glDrawArrays(GL_LINES, 0, gridVertexCount);
        glBindVertexArray(0);

        glDepthMask(true);

        shader.setUniformMatrix4f("uMVP", mvp);
        shader.setUniformMatrix4f("uModel", identity);

        glBindVertexArray(axisVao);
        glDrawArrays(GL_LINES, 0, 6);
        glBindVertexArray(0);

        ShaderProgram.unbind();
    }

    public void setVisible(boolean v) { this.visible = v; }
    public boolean isVisible() { return visible; }

    private void buildGrid() {
        final int   lines  = SIZE * 2 + 1;
        final int   verts  = lines * 2 * 2;
        final float extent = SIZE * STEP;

        FloatBuffer pos = BufferUtils.createFloatBuffer(verts * 3);
        FloatBuffer col = BufferUtils.createFloatBuffer(verts * 3);

        final float r = 0.25f, g = 0.25f, b = 0.28f;

        for (int i = -SIZE; i <= SIZE; i++) {
            float x = i * STEP;
            pos.put(x).put(GRID_Y).put(-extent);   col.put(r).put(g).put(b);
            pos.put(x).put(GRID_Y).put( extent);   col.put(r).put(g).put(b);
        }

        for (int i = -SIZE; i <= SIZE; i++) {
            float z = i * STEP;
            pos.put(-extent).put(GRID_Y).put(z);   col.put(r).put(g).put(b);
            pos.put( extent).put(GRID_Y).put(z);   col.put(r).put(g).put(b);
        }

        pos.flip();
        col.flip();

        gridVao = glGenVertexArrays();
        glBindVertexArray(gridVao);
        gridVbo      = uploadVbo(pos, 0, 3);
        gridColorVbo = uploadVbo(col, 1, 3);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        gridVertexCount = verts;
    }

    private void buildAxes() {
        FloatBuffer pos = BufferUtils.createFloatBuffer(18);
        FloatBuffer col = BufferUtils.createFloatBuffer(18);

        pos.put(0).put(0).put(0);        col.put(1).put(0).put(0);
        pos.put(AXIS_LEN).put(0).put(0); col.put(1).put(0).put(0);

        pos.put(0).put(0).put(0);        col.put(0).put(1).put(0);
        pos.put(0).put(AXIS_LEN).put(0); col.put(0).put(1).put(0);

        pos.put(0).put(0).put(0);        col.put(0).put(0).put(1);
        pos.put(0).put(0).put(AXIS_LEN); col.put(0).put(0).put(1);

        pos.flip();
        col.flip();

        axisVao = glGenVertexArrays();
        glBindVertexArray(axisVao);
        axisVbo      = uploadVbo(pos, 0, 3);
        axisColorVbo = uploadVbo(col, 1, 3);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private static int uploadVbo(FloatBuffer data, int attrib, int components) {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(attrib, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(attrib);
        return vbo;
    }
}