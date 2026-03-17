package com.modelviewer.animation;

/**
 * Skeleton definition for OSRS animations.
 *
 * Stored in index 0, file 0 of each framemap archive.
 * Each transform node maps to a set of vertex-skin groups (as stored in
 * {@link com.modelviewer.model.ModelMesh#vertexSkins}).
 */
public final class FramemapDef {

    /**
     * Transform type codes:
     *   0 = origin  (sets the reference centroid for subsequent transforms)
     *   1 = translate
     *   2 = rotate  (Euler angles; 512 units = full rotation)
     *   3 = scale   (delta added to 128-base; (128+d)/128 = scale factor)
     *   5 = alpha   (not rendered — ignored)
     */
    public static final int TYPE_ORIGIN    = 0;
    public static final int TYPE_TRANSLATE = 1;
    public static final int TYPE_ROTATE    = 2;
    public static final int TYPE_SCALE     = 3;
    public static final int TYPE_ALPHA     = 5;

    /** Number of transform nodes. */
    public final int length;

    /** Transform type for each node (see TYPE_* constants). */
    public final int[] types;

    /**
     * Vertex-group IDs for each transform node.
     * A vertex belongs to the group given by {@code ModelMesh.vertexSkins[v]}.
     */
    public final int[][] groups;

    public FramemapDef(int length, int[] types, int[][] groups) {
        this.length = length;
        this.types  = types;
        this.groups = groups;
    }
}
