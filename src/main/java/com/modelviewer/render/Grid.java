package com.modelviewer.render;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders a camera-relative ground grid and world-space axis indicators.
 *
 * Grid:
 *   - Lies on the X/Z plane at Y = {@link #GRID_Y} (-0.01) to prevent Z-fighting
 *   - Extends ±{@link #SIZE} units in X and Z, with {@link #STEP}-unit spacing
 *   - Origin tracks floor(cameraX), floor(cameraZ) for the infinite-grid illusion;
 *     the grid shifts by one unit at a time — imperceptible to the viewer
 *   - Rendered with depth test ON, depth write OFF so it always appears behind geometry
 *
 * Axis lines (fixed in world space):
 *   - X axis: red,   (0,0,0) → (10,0,0)
 *   - Y axis: green, (0,0,0) → (0,10,0)
 *   - Z axis: blue,  (0,0,0) → (0,0,10)
 *   - Rendered with depth test ON, depth write ON (occluded by geometry normally)
 *
 * Both use the existing model shader in flat-colour mode (uRenderMode = 2).
 */
public final class Grid {

    private static final int   SIZE   = 100;    // half-extent — grid spans ±SIZE units
    private static final float STEP   = 1.0f;
    private static final float GRID_Y = 0f;
    private static final float AXIS_LEN = 10f;

    // ── Grid VAO / VBOs ───────────────────────────────────────────────────────
    private int gridVao         = 0;
    private int gridVbo         = 0;
    private int gridColorVbo    = 0;
    private int gridVertexCount = 0;

    // ── Axis-line VAO / VBOs ──────────────────────────────────────────────────
    private int axisVao      = 0;
    private int axisVbo      = 0;
    private int axisColorVbo = 0;

    private volatile boolean visible = true;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Allocates VAOs and uploads static geometry. Must be called from the OpenGL thread. */
    public void init() {
        buildGrid();
        buildAxes();
    }

    /** Releases all GPU resources. Must be called from the OpenGL thread. */
    public void free() {
        if (gridVao      != 0) { glDeleteVertexArrays(gridVao);   gridVao      = 0; }
        if (gridVbo      != 0) { glDeleteBuffers(gridVbo);        gridVbo      = 0; }
        if (gridColorVbo != 0) { glDeleteBuffers(gridColorVbo);   gridColorVbo = 0; }
        if (axisVao      != 0) { glDeleteVertexArrays(axisVao);   axisVao      = 0; }
        if (axisVbo      != 0) { glDeleteBuffers(axisVbo);        axisVbo      = 0; }
        if (axisColorVbo != 0) { glDeleteBuffers(axisColorVbo);   axisColorVbo = 0; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders the grid and axis lines for one frame.
     *
     * The shader must be valid; this method manages {@code use()} / {@code unbind()} itself.
     *
     * @param shader  active model shader
     * @param mvp     pre-multiplied projection × view matrix (column-major, 16 floats)
     * @param cameraX world-space eye X — used to derive the camera-relative grid offset
     * @param cameraZ world-space eye Z — used to derive the camera-relative grid offset
     */
    public void render(ShaderProgram shader, float[] mvp, float cameraX, float cameraZ) {
        if (!visible) return;

        // ── Camera-relative grid MVP ──────────────────────────────────────────
        // Snap camera position to nearest integer to prevent per-frame jitter.
        // The grid shifts by exactly 1 unit when the camera crosses a boundary,
        // which is invisible at any normal viewing distance.
        float gx = (float) Math.floor(cameraX);
        float gz = (float) Math.floor(cameraZ);

        // Build a pure translation matrix T (column-major):  translate(gx, 0, gz)
        float[] T = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            gx, 0, gz, 1
        };
        float[] gridMVP = Camera.multiply(mvp, T);

        // ── Draw grid ─────────────────────────────────────────────────────────
        // Depth test ON  → grid is hidden behind any geometry in front of it.
        // Depth write OFF → grid does not pollute the depth buffer, so geometry
        //                    rendered later is unaffected.
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);

        shader.use();
        shader.setUniformMatrix4f("uMVP", gridMVP);
        shader.setUniform1i("uRenderMode", 2);  // flat colour — no lighting
        shader.setUniform1i("uBypassMvp", 0);

        glBindVertexArray(gridVao);
        glDrawArrays(GL_LINES, 0, gridVertexCount);
        glBindVertexArray(0);

        glDepthMask(true);

        // ── Draw axis lines ───────────────────────────────────────────────────
        // Use the unmodified MVP (world-space, no grid offset).
        // Depth write ON so they participate in the normal depth test.
        shader.setUniformMatrix4f("uMVP", mvp);

        glBindVertexArray(axisVao);
        glDrawArrays(GL_LINES, 0, 6);
        glBindVertexArray(0);

        ShaderProgram.unbind();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Visibility toggle
    // ─────────────────────────────────────────────────────────────────────────

    public void    setVisible(boolean v) { this.visible = v; }
    public boolean isVisible()           { return visible;   }

    // ─────────────────────────────────────────────────────────────────────────
    // Geometry builders
    // ─────────────────────────────────────────────────────────────────────────

    private void buildGrid() {
        final int   lines  = SIZE * 2 + 1;     // 201 lines per axis
        final int   verts  = lines * 2 * 2;    // 2 axes × 201 lines × 2 endpoints = 804
        final float extent = SIZE * STEP;       // 100.0

        FloatBuffer pos = BufferUtils.createFloatBuffer(verts * 3);
        FloatBuffer col = BufferUtils.createFloatBuffer(verts * 3);

        // All grid lines use the same dim grey — the axis VAO handles centre-line colour.
        final float r = 0.25f, g = 0.25f, b = 0.28f;

        // Lines parallel to Z (one line per X value)
        for (int i = -SIZE; i <= SIZE; i++) {
            float x = i * STEP;
            pos.put(x).put(GRID_Y).put(-extent);   col.put(r).put(g).put(b);
            pos.put(x).put(GRID_Y).put( extent);   col.put(r).put(g).put(b);
        }

        // Lines parallel to X (one line per Z value)
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
        FloatBuffer pos = BufferUtils.createFloatBuffer(18);   // 6 vertices × 3 floats
        FloatBuffer col = BufferUtils.createFloatBuffer(18);

        // X axis — red
        pos.put(0).put(0).put(0);              col.put(1).put(0).put(0);
        pos.put(AXIS_LEN).put(0).put(0);       col.put(1).put(0).put(0);

        // Y axis — green
        pos.put(0).put(0).put(0);              col.put(0).put(1).put(0);
        pos.put(0).put(AXIS_LEN).put(0);       col.put(0).put(1).put(0);

        // Z axis — blue
        pos.put(0).put(0).put(0);              col.put(0).put(0).put(1);
        pos.put(0).put(0).put(AXIS_LEN);       col.put(0).put(0).put(1);

        pos.flip();
        col.flip();

        axisVao = glGenVertexArrays();
        glBindVertexArray(axisVao);
        axisVbo      = uploadVbo(pos, 0, 3);
        axisColorVbo = uploadVbo(col, 1, 3);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static int uploadVbo(FloatBuffer data, int attrib, int components) {
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(attrib, components, GL_FLOAT, false, components * Float.BYTES, 0L);
        glEnableVertexAttribArray(attrib);
        return vbo;
    }
}
