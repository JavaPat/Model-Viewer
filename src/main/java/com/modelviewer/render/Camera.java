package com.modelviewer.render;

/**
 * Orbit-style camera that produces View and Projection matrices for OpenGL.
 *
 * Controls:
 *   Left-mouse drag  → rotate (azimuth + elevation)
 *   Scroll wheel     → zoom (move eye along the radial axis)
 *   Middle-mouse drag → pan (translate the pivot point in screen space)
 *
 * Coordinate system: right-handed, Y-up (standard OpenGL convention).
 * OSRS uses Y-up too, but with negative Y for "up" visually — the camera
 * compensates by looking at the model from a slight elevation.
 */
public final class Camera {

    // ── Orbit parameters ─────────────────────────────────────────────────────

    /** Horizontal rotation angle around the world Y-axis (radians). */
    private float azimuth   = 0f;

    /** Vertical rotation angle (radians). Clamped to avoid gimbal lock. */
    private float elevation = 0.4f;

    /** Distance from camera to pivot point. */
    private float radius    = 3.0f;

    /** World-space pivot point (camera always looks at this). */
    private float pivotX    = 0f;
    private float pivotY    = 1.5f;
    private float pivotZ    = 0f;

    // ── Projection parameters ────────────────────────────────────────────────

    private float fovY      = (float) Math.toRadians(70.0);
    private float aspect    = 1.0f;
    private float near      = 0.1f;
    private float far       = 500.0f;

    // ── Debug override (forced camera pose) ─────────────────────────────────
    private boolean debugOverride = false;
    private float debugEyeX = 0f, debugEyeY = 0f, debugEyeZ = 5f;
    private float debugTargetX = 0f, debugTargetY = 0f, debugTargetZ = 0f;

    // ── Drag tracking (populated by ViewportPanel input handlers) ────────────

    private float lastMouseX, lastMouseY;
    private boolean draggingLeft, draggingMiddle;

    public Camera() {}

    // ── Input handlers ────────────────────────────────────────────────────────

    public void onMousePressed(float x, float y, boolean left, boolean middle) {
        lastMouseX = x;
        lastMouseY = y;
        if (left)   draggingLeft   = true;
        if (middle) draggingMiddle = true;
    }

    public void onMouseReleased(boolean left, boolean middle) {
        if (left)   draggingLeft   = false;
        if (middle) draggingMiddle = false;
    }

    public void onMouseDragged(float x, float y) {
        float dx = x - lastMouseX;
        float dy = y - lastMouseY;
        lastMouseX = x;
        lastMouseY = y;

        if (draggingLeft) {
            // Orbit: horizontal drag rotates around Y (azimuth)
            //        vertical drag changes elevation
            azimuth   -= dx * 0.005f;
            elevation += dy * 0.005f;
            elevation = Math.max(-1.5f, Math.min(1.5f, elevation)); // clamp
        }

        if (draggingMiddle) {
            // Pan: move the pivot in the camera's right/up directions
            float[] right = getRightVector();
            float[] up    = getUpVector();

            float panScale = radius * 0.002f;
            pivotX -= (right[0] * dx - up[0] * dy) * panScale;
            pivotY -= (right[1] * dx - up[1] * dy) * panScale;
            pivotZ -= (right[2] * dx - up[2] * dy) * panScale;
        }
    }

    public void onScroll(float delta) {
        // Zoom: scroll in/out moves the eye closer/farther
        radius *= (1f - delta * 0.1f);
        radius = Math.max(0.1f, radius);
    }

    // ── Matrix computation ────────────────────────────────────────────────────

    /**
     * Computes the 4×4 view matrix (column-major, suitable for glUniformMatrix4fv).
     * This is a lookAt matrix from the orbiting eye position to the pivot.
     */
    public float[] getViewMatrix() {
        if (debugOverride) {
            return lookAt(debugEyeX, debugEyeY, debugEyeZ,
                    debugTargetX, debugTargetY, debugTargetZ,
                    0f, 1f, 0f);
        }
        float[] eye = getEyePosition();
        return lookAt(eye[0], eye[1], eye[2], pivotX, pivotY, pivotZ, 0f, 1f, 0f);
    }

    /**
     * Computes the 4×4 projection matrix.
     */
    public float[] getProjectionMatrix() {
        return perspective(fovY, aspect, near, far);
    }

    /** Sets the viewport aspect ratio (width / height). */
    public void setAspect(float aspect) {
        this.aspect = aspect;
    }

