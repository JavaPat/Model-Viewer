package com.modelviewer.ui;

import com.modelviewer.model.ModelMesh;
import com.modelviewer.render.Renderer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * JavaFX panel that displays the 3D OpenGL viewport.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * LWJGL + JavaFX integration strategy
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * JavaFX and LWJGL/OpenGL cannot share the same window natively.  Instead:
 *
 *  1. A background "render thread" owns a hidden GLFW window (the OpenGL
 *     context lives on that thread).
 *  2. After rendering each frame to an FBO, the render thread calls
 *     glReadPixels() into stagingBuf — a direct ByteBuffer owned exclusively
 *     by the render thread.
 *  3. A JavaFX AnimationTimer fires at ~60 fps.  When a new frame is ready,
 *     it calls pixelBuffer.updateBuffer() whose callback copies stagingBuf →
 *     pixelBuf (the PixelBuffer's backing store) on the FX thread.
 *
 * Buffer ownership:
 *   stagingBuf — render thread only (written by glReadPixels, read by the
 *                updateBuffer callback).  There is a benign data race: the
 *                render thread may start writing the next frame while the FX
 *                thread is still copying the previous one.  In practice this
 *                is invisible (at most one torn row boundary), and it avoids
 *                the fatal race where the render thread advances pixelBuf's
 *                position while JavaFX's D3D pipeline reads it.
 *   pixelBuf  — FX thread only (written only inside the updateBuffer callback,
 *                read by JavaFX's render pipeline).  JavaFX's D3DTexture.update
 *                reads pixelBuf.remaining() during the pipeline flush; keeping
 *                this buffer FX-thread-only ensures position is always 0 and
 *                remaining() always equals the full frame size.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Mouse controls
 * ──────────────────────────────────────────────────────────────────────────────
 *   Left drag   → rotate model
 *   Middle drag → rotate camera
 *   Scroll      → zoom
 */
public final class ViewportPanel extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(ViewportPanel.class);

    private static final int INITIAL_W = 800;
    private static final int INITIAL_H = 600;
    /** Target frame rate for the render thread. */
    private static final long FRAME_NANOS = 1_000_000_000L / 60;

    // ── Shared render state ───────────────────────────────────────────────────
    private final Renderer renderer = new Renderer();

    /** Flag set by the render thread when a new frame is in stagingBuf. */
    private final AtomicBoolean frameReady = new AtomicBoolean(false);

    private volatile int pendingW = INITIAL_W;
    private volatile int pendingH = INITIAL_H;

    // ── Render-thread pixel buffer ────────────────────────────────────────────
    /** Written by the render thread only; read (copied) by the FX thread. */
    private volatile ByteBuffer stagingBuf;

    // ── JavaFX image pipeline (FX thread only) ────────────────────────────────
    private ByteBuffer              pixelBuf;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private WritableImage           writableImage;
    private final ImageView         imageView = new ImageView();

    private Thread         renderThread;
    private AnimationTimer fxTimer;

    private volatile boolean running = false;
    private volatile boolean panUp;
    private volatile boolean panDown;
    private volatile boolean panLeft;
    private volatile boolean panRight;

    private float lastDragX;
    private float lastDragY;
    private boolean draggingPrimary;
    private boolean draggingMiddle;

    /** Optional callback to surface render-thread events to the UI (e.g. debug panel). */
    private Consumer<String> renderEventHandler;

    public ViewportPanel() {
        setStyle("-fx-background-color: #1e1f22;");
        getChildren().add(imageView);
        imageView.setPreserveRatio(false);

        // Resize listener: when the JavaFX layout changes size, notify render thread
        widthProperty() .addListener((obs, o, nv) -> requestResize());
        heightProperty().addListener((obs, o, nv) -> requestResize());

        // Mouse input
        setFocusTraversable(true);
        setOnMousePressed(e -> {
            requestFocus();
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                draggingPrimary = true;
                lastDragX = (float) e.getX();
                lastDragY = (float) e.getY();
            } else if (e.getButton() == javafx.scene.input.MouseButton.MIDDLE) {
                draggingMiddle = true;
                lastDragX = (float) e.getX();
                lastDragY = (float) e.getY();
            }
        });
        setOnMouseReleased(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                draggingPrimary = false;
            } else if (e.getButton() == javafx.scene.input.MouseButton.MIDDLE) {
                draggingMiddle = false;
            }
        });
        setOnMouseDragged(e -> {
            float x = (float) e.getX();
            float y = (float) e.getY();
            float dx = x - lastDragX;
            float dy = y - lastDragY;
            if (draggingPrimary) {
                renderer.rotateModel(dx, dy);
            } else if (draggingMiddle) {
                renderer.getCamera().rotateLook(dx, dy);
            }
            lastDragX = x;
            lastDragY = y;
        });
        setOnScroll(e -> renderer.getCamera().onScroll((float) -e.getDeltaY() / 40f));

        // Key bindings
        setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case W -> panUp = true;
                case S -> panDown = true;
                case A -> panLeft = true;
                case D -> panRight = true;
                case G -> renderer.setGridVisible(!renderer.isGridVisible());
                default -> { }
            }
        });
        setOnKeyReleased(e -> {
            switch (e.getCode()) {
                case W -> panUp = false;
                case S -> panDown = false;
                case A -> panLeft = false;
                case D -> panRight = false;
                default -> { }
            }
        });

        // Initialise the FX-side image surface at the initial size
        rebuildImageSurface(INITIAL_W, INITIAL_H);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Starts the render thread and the JavaFX animation timer. */
    public void start() {
        if (running) return;
        running = true;

        renderThread = new Thread(this::renderLoop, "osrs-render-thread");
        renderThread.setDaemon(true);
        renderThread.start();

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                onFxFrame();
            }
        };
        fxTimer.start();
    }

    /** Stops rendering and frees all GPU resources. */
    public void stop() {
        running = false;
        if (fxTimer != null) fxTimer.stop();
        if (renderThread != null) {
            try { renderThread.join(2000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** Enqueues a mesh for display (may be called from any thread). */
    public void displayMesh(ModelMesh mesh) {
        // 🔥 Reset camera BEFORE loading new model
        renderer.getCamera().reset();

        renderer.setMesh(mesh);
    }

    /** Propagates render mode change to the renderer (thread-safe). */
    public void setRenderMode(int mode) {
        renderer.setRenderMode(mode);
    }

    /** Tells the camera to reset its orientation. */
    public void resetCamera() {
        renderer.getCamera().reset();
    }

    /**
     * Sets a callback invoked (on the FX thread) for render-thread events —
     * both successful milestones (GL init, GPU upload) and errors.
     * Pass {@code null} to disable.
     */
    public void setRenderEventHandler(Consumer<String> handler) {
        this.renderEventHandler = handler;
    }

    // ── JavaFX frame update ───────────────────────────────────────────────────

    /**
     * Called on the JavaFX thread ~60× per second by the AnimationTimer.
     *
     * When a new frame is ready, copies stagingBuf (render-thread-written) →
     * pixelBuf (FX-thread-only) inside the updateBuffer callback so that
     * JavaFX's D3D pipeline always sees a fully-written buffer with position=0.
     */
    private void onFxFrame() {
        if (frameReady.getAndSet(false)) {
            ByteBuffer src = stagingBuf;
            if (src == null) {
                emitRenderEvent("[RENDER] stagingBuf is null — frame skipped");
                return;
            }
            if (src.capacity() != pixelBuf.capacity()) {
                return;  // normal during resize: render thread races ahead; FX catches up next frame
            }
            pixelBuffer.updateBuffer(pb -> {
                ByteBuffer b = pb.getBuffer();
                b.clear();
                src.rewind();
                b.put(src);
                b.rewind();
                return null;   // full-frame update
            });
        }
    }

    // ── Render thread ─────────────────────────────────────────────────────────

    private void renderLoop() {
        try {
            // Allocate the render-thread's staging buffer at the initial size
            stagingBuf = allocDirect(INITIAL_W * INITIAL_H * 4);

            renderer.init(INITIAL_W, INITIAL_H);
            renderer.setEventLogger(this::emitRenderEvent);
            emitRenderEvent("[RENDER] GL context ready (" + INITIAL_W + "×" + INITIAL_H + ")");

            // ── DIAGNOSTIC: enable this flag to test the raw pipeline ─────────
            // With pipelineDiagnosticMode=true the renderer bypasses the FBO,
            // the existing shaders, and the model VAO, and draws a bare yellow
            // triangle directly to GL_BACK using glDrawArrays.
            //
            // YELLOW TRIANGLE VISIBLE  → pipeline works; bug is in FBO or shaders
            // NOTHING VISIBLE          → bug is in readPixels or JavaFX PixelBuffer
            //
            // Set to false once the triangle confirms the pipeline is healthy.
            renderer.setPipelineDiagnosticMode(false);

            long lastFrameStart = System.nanoTime();
            while (running) {
                long frameStart = System.nanoTime();
                float deltaSeconds = (frameStart - lastFrameStart) / 1_000_000_000f;
                lastFrameStart = frameStart;

                // Handle FBO resize entirely on the GL thread
                int w = pendingW, h = pendingH;
                if (renderer.getWidth() != w || renderer.getHeight() != h) {
                    renderer.resize(w, h);
                    stagingBuf = allocDirect(w * h * 4);   // new render-thread buffer
                    final int fw = w, fh = h;
                    Platform.runLater(() -> rebuildImageSurface(fw, fh));
                }

                renderer.getCamera().update(deltaSeconds, panUp, panDown, panLeft, panRight);

                // Render frame into stagingBuf (render thread only)
                try {
                    renderer.renderFrame(stagingBuf);
                    frameReady.set(true);
                } catch (Exception frameEx) {
                    log.error("Frame render error (skipping frame)", frameEx);
                    emitRenderEvent("[RENDER ERROR] " + frameEx.getClass().getSimpleName() + ": " + frameEx.getMessage());
                }

                // Cap at ~60 fps
                long sleep = FRAME_NANOS - (System.nanoTime() - frameStart);
                if (sleep > 0) LockSupport.parkNanos(sleep);
            }
        } catch (Exception e) {
            log.error("Render thread crashed", e);
            emitRenderEvent("[RENDER ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            renderer.destroy();
        }
    }

    /** Posts a render-thread event to the FX thread via the registered handler. */
    private void emitRenderEvent(String msg) {
        Consumer<String> h = renderEventHandler;
        if (h != null) Platform.runLater(() -> h.accept(msg));
    }

    // ── Image surface management (FX thread) ──────────────────────────────────

    /**
     * Rebuilds the FX-side PixelBuffer + WritableImage at the given size.
     * Must be called on the JavaFX Application Thread.
     */
    private void rebuildImageSurface(int w, int h) {
        pixelBuf    = allocDirect(w * h * 4);
        pixelBuffer = new PixelBuffer<>(w, h, pixelBuf, PixelFormat.getByteBgraPreInstance());
        writableImage = new WritableImage(pixelBuffer);
        imageView.setImage(writableImage);
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);
    }

    private void requestResize() {
        int w = (int) Math.max(1, getWidth());
        int h = (int) Math.max(1, getHeight());
        if (w != pendingW || h != pendingH) {
            pendingW = w;
            pendingH = h;
        }
    }

    private static ByteBuffer allocDirect(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
