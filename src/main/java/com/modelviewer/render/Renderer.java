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

public final class Renderer {

    private static final Logger log = LoggerFactory.getLogger(Renderer.class);
    private static final int MAX_GPU_MODELS = 50;

    private final RenderContext context = new RenderContext();
    private final Camera camera = new Camera();
    private ShaderProgram shader;

    private final Map<Integer, GpuModel> gpuCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, GpuModel> eldest) {
            if (size() > MAX_GPU_MODELS) {
                eldest.getValue().free();
                log.debug("LRU evicted model {}", eldest.getKey());
                return true;
            }
            return false;
        }
    };

    private volatile ModelMesh pendingMesh = null;
    private GpuModel currentGpuModel = null;
    private int currentModelId = -1;

    private int renderMode = 0;
    private Consumer<String> eventLogger;

    private static final float DEFAULT_MODEL_ROTATION_X = 0f;
    private static final float DEFAULT_MODEL_ROTATION_Y = 0f;
    private volatile float modelRotationX = DEFAULT_MODEL_ROTATION_X;
    private volatile float modelRotationY = DEFAULT_MODEL_ROTATION_Y;

    private float bgR = 0.12f, bgG = 0.12f, bgB = 0.14f;
    private final Grid grid = new Grid();

    public void init(int width, int height) {
        context.init(width, height);
        shader = new ShaderProgram("/shaders/model.vert", "/shaders/model.frag");
        camera.setAspect((float) width / height);
        grid.init();
        log.info("Renderer initialized ({}×{})", width, height);
    }

    public void setMesh(ModelMesh mesh) {
        this.pendingMesh = mesh;
    }

    public void renderFrame(ByteBuffer dest) {
        ModelMesh mesh = pendingMesh;
        if (mesh != null && mesh.modelId != currentModelId) {
            pendingMesh = null;

            GpuModel cached = gpuCache.get(mesh.modelId);
            if (cached == null) {
                cached = MeshUploader.upload(mesh);
                gpuCache.put(mesh.modelId, cached);
            }

            currentGpuModel = cached;
            currentModelId  = mesh.modelId;

            // ✅ SAFE CAMERA FRAMING
            final float SCALE = 1.0f / 512.0f;

            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

            for (int i = 0; i < mesh.vertexCount; i++) {
                float x = mesh.vertexX[i] * SCALE;
                float y = mesh.vertexY[i] * SCALE;
                float z = mesh.vertexZ[i] * SCALE;

                float rx = x;
                float ry = -y;
                float rz = z;

                if (!Float.isFinite(rx) || !Float.isFinite(ry) || !Float.isFinite(rz)) continue;

                if (rx < minX) minX = rx;
                if (rx > maxX) maxX = rx;
                if (ry < minY) minY = ry;
                if (ry > maxY) maxY = ry;
                if (rz < minZ) minZ = rz;
                if (rz > maxZ) maxZ = rz;
            }

            float radius = 2.0f;
            float centerX = 0f, centerY = 0f, centerZ = 0f;

            if (minX != Float.MAX_VALUE) {
                float sizeX = maxX - minX;
                float sizeZ = maxZ - minZ;
                float sizeY = maxY - minY;

                radius = Math.max(Math.max(sizeX, sizeZ), sizeY) * 0.5f;

                if (!Float.isFinite(radius) || radius <= 0f) {
                    radius = 2.0f;
                }

                radius = Math.min(radius, 25.0f);
                radius = Math.max(radius, 1.0f);

                centerX = (minX + maxX) * 0.5f;
                centerY = (minY + maxY) * 0.5f;
                centerZ = (minZ + maxZ) * 0.5f;
            }

            camera.frameModel(centerX, centerY, centerZ, radius);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, context.getWidth(), context.getHeight());
        glClearColor(bgR, bgG, bgB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        if (currentGpuModel != null && !currentGpuModel.isFreed()) {
            shader.use();

            float[] proj = camera.getProjectionMatrix();
            float[] view = camera.getViewMatrix();
            float[] model = buildModelMatrix();
            float[] vp = Camera.multiply(proj, view);
            float[] mvp = Camera.multiply(vp, model);

            shader.setUniformMatrix4f("uMVP", mvp);
            shader.setUniformMatrix4f("uModel", model);
            shader.setUniform1i("uRenderMode", renderMode);
            shader.setUniform3f("uLightDir", 0.6f, 1.0f, 0.8f);
            shader.setUniform3f("uWireColor", 1.0f, 1.0f, 1.0f);
            shader.setUniform1i("uHasTexture", 0);

            currentGpuModel.draw(renderMode == 1);

            ShaderProgram.unbind();
        }

        float[] proj = camera.getProjectionMatrix();
        float[] view = camera.getViewMatrix();
        float[] mvp  = Camera.multiply(proj, view);
        float[] eye  = camera.getEyePosition();
        grid.render(shader, mvp, eye[0], eye[2]);

        context.readPixelsFromDefault(dest);
    }

    public void resize(int width, int height) {
        context.resize(width, height);
        camera.setAspect((float) width / height);
    }

    public void destroy() {
        for (GpuModel m : gpuCache.values()) m.free();
        gpuCache.clear();
        grid.free();
        if (shader != null) shader.delete();
        context.destroy();
    }

// ✅ RESTORED METHODS (fix your errors)

    public Camera getCamera() { return camera; }

    public void setRenderMode(int mode) { this.renderMode = mode; }
    public int getRenderMode() { return renderMode; }

    public void rotateModel(float dx, float dy) {
        final float sensitivity = (float) Math.toRadians(0.2f);
        modelRotationY += dx * sensitivity;
        modelRotationX += dy * sensitivity;
    }

    public void setGridVisible(boolean visible) { grid.setVisible(visible); }
    public boolean isGridVisible() { return grid.isVisible(); }

    public int getWidth() { return context.getWidth(); }
    public int getHeight() { return context.getHeight(); }

    public void setEventLogger(Consumer<String> logger) { this.eventLogger = logger; }

    public void setPipelineDiagnosticMode(boolean enabled) {
        // (kept for compatibility — no-op)
    }

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
