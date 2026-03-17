package com.modelviewer.animation;

import com.modelviewer.model.ModelMesh;

/**
 * Applies an {@link AnimationFrame}'s transforms to a {@link ModelMesh} to
 * produce a single posed copy of that mesh.
 *
 * ── Transform types handled ───────────────────────────────────────────────
 *   TYPE_ORIGIN    (0) — ignored; used only as a reference anchor
 *   TYPE_TRANSLATE (1) — add (dx, dy, dz) to every vertex in the groups
 *   TYPE_ROTATE    (2) — Euler rotation around the group centroid;
 *                        512 units per full rotation (like OSRS client)
 *   TYPE_SCALE     (3) — scale vertices relative to group centroid;
 *                        (128 + delta) / 128 = scale factor
 *   TYPE_ALPHA     (5) — ignored (no per-vertex alpha in our pipeline)
 *
 * Vertices with {@code vertexSkins == null} are never moved.
 */
public final class AnimationApplier {

    /** Full rotation in OSRS animation angle units. */
    private static final double FULL_ROTATION = 512.0;

    private AnimationApplier() {}

    /**
     * Applies {@code frame} to {@code base} and returns a new posed ModelMesh.
     *
     * @param base    rest-pose mesh (not mutated)
     * @param frame   animation frame (may be null — returns base unchanged)
     * @param poseId  modelId to assign to the returned mesh (use a unique value
     *                to bypass the GPU LRU cache entry from the previous frame)
     * @return posed mesh, or {@code base} if frame is null or mesh has no skins
     */
    public static ModelMesh apply(ModelMesh base, AnimationFrame frame, int poseId) {
        if (frame == null || base.vertexSkins == null) return base;

        int n = base.vertexCount;
        int[] vx = base.vertexX.clone();
        int[] vy = base.vertexY.clone();
        int[] vz = base.vertexZ.clone();
        int[] skins = base.vertexSkins;

        FramemapDef framemap = frame.framemap;

        // Precompute centroid of each vertex group (from rest-pose positions).
        // Used as the pivot for rotate / scale transforms.
        int maxGroup = maxGroupId(framemap);
        float[] cx  = new float[maxGroup + 1];
        float[] cy  = new float[maxGroup + 1];
        float[] cz  = new float[maxGroup + 1];
        int[]   cnt = new int[maxGroup + 1];

        for (int i = 0; i < n; i++) {
            int g = skins[i];
            if (g >= 0 && g <= maxGroup) {
                cx[g]  += base.vertexX[i];
                cy[g]  += base.vertexY[i];
                cz[g]  += base.vertexZ[i];
                cnt[g] += 1;
            }
        }
        for (int g = 0; g <= maxGroup; g++) {
            if (cnt[g] > 0) {
                cx[g] /= cnt[g];
                cy[g] /= cnt[g];
                cz[g] /= cnt[g];
            }
        }

        // Apply each active transform in the frame
        for (int t = 0; t < frame.length; t++) {
            int tid = frame.transformIds[t];
            if (tid < 0 || tid >= framemap.length) continue;

            int   type   = framemap.types[tid];
            int[] groups = framemap.groups[tid];
            int   ddx    = frame.dx[t];
            int   ddy    = frame.dy[t];
            int   ddz    = frame.dz[t];

            switch (type) {

                case FramemapDef.TYPE_ORIGIN:
                    // Nothing to do — the centroid array already captures this
                    break;

                case FramemapDef.TYPE_TRANSLATE:
                    for (int group : groups) {
                        for (int i = 0; i < n; i++) {
                            if (skins[i] == group) {
                                vx[i] += ddx;
                                vy[i] += ddy;
                                vz[i] += ddz;
                            }
                        }
                    }
                    break;

                case FramemapDef.TYPE_ROTATE: {
                    // OSRS angle unit: 512 = full rotation (2π)
                    double ax = ddx * (2 * Math.PI / FULL_ROTATION);
                    double ay = ddy * (2 * Math.PI / FULL_ROTATION);
                    double az = ddz * (2 * Math.PI / FULL_ROTATION);

                    double sinX = Math.sin(ax), cosX = Math.cos(ax);
                    double sinY = Math.sin(ay), cosY = Math.cos(ay);
                    double sinZ = Math.sin(az), cosZ = Math.cos(az);

                    for (int group : groups) {
                        float ocx = group <= maxGroup ? cx[group] : 0;
                        float ocy = group <= maxGroup ? cy[group] : 0;
                        float ocz = group <= maxGroup ? cz[group] : 0;

                        for (int i = 0; i < n; i++) {
                            if (skins[i] != group) continue;

                            double rx = vx[i] - ocx;
                            double ry = vy[i] - ocy;
                            double rz = vz[i] - ocz;

                            // Y-axis (yaw) first
                            if (ddy != 0) {
                                double nx =  rx * cosY + rz * sinY;
                                double nz = -rx * sinY + rz * cosY;
                                rx = nx;
                                rz = nz;
                            }

                            // X-axis (pitch)
                            if (ddx != 0) {
                                double ny = ry * cosX - rz * sinX;
                                double nz = ry * sinX + rz * cosX;
                                ry = ny;
                                rz = nz;
                            }

                            // Z-axis (roll)
                            if (ddz != 0) {
                                double nx = rx * cosZ - ry * sinZ;
                                double ny = rx * sinZ + ry * cosZ;
                                rx = nx;
                                ry = ny;
                            }

                            vx[i] = (int) Math.round(rx + ocx);
                            vy[i] = (int) Math.round(ry + ocy);
                            vz[i] = (int) Math.round(rz + ocz);
                        }
                    }
                    break;
                }

                case FramemapDef.TYPE_SCALE: {
                    // (128 + delta) / 128 = scale factor (128 = no change)
                    double sx = (128 + ddx) / 128.0;
                    double sy = (128 + ddy) / 128.0;
                    double sz = (128 + ddz) / 128.0;

                    for (int group : groups) {
                        float ocx = group <= maxGroup ? cx[group] : 0;
                        float ocy = group <= maxGroup ? cy[group] : 0;
                        float ocz = group <= maxGroup ? cz[group] : 0;

                        for (int i = 0; i < n; i++) {
                            if (skins[i] != group) continue;
                            vx[i] = (int) Math.round(ocx + (vx[i] - ocx) * sx);
                            vy[i] = (int) Math.round(ocy + (vy[i] - ocy) * sy);
                            vz[i] = (int) Math.round(ocz + (vz[i] - ocz) * sz);
                        }
                    }
                    break;
                }

                // TYPE_ALPHA (5) and any unknown types: ignored
            }
        }

        return new ModelMesh(poseId, vx, vy, vz,
                base.faceVertexA, base.faceVertexB, base.faceVertexC,
                base.faceColors, base.faceRenderTypes, base.faceAlphas,
                base.faceTextureIds, base.vertexSkins,
                null, null, null);
    }

    private static int maxGroupId(FramemapDef framemap) {
        int max = 0;
        for (int[] groups : framemap.groups) {
            for (int g : groups) {
                if (g > max) max = g;
            }
        }
        return max;
    }
}
