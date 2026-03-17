package com.modelviewer.animation;

/**
 * One frame within an OSRS animation.
 *
 * Stored in index 0, archive = framemapId, file = frameIndex.
 * Only the transforms that are active in this particular frame are stored;
 * transforms not listed have zero delta.
 */
public final class AnimationFrame {

    /** The skeleton this frame belongs to. */
    public final FramemapDef framemap;

    /** Number of active transforms in this frame. */
    public final int length;

    /** Indices into {@code framemap.types} / {@code framemap.groups}. */
    public final int[] transformIds;

    /** Delta values for X, Y, Z per active transform. */
    public final int[] dx;
    public final int[] dy;
    public final int[] dz;

    public AnimationFrame(FramemapDef framemap, int length,
                          int[] transformIds, int[] dx, int[] dy, int[] dz) {
        this.framemap     = framemap;
        this.length       = length;
        this.transformIds = transformIds;
        this.dx           = dx;
        this.dy           = dy;
        this.dz           = dz;
    }
}
