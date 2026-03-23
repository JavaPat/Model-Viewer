package com.modelviewer.render;

import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Manages a hidden GLFW window used solely as an OpenGL rendering context, and
 * an off-screen Framebuffer Object (FBO) that the scene is rendered into.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Why a hidden window?
 * ──────────────────────────────────────────────────────────────────────────────
 * LWJGL requires a GLFW window to create a valid OpenGL context.  Rather than
 * showing a raw GLFW window alongside the JavaFX window, we:
 *   1. Create a GLFW window with GLFW_VISIBLE = false
 *   2. Render everything into an FBO attached to this context
 *   3. Read the FBO pixels back to a ByteBuffer each frame
 *   4. Hand that ByteBuffer to a JavaFX PixelBuffer so the ViewportPanel
 *      can display it as a normal JavaFX Image
 *
 * This "pixel readback" approach has a modest CPU cost (glReadPixels blocks
 * until the GPU finishes), which is perfectly acceptable for a model viewer.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * FBO structure
 * ──────────────────────────────────────────────────────────────────────────────
 *   Color attachment : GL_RGBA8 texture
 *   Depth attachment : GL_DEPTH24_STENCIL8 renderbuffer
 */
public final class RenderContext {

    private static final Logger log = LoggerFactory.getLogger(RenderContext.class);

    private long  glfwWindow;
    private int   fbo;
    private int   colorTex;
    private int   depthRbo;

    /** Direct-memory buffer used by glReadPixels. Allocated with the FBO, freed in destroyFbo. */
    private ByteBuffer readBuf;

    private int   width;
    private int   height;

    /** True once GLFW and OpenGL have been successfully initialized. */
    private boolean initialized = false;

    /**
     * Initialises GLFW, creates the hidden window and OpenGL context, and builds
     * the initial FBO at the given size.
     *
     * @throws RuntimeException on failure
     */
    public void init(int width, int height) {
        this.width  = width;
        this.height = height;

        // GLFW init
        if (!glfwInit()) throw new RuntimeException("Failed to initialize GLFW");

        glfwWindowHint(GLFW_VISIBLE,    GLFW_FALSE);   // keep window hidden
        glfwWindowHint(GLFW_RESIZABLE,  GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        glfwWindow = glfwCreateWindow(width, height, "offscreen-ctx", NULL, NULL);
        if (glfwWindow == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create GLFW context window");
        }

        glfwMakeContextCurrent(glfwWindow);
        GL.createCapabilities();

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        // No backface culling — model viewer must show all geometry, including
        // hollow meshes, inside surfaces, and models with inconsistent winding.

        buildFbo(width, height);
        initialized = true;
        log.info("OpenGL context initialized ({}×{})", width, height);
    }

    /**
     * Resizes the FBO to a new resolution.  Old FBO resources are freed first.
     * Must be called from the OpenGL thread.
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        width  = newWidth;
        height = newHeight;
        destroyFbo();
        buildFbo(width, height);
        log.debug("FBO resized to {}×{}", width, height);
    }

    /**
     * Binds the FBO as the render target and clears it.
     * The scene is rendered between this call and {@link #readPixels}.
     */
    public void beginFrame(float r, float g, float b) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
        glClearColor(r, g, b, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Reads the rendered pixels from the FBO into {@code dest}.
     *
     * The pixels are in BGRA format to match the JavaFX PixelFormat.BYTE_BGRA_PRE
     * expected by {@link javafx.scene.image.PixelBuffer}.
     *
     * OpenGL stores rows bottom-up; we flip vertically here so JavaFX
     * (which is top-down) displays the image correctly.
     *
     * @param dest pre-allocated direct ByteBuffer of size width * height * 4
     */
    public void readPixels(ByteBuffer dest) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glReadBuffer(GL_COLOR_ATTACHMENT0);

        // readBuf is a direct off-heap buffer — required by LWJGL/OpenGL.
        // Heap buffers (ByteBuffer.wrap / allocate) cannot be passed to glReadPixels
        // because LWJGL cannot obtain a stable native address for them.
        readBuf.clear();
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, readBuf);

        // Flip vertically: OpenGL row 0 = bottom, JavaFX row 0 = top.
        // dest.position is set explicitly per row so concurrent FX-thread reads
        // of stagingBuf (via duplicate()) cannot cause a BufferOverflowException.
        int rowBytes = width * 4;
        for (int row = height - 1; row >= 0; row--) {
            int destRow = (height - 1 - row);
            readBuf.limit(row * rowBytes + rowBytes);
            readBuf.position(row * rowBytes);
            dest.limit(destRow * rowBytes + rowBytes);
            dest.position(destRow * rowBytes);
            dest.put(readBuf);
        }
        dest.rewind();

        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    }

    /** Unbinds the FBO (restores default framebuffer). */
    public void endFrame() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Reads pixels from the DEFAULT framebuffer (GL_BACK) instead of the FBO.
     *
     * Used by the pipeline diagnostic mode to bypass the FBO entirely.
     * The hidden GLFW window's back buffer holds the rendered image even without
     * a glfwSwapBuffers call — glReadPixels can read from it directly.
     *
     * @param dest pre-allocated direct ByteBuffer of size width * height * 4
     */
    public void readPixelsFromDefault(ByteBuffer dest) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glReadBuffer(GL_BACK);

        readBuf.clear();
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, readBuf);

        // Flip vertically: OpenGL row 0 = bottom, JavaFX row 0 = top.
        // dest.position is set explicitly per row so concurrent FX-thread reads
        // of stagingBuf (via duplicate()) cannot cause a BufferOverflowException.
        int rowBytes = width * 4;
        for (int row = height - 1; row >= 0; row--) {
            int destRow = (height - 1 - row);
            readBuf.limit(row * rowBytes + rowBytes);
            readBuf.position(row * rowBytes);
            dest.limit(destRow * rowBytes + rowBytes);
            dest.position(destRow * rowBytes);
            dest.put(readBuf);
        }
        dest.rewind();

        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    }

    public int getWidth()  { return width;  }
    public int getHeight() { return height; }
    public boolean isInitialized() { return initialized; }

    /** Frees all GPU and GLFW resources. Must be called from the OpenGL thread. */
    public void destroy() {
        if (!initialized) return;
        destroyFbo();
        glfwDestroyWindow(glfwWindow);
        glfwTerminate();
        initialized = false;
        log.info("RenderContext destroyed");
    }

    // ── FBO lifecycle ─────────────────────────────────────────────────────────

    private void buildFbo(int w, int h) {
        readBuf = memAlloc(w * h * 4);  // direct off-heap — safe to pass to glReadPixels

        // Color texture
        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        // Depth renderbuffer
        depthRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

        // FBO assembly
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthRbo);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete — status: 0x" + Integer.toHexString(status));
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void destroyFbo() {
        glDeleteFramebuffers(fbo);
        glDeleteTextures(colorTex);
        glDeleteRenderbuffers(depthRbo);
        if (readBuf != null) {
            memFree(readBuf);
            readBuf = null;
        }
    }
}