    /**
     * Reframes the camera to neatly display a model with the given bounding radius
     * and center.  Called automatically when a new model is loaded.
     */
    public void frameModel(float centerX, float centerY, float centerZ, float boundingRadius) {
        pivotX = centerX;
        pivotY = centerY + boundingRadius * 0.5f;  // look at upper half so grid appears at bottom
        pivotZ = centerZ;
        // Fit the model in the FOV with a little breathing room
        float distance = (boundingRadius / (float) Math.tan(fovY * 0.5f)) * 1.5f;
        radius    = Math.max(distance, 0.1f);
        azimuth   = 0f;
        elevation = 0.3f;
    }

    /** Resets to default orbit position around the current pivot. */
    public void reset() {
        azimuth   = 0f;
        elevation = 0.3f;
        radius    = 3.0f;
        pivotX = 0f;
        pivotY = 1.5f;
        pivotZ = 0f;
    }

    /** Enables or disables the debug camera override. */
    public void setDebugOverride(boolean enabled) {
        this.debugOverride = enabled;
    }

    /** Sets the forced debug eye/target used when debug override is enabled. */
    public void setDebugLookAt(float ex, float ey, float ez, float cx, float cy, float cz) {
        this.debugEyeX = ex;
        this.debugEyeY = ey;
        this.debugEyeZ = ez;
        this.debugTargetX = cx;
        this.debugTargetY = cy;
        this.debugTargetZ = cz;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    public float[] getEyePosition() {
        float sinAz = (float) Math.sin(azimuth);
        float cosAz = (float) Math.cos(azimuth);
        float sinEl = (float) Math.sin(elevation);
        float cosEl = (float) Math.cos(elevation);
        return new float[]{
            pivotX + radius * cosEl * sinAz,
            pivotY + radius * sinEl,
            pivotZ + radius * cosEl * cosAz
        };
    }

    private float[] getRightVector() {
        // Right vector = cross(forward, worldUp), then normalize
        float sinAz = (float) Math.sin(azimuth);
        float cosAz = (float) Math.cos(azimuth);
        // Forward in XZ plane: (sinAz, 0, cosAz), so right = (cosAz, 0, -sinAz)
        return new float[]{ cosAz, 0f, -sinAz };
    }

    private float[] getUpVector() {
        // Approximate "up" from elevation angle
        float sinEl = (float) Math.sin(elevation);
        float cosEl = (float) Math.cos(elevation);
        float sinAz = (float) Math.sin(azimuth);
        float cosAz = (float) Math.cos(azimuth);
        return new float[]{
            -sinEl * sinAz,
             cosEl,
            -sinEl * cosAz
        };
    }

    // ── Matrix math ───────────────────────────────────────────────────────────

    /** Standard lookAt matrix (column-major for OpenGL). */
    private static float[] lookAt(float ex, float ey, float ez,
                                   float cx, float cy, float cz,
                                   float ux, float uy, float uz) {
        // Forward vector
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float flen = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        fx /= flen; fy /= flen; fz /= flen;

        // Right vector = forward × up
        float rx = fy*uz - fz*uy;
        float ry = fz*ux - fx*uz;
        float rz = fx*uy - fy*ux;
        float rlen = (float) Math.sqrt(rx*rx + ry*ry + rz*rz);
        rx /= rlen; ry /= rlen; rz /= rlen;

        // True up = right × forward
        float tux = ry*fz - rz*fy;
        float tuy = rz*fx - rx*fz;
        float tuz = rx*fy - ry*fx;

        // Column-major layout (OpenGL convention):
        //   col 0 = right vector  (rx, ry, rz, 0)
        //   col 1 = true-up vector (tux, tuy, tuz, 0)
        //   col 2 = -forward vector (-fx, -fy, -fz, 0)
        //   col 3 = translation
        return new float[]{
             rx,   ry,   rz,  0f,
             tux,  tuy,  tuz, 0f,
            -fx,  -fy,  -fz,  0f,
            -(rx*ex + ry*ey + rz*ez),
            -(tux*ex + tuy*ey + tuz*ez),
             (fx*ex + fy*ey + fz*ez),
            1f
        };
    }

    /** Standard perspective projection matrix (column-major). */
    private static float[] perspective(float fovY, float aspect, float near, float far) {
        float f = 1.0f / (float) Math.tan(fovY * 0.5f);
        float rangeInv = 1.0f / (near - far);
        return new float[]{
            f / aspect, 0,  0,  0,
            0,          f,  0,  0,
            0,          0,  (far + near) * rangeInv, -1,
            0,          0,  2f * far * near * rangeInv, 0
        };
    }

    /** Multiplies two 4×4 column-major matrices: result = a * b. */
    public static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[row + k * 4] * b[k + col * 4];
                }
                r[row + col * 4] = sum;
            }
        }
        return r;
    }
}
