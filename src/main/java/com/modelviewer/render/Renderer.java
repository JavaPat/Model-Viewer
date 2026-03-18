package com.modelviewer.render;

import com.modelviewer.model.ModelMesh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * High-level renderer that manages:
 *   - The OpenGL render context and FBO
 *   - An LRU cache of GPU-uploaded models (max {@link #MAX_GPU_MODELS})
 *   - The shader programs for solid and wireframe modes
 *   - The orbit camera
 *
 * Threading model:
 *   All OpenGL operations happen on the dedicated render thread owned by
 *   {@link com.modelviewer.ui.ViewportPanel}.  This class must only be
 *   accessed from that thread.
 *
 * Render modes (set via {@link #setRenderMode}):
 *   0 = vertex colour + diffuse lighting
 *   1 = wireframe
 *   2 = flat (vertex colour, no lighting)
 */
public final class Renderer {

    private static final Logger log = LoggerFactory.getLogger(Renderer.class);

    /** Maximum number of models kept resident on the GPU simultaneously. */
    private static final int MAX_GPU_MODELS = 50;

    private final RenderContext context = new RenderContext();
    private final Camera camera = new Camera();

    private ShaderProgram shader;

    /**
     * LRU cache: model ID → GpuModel.
     * LinkedHashMap with accessOrder=true evicts the least-recently-used entry
     * when the map exceeds MAX_GPU_MODELS entries, freeing the GPU buffers.
     */
    private final Map<Integer, GpuModel> gpuCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, GpuModel> eldest) {
            if (size() > MAX_GPU_MODELS) {
                eldest.getValue().free();   // release GPU buffers immediately
                log.debug("LRU evicted model {}", eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /** The model currently being displayed. Null = nothing to render. */
    private volatile ModelMesh pendingMesh = null;
    private GpuModel currentGpuModel = null;
    private int currentModelId = -1;

    /** 0 = colour+lighting, 1 = wireframe, 2 = flat colour */
    private int renderMode = 0;

    /** Optional callback for render-thread log events (GPU uploads, errors). */
    private Consumer<String> eventLogger;

    /** Whether the first successful draw call has been logged yet. */
    private boolean firstDrawLogged = false;

    /**
     * When true, the vertex shader bypasses the MVP matrix and renders raw positions
     * directly as NDC coordinates.  This is only meaningful if the model is already
     * in clip-space range (e.g., in a custom debug upload).
     *
     * USE FOR DEBUGGING:
     *   bypassMvp=true  + model visible   → vertex attributes OK; MVP matrix is broken
     *   bypassMvp=true  + model invisible → vertex attribute binding is broken
     *
     * Toggle via {@link #setBypassMvp(boolean)}.
     */
    private volatile boolean bypassMvp = false;

    /**
     * When true, renders a hardcoded yellow test triangle instead of the real model.
     * Use this to verify the rendering pipeline works independently of model data.
     * Toggle via {@link #setTestTriangleMode(boolean)}.
     */
    private volatile boolean testTriangleMode = false;
    private GpuModel testTriangleModel = null;

    /** Forced debug camera override (eye=(0,0,5), target=(0,0,0)). */
    private volatile boolean debugCameraOverride = false;

    /** Model rotation applied in the shader/model matrix, not by mutating vertices. */
    private static final float DEFAULT_MODEL_ROTATION_X = 0f;
    private static final float DEFAULT_MODEL_ROTATION_Y = 0f;
    private volatile float modelRotationX = DEFAULT_MODEL_ROTATION_X;
    private volatile float modelRotationY = DEFAULT_MODEL_ROTATION_Y;

    // ── Pipeline diagnostic ───────────────────────────────────────────────────
    //
    // When pipelineDiagnosticMode is true, the renderer bypasses:
    //   • The FBO (renders directly to the default framebuffer)
    //   • The existing ShaderProgram (uses two inline minimal shaders)
    //   • The model/VAO system (uses a bare VBO with glDrawArrays)
    //
    // This is the deepest possible isolation test.  If a yellow triangle
    // appears in this mode, everything from OpenGL draw call through JavaFX
    // display is working.  If nothing appears, the issue is in the readback
    // or JavaFX PixelBuffer path.
    //
    // Enable with: renderer.setPipelineDiagnosticMode(true)
    //
    private volatile boolean pipelineDiagnosticMode = false;
    private int diagVao  = 0;
    private int diagVbo  = 0;
    private int diagProg = 0;

    // Minimal vertex shader: passes NDC-space positions through unchanged.
    // No MVP, no uniforms — the triangle vertices ARE the clip-space positions.
    private static final String DIAG_VERT =
        "#version 330 core\n" +
        "layout(location = 0) in vec3 aPos;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(aPos, 1.0);\n" +
        "}\n";

    // Minimal fragment shader: outputs solid yellow for every fragment.
    // If any fragment appears at all it will be unmistakably visible.
    private static final String DIAG_FRAG =
        "#version 330 core\n" +
        "out vec4 FragColor;\n" +
        "void main() {\n" +
        "    FragColor = vec4(1.0, 1.0, 0.0, 1.0);\n" +
        "}\n";

    /** Compiles and links the diagnostic shaders and uploads a 3-vertex NDC triangle. */
    private void initDiagnosticPipeline() {
        // Vertex shader
        int vert = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vert, DIAG_VERT);
        glCompileShader(vert);
        if (glGetShaderi(vert, GL_COMPILE_STATUS) == GL_FALSE) {
            log.error("[DIAG] Vertex shader compile failed:\n{}", glGetShaderInfoLog(vert));
        } else {
            log.info("[DIAG] Vertex shader compiled OK");
        }

        // Fragment shader
        int frag = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(frag, DIAG_FRAG);
        glCompileShader(frag);
        if (glGetShaderi(frag, GL_COMPILE_STATUS) == GL_FALSE) {
            log.error("[DIAG] Fragment shader compile failed:\n{}", glGetShaderInfoLog(frag));
        } else {
            log.info("[DIAG] Fragment shader compiled OK");
        }

        // Link program
        diagProg = glCreateProgram();
        glAttachShader(diagProg, vert);
        glAttachShader(diagProg, frag);
        glLinkProgram(diagProg);
        glDeleteShader(vert);
        glDeleteShader(frag);
        if (glGetProgrami(diagProg, GL_LINK_STATUS) == GL_FALSE) {
            log.error("[DIAG] Shader link failed:\n{}", glGetProgramInfoLog(diagProg));
        } else {
            log.info("[DIAG] Shader program linked OK — prog={}", diagProg);
        }

        // VAO + VBO: a simple triangle fully inside NDC space
        // Vertices: bottom-left (-0.5,-0.5), bottom-right (0.5,-0.5), top-center (0,0.5)
        float[] verts = { -0.5f, -0.5f, 0f,
                           0.5f, -0.5f, 0f,
                           0.0f,  0.5f, 0f };

        diagVao = glGenVertexArrays();
        glBindVertexArray(diagVao);

        diagVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, diagVbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Report any setup errors
        int err;
        while ((err = glGetError()) != GL_NO_ERROR) {
            log.error("[DIAG] GL error during diagnostic init: 0x{}", Integer.toHexString(err));
        }

        log.info("[DIAG] Pipeline diagnostic ready — VAO={} VBO={} prog={}", diagVao, diagVbo, diagProg);
        log.info("[DIAG] Rendering to default framebuffer (GL_BACK), bypassing FBO");
    }

    /** Renders the diagnostic triangle and writes the result into {@code dest}. */
    private void renderDiagnosticFrame(ByteBuffer dest) {
        // Bypass FBO entirely — render directly to the hidden GLFW window's back buffer.
        // The window is invisible but its back buffer exists and glReadPixels can read it.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWidth(), context.getHeight());
        glClearColor(0f, 0f, 0f, 1f);   // black — yellow triangle is unmistakable
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glUseProgram(diagProg);
        glBindVertexArray(diagVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);

        // Report GL errors immediately after the draw
        int err;
        boolean anyErr = false;
        while ((err = glGetError()) != GL_NO_ERROR) {
            anyErr = true;
            String msg = "[DIAG] GL error 0x" + Integer.toHexString(err) + " after draw";
            log.error(msg);
            Consumer<String> el = eventLogger;
            if (el != null) el.accept(msg);
        }
        if (!anyErr) {
            log.info("[DIAG] glDrawArrays(GL_TRIANGLES, 0, 3) — no GL errors");
        }

        // Read from default framebuffer (GL_BACK) instead of FBO
        context.readPixelsFromDefault(dest);

        // Report any errors from the readback itself
        while ((err = glGetError()) != GL_NO_ERROR) {
            String msg = "[DIAG] GL error 0x" + Integer.toHexString(err) + " after readPixels";
            log.error(msg);
            Consumer<String> el = eventLogger;
            if (el != null) el.accept(msg);
        }
    }

    // ── Background colour ────────────────────────────────────────────────────
    private float bgR = 0.12f, bgG = 0.12f, bgB = 0.14f;

    // ── Ground grid + axis lines ──────────────────────────────────────────────
    private final Grid grid = new Grid();

    public void init(int width, int height) {
        context.init(width, height);
        shader = new ShaderProgram("/shaders/model.vert", "/shaders/model.frag");
        camera.setAspect((float) width / height);

        // Initialize the raw pipeline diagnostic (separate VAO, inline shaders, no FBO).
        initDiagnosticPipeline();

        // Build ground grid + axis lines.
        grid.init();

        log.info("Renderer initialized ({}×{})", width, height);
    }

    /**
     * Enqueues a new model for display.
     * The mesh will be uploaded to the GPU on the next call to {@link #renderFrame}.
     * Thread-safe: may be called from any thread.
     */
    public void setMesh(ModelMesh mesh) {
        this.pendingMesh = mesh;
    }

    /**
     * Renders one frame and writes the pixel data into {@code dest}.
     * Must be called from the OpenGL thread.
     *
     * @param dest pixel buffer managed by the ViewportPanel (BGRA, top-down)
     */
    public void renderFrame(ByteBuffer dest) {
        // ── Handle pending mesh (upload to GPU) ───────────────────────────────
        ModelMesh mesh = pendingMesh;
        if (mesh != null && mesh.modelId != currentModelId) {
            pendingMesh = null;

            GpuModel cached = gpuCache.get(mesh.modelId);
            if (cached == null) {
                cached = MeshUploader.upload(mesh);
                gpuCache.put(mesh.modelId, cached);
                String uploadMsg = String.format("[RENDER] GPU upload #%d — %d verts, %d faces",
                        mesh.modelId, mesh.vertexCount, mesh.faceCount);
                log.info("Uploaded model {} to GPU — {} verts, {} faces ({} cached)",
                        mesh.modelId, mesh.vertexCount, mesh.faceCount, gpuCache.size());
                Consumer<String> el = eventLogger;
                if (el != null) el.accept(uploadMsg);
            }

            currentGpuModel = cached;
            currentModelId  = mesh.modelId;
            firstDrawLogged = false;
            modelRotationX = DEFAULT_MODEL_ROTATION_X;
            modelRotationY = DEFAULT_MODEL_ROTATION_Y;

            // 🔥 FIXED CAMERA FRAMING (THIS WAS THE PROBLEM)
            final float SCALE = 1.0f / 128.0f;

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

            for (int i = 0; i < mesh.vertexCount; i++) {
                float x = mesh.vertexX[i] * SCALE;
                float y = mesh.vertexY[i] * SCALE;
                float z = mesh.vertexZ[i] * SCALE;

                float rx = x;
                float ry = z;
                float rz = -y;

                if (rx < minX) minX = rx;
                if (rx > maxX) maxX = rx;
                if (ry < minY) minY = ry;
                if (ry > maxY) maxY = ry;
                if (rz < minZ) minZ = rz;
                if (rz > maxZ) maxZ = rz;
            }

            float centerX = (minX + maxX) * 0.5f;
            float centerY = (minY + maxY) * 0.5f;
            float centerZ = (minZ + maxZ) * 0.5f;

            float sizeX = maxX - minX;
            float sizeZ = maxZ - minZ;
            float sizeY = maxY - minY;
            float radius = Math.max(Math.max(sizeX, sizeZ), sizeY);

            // Clamp to prevent insane zoom from broken models
            radius = Math.max(1f, Math.min(radius, 100f));

            // Frame camera properly
            camera.frameModel(centerX, centerY, centerZ, radius);
        }

        // ── Pipeline diagnostic ───────────────────────────────────────────────
        if (pipelineDiagnosticMode) {
            renderDiagnosticFrame(dest);
            return;
        }

        // ── Render ────────────────────────────────────────────────────────────
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWidth(), context.getHeight());
        glClearColor(bgR, bgG, bgB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        while (glGetError() != GL_NO_ERROR) {}

        if (testTriangleMode && testTriangleModel != null && !testTriangleModel.isFreed()) {
            shader.use();
            float[] identity = {
                    1,0,0,0,
                    0,1,0,0,
                    0,0,1,0,
                    0,0,0,1
            };
            shader.setUniformMatrix4f("uMVP", identity);
            shader.setUniformMatrix4f("uModel", identity);
            shader.setUniform1i("uRenderMode", 2);
            shader.setUniform3f("uLightDir", 0f, 1f, 0f);
            shader.setUniform3f("uWireColor", 1f, 1f, 0f);
            testTriangleModel.draw(false);
            ShaderProgram.unbind();

        } else if (currentGpuModel != null && !currentGpuModel.isFreed()) {
            shader.use();

            float[] proj = camera.getProjectionMatrix();
            float[] view = camera.getViewMatrix();
            float[] model = buildModelMatrix();
            float[] vp = Camera.multiply(proj, view);
            float[] mvp = Camera.multiply(vp, model);

            shader.setUniformMatrix4f("uMVP", mvp);
            shader.setUniformMatrix4f("uModel", model);
            shader.setUniform1i("uRenderMode", renderMode);
            shader.setUniform3f("uLightDir", 0.5f, 1.0f, 0.7f);
            shader.setUniform3f("uWireColor", 0.85f, 0.85f, 0.85f);
            shader.setUniform1i("uBypassMvp", bypassMvp ? 1 : 0);

            currentGpuModel.draw(renderMode == 1);

            ShaderProgram.unbind();
        }

        {
            float[] proj = camera.getProjectionMatrix();
            float[] view = camera.getViewMatrix();
            float[] mvp  = Camera.multiply(proj, view);
            float[] eye  = camera.getEyePosition();
            grid.render(shader, mvp, eye[0], eye[2]);
        }

        context.readPixelsFromDefault(dest);
    }

    /**
     * Resizes the rendering surface.  Must be called from the OpenGL thread.
     */
    public void resize(int width, int height) {
        context.resize(width, height);
        camera.setAspect((float) width / height);
    }

    public void destroy() {
        for (GpuModel m : gpuCache.values()) m.free();
        gpuCache.clear();
        if (testTriangleModel != null) testTriangleModel.free();
        if (diagVao  != 0) { glDeleteVertexArrays(diagVao); diagVao  = 0; }
        if (diagVbo  != 0) { glDeleteBuffers(diagVbo);      diagVbo  = 0; }
        if (diagProg != 0) { glDeleteProgram(diagProg);     diagProg = 0; }
        grid.free();
        if (shader != null) shader.delete();
        context.destroy();
    }

    // ── Getters / setters accessed from UI thread (volatile / immutable) ─────

    public Camera getCamera() { return camera; }

    public void setRenderMode(int mode) { this.renderMode = mode; }
    public int  getRenderMode()         { return renderMode; }

    /**
     * Enables or disables the test-triangle diagnostic mode.
     *
     * When enabled, a hardcoded yellow triangle is rendered instead of any model.
     * The triangle is in NDC space and does not depend on the camera or model data.
     *
     * HOW TO USE:
     *   1. Call renderer.setTestTriangleMode(true) before the first frame.
     *   2. If a yellow triangle appears → the pipeline (FBO, shaders, readback) works.
     *      The issue is in model data or camera setup.
     *   3. If nothing appears → the issue is in the pipeline itself (OpenGL context,
     *      FBO readback, JavaFX PixelBuffer, or shader compilation).
     */
    public void setTestTriangleMode(boolean enabled) { this.testTriangleMode = enabled; }

    /**
     * Enables or disables the forced debug camera pose:
     * eye=(0,0,5), target=(0,0,0). Useful to confirm camera/scale issues.
     */
    public void setDebugCameraOverride(boolean enabled) {
        this.debugCameraOverride = enabled;
        camera.setDebugLookAt(0f, 2.5f, 6f, 0f, 1f, 0f);
        camera.setDebugOverride(enabled);
    }

    /**
     * Enables or disables the raw pipeline diagnostic mode.
     *
     * When enabled, every frame bypasses ALL existing rendering infrastructure:
     *   • No FBO — renders directly to the default framebuffer (GL_BACK)
     *   • No existing ShaderProgram — uses two inline minimal shaders
     *   • No model VAO system — uses a bare VBO + glDrawArrays
     *
     * A yellow triangle should appear in the center of the viewport.
     *
     * INTERPRETATION:
     *   Triangle visible   → OpenGL draw + readPixelsFromDefault + JavaFX all work.
     *                         The bug is in the FBO, existing shaders, or model VAO.
     *   Triangle not visible → The bug is in readPixels or JavaFX PixelBuffer display.
     *                          Check ViewportPanel threading and PixelBuffer.updateBuffer().
     */
    public void setPipelineDiagnosticMode(boolean enabled) { this.pipelineDiagnosticMode = enabled; }

    /**
     * Enables or disables MVP bypass in the vertex shader.
     *
     * When enabled, the vertex shader uses raw positions (no camera transform).
     * Useful only when the model is already in clip-space range.
     *
     * bypassMvp=true  + model visible   → vertex attributes correct; fix MVP matrix
     * bypassMvp=true  + model invisible → vertex attribute binding is broken
     */
    public void setBypassMvp(boolean bypass) { this.bypassMvp = bypass; }

    public void setGridVisible(boolean visible) { grid.setVisible(visible); }
    public boolean isGridVisible()              { return grid.isVisible(); }

    public void rotateModel(float dx, float dy) {
        final float sensitivity = (float) Math.toRadians(0.2f);
        modelRotationY += dx * sensitivity;
        modelRotationX += dy * sensitivity;
        float maxPitch = (float) Math.toRadians(89.0);
        modelRotationX = Math.max(-maxPitch, Math.min(maxPitch, modelRotationX));
    }

    public int  getWidth()  { return context.getWidth();  }
    public int  getHeight() { return context.getHeight(); }

    public boolean isInitialized() { return context.isInitialized(); }

    /** Sets a callback invoked on the render thread for GPU-upload events. */
    public void setEventLogger(Consumer<String> logger) { this.eventLogger = logger; }

    private float[] buildModelMatrix() {
        float sx = (float) Math.sin(modelRotationX);
        float cx = (float) Math.cos(modelRotationX);
        float sy = (float) Math.sin(modelRotationY);
        float cy = (float) Math.cos(modelRotationY);

        float[] rotateX = {
            1, 0, 0, 0,
            0, cx, sx, 0,
            0, -sx, cx, 0,
            0, 0, 0, 1
        };
        float[] rotateY = {
            cy, 0, -sy, 0,
            0, 1, 0, 0,
            sy, 0, cy, 0,
            0, 0, 0, 1
        };
        return Camera.multiply(rotateY, rotateX);
    }
}
