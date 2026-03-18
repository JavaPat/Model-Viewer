package com.modelviewer.render;

/**
 * Blender-style orbit camera.
 *
 * State:
 *   - target: point the camera orbits around
 *   - distance: eye-to-target distance
 *   - yaw/pitch: orbit angles around the target
 *
 * Coordinate system: right-handed, Y-up.
 */
public final class Camera {

    private static final float DEFAULT_TARGET_X = 0f;
    private static final float DEFAULT_TARGET_Y = 1f;
    private static final float DEFAULT_TARGET_Z = 0f;
    private static final float DEFAULT_DISTANCE = 6.5f;
    private static final float DEFAULT_YAW = 0f;
    private static final float DEFAULT_PITCH = (float) Math.toRadians(14.0);

    private static final float ROTATE_SENSITIVITY = 0.005f;
    private static final float PAN_SPEED = 2.5f;
    private static final float ZOOM_SPEED = 0.35f;
    private static final float MIN_DISTANCE = 1.0f;
    private static final float MAX_DISTANCE = 200.0f;

    private float targetX = DEFAULT_TARGET_X;
    private float targetY = DEFAULT_TARGET_Y;
    private float targetZ = DEFAULT_TARGET_Z;

    private float distance = DEFAULT_DISTANCE;
    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;

    private float fovY   = (float) Math.toRadians(70.0);
    private float aspect = 1.0f;
    private float near   = 0.1f;
    private float far    = 500.0f;

    private boolean debugOverride = false;
    private float debugEyeX = 0f, debugEyeY = 2.5f, debugEyeZ = 6f;
    private float debugTargetX = DEFAULT_TARGET_X, debugTargetY = DEFAULT_TARGET_Y, debugTargetZ = DEFAULT_TARGET_Z;

    public Camera() {}

    public void update(float deltaSeconds, boolean panForward, boolean panBackward,
                       boolean panLeft, boolean panRight) {
        if (deltaSeconds <= 0f) {
            return;
        }

        float panAmount = PAN_SPEED * deltaSeconds;
        float[] forward = getForwardVector();
        float[] right = normalize(cross(forward, new float[]{0f, 1f, 0f}));

        float moveX = 0f, moveY = 0f, moveZ = 0f;
        if (panLeft) {
            moveX -= right[0] * panAmount;
            moveY -= right[1] * panAmount;
            moveZ -= right[2] * panAmount;
        }
        if (panRight) {
            moveX += right[0] * panAmount;
            moveY += right[1] * panAmount;
            moveZ += right[2] * panAmount;
        }
        if (panForward) {
            moveY += panAmount;
        }
        if (panBackward) {
            moveY -= panAmount;
        }

        targetX += moveX;
        targetY += moveY;
        targetZ += moveZ;
    }

    public void rotateLook(float dx, float dy) {
        yaw += dx * ROTATE_SENSITIVITY;
        pitch += dy * ROTATE_SENSITIVITY;
        pitch = clamp(pitch, (float) Math.toRadians(-89.0), (float) Math.toRadians(89.0));
    }

    public void onScroll(float delta) {
        distance -= delta * ZOOM_SPEED;
        distance = clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
    }

    public float[] getViewMatrix() {
        if (debugOverride) {
            return lookAt(debugEyeX, debugEyeY, debugEyeZ,
                    debugTargetX, debugTargetY, debugTargetZ,
                    0f, 1f, 0f);
        }

        float[] eye = getEyePosition();
        return lookAt(eye[0], eye[1], eye[2], targetX, targetY, targetZ, 0f, 1f, 0f);
    }

    public float[] getProjectionMatrix() {
        return perspective(fovY, aspect, near, far);
    }

    public void setAspect(float aspect) {
        this.aspect = aspect;
    }

    public void frameModel(float centerX, float centerY, float centerZ, float boundingRadius) {
        targetX = centerX;
        targetY = centerY + Math.max(1.0f, boundingRadius * 0.35f);
        targetZ = centerZ;
        distance = clamp(Math.max(DEFAULT_DISTANCE, boundingRadius * 2.5f), MIN_DISTANCE, MAX_DISTANCE);
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
    }

    public void reset() {
        targetX = DEFAULT_TARGET_X;
        targetY = DEFAULT_TARGET_Y;
        targetZ = DEFAULT_TARGET_Z;
        distance = DEFAULT_DISTANCE;
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
    }

    public void setDebugOverride(boolean enabled) {
        this.debugOverride = enabled;
    }

    public void setDebugLookAt(float ex, float ey, float ez, float cx, float cy, float cz) {
        this.debugEyeX = ex;
        this.debugEyeY = ey;
        this.debugEyeZ = ez;
        this.debugTargetX = cx;
        this.debugTargetY = cy;
        this.debugTargetZ = cz;
    }

    public float[] getEyePosition() {
        if (debugOverride) {
            return new float[]{debugEyeX, debugEyeY, debugEyeZ};
        }

        float cosPitch = (float) Math.cos(pitch);
        float eyeX = targetX + distance * cosPitch * (float) Math.sin(yaw);
        float eyeY = targetY + distance * (float) Math.sin(pitch);
        float eyeZ = targetZ + distance * cosPitch * (float) Math.cos(yaw);
        return new float[]{eyeX, eyeY, eyeZ};
    }

    public float[] getTargetPosition() {
        if (debugOverride) {
            return new float[]{debugTargetX, debugTargetY, debugTargetZ};
        }
        return new float[]{targetX, targetY, targetZ};
    }

    public float[] getForwardVector() {
        if (debugOverride) {
            return normalize(new float[]{
                    debugTargetX - debugEyeX,
                    debugTargetY - debugEyeY,
                    debugTargetZ - debugEyeZ
            });
        }
        float[] eye = getEyePosition();
        return normalize(new float[]{
                targetX - eye[0],
                targetY - eye[1],
                targetZ - eye[2]
        });
    }

    private static float[] lookAt(float ex, float ey, float ez,
                                  float cx, float cy, float cz,
                                  float ux, float uy, float uz) {
        float[] forward = normalize(new float[]{cx - ex, cy - ey, cz - ez});
        float[] up = normalize(new float[]{ux, uy, uz});
        float[] right = normalize(cross(forward, up));
        float[] trueUp = normalize(cross(right, forward));

        float fx = forward[0], fy = forward[1], fz = forward[2];
        float rx = right[0],   ry = right[1],   rz = right[2];
        float tux = trueUp[0], tuy = trueUp[1], tuz = trueUp[2];

        return new float[]{
                rx, ry, rz, 0f,
                tux, tuy, tuz, 0f,
                -fx, -fy, -fz, 0f,
                -(rx * ex + ry * ey + rz * ez),
                -(tux * ex + tuy * ey + tuz * ez),
                (fx * ex + fy * ey + fz * ez),
                1f
        };
    }

    private static float[] perspective(float fovY, float aspect, float near, float far) {
        float f = 1.0f / (float) Math.tan(fovY * 0.5f);
        float rangeInv = 1.0f / (near - far);
        return new float[]{
                f / aspect, 0, 0, 0,
                0, f, 0, 0,
                0, 0, (far + near) * rangeInv, -1,
                0, 0, 2f * far * near * rangeInv, 0
        };
    }

    public static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[row + k * 4] * b[k + col * 4];
                }
                r[row + col * 4] = sum;
            }
        }
        return r;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len == 0f) {
            return new float[]{0f, 0f, -1f};
        }
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
