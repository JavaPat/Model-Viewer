package com.modelviewer.model;

/**
 * A decoded OSRS model ready for GPU upload.
 *
 * After {@link ModelDecoder#decode} returns a ModelMesh, the data is in a
 * clean, engine-agnostic form.  {@code MeshUploader} converts it to OpenGL
 * VBOs / VAOs.
 *
 * Coordinate system:
 *   OSRS uses a left-handed coordinate system where +Y is up and values are
 *   in units of 1/128 of a game tile.  We keep the raw integers here and let
 *   the renderer scale them to tile units for display.
 */
public final class ModelMesh {

    /** Unique model ID (archive index within cache index 7). */
    public final int modelId;

    // ── Geometry ─────────────────────────────────────────────────────────────

    public final int vertexCount;
    public final int faceCount;

    /** Per-vertex positions in OSRS integer units. */
    public final int[] vertexX;
    public final int[] vertexY;
    public final int[] vertexZ;

    /** Per-face vertex indices forming triangles. */
    public final int[] faceVertexA;
    public final int[] faceVertexB;
    public final int[] faceVertexC;

    /** Per-face HSL colours (packed 16-bit). */
    public final short[] faceColors;

    /** Per-face render type (0=flat, 1=textured, etc.).  May be null. */
    public final int[] faceRenderTypes;

    /** Per-face alpha (0=opaque, 255=transparent).  May be null. */
    public final int[] faceAlphas;

    /** Per-face texture IDs (-1 = none).  May be null. */
    public final int[] faceTextureIds;

    /**
     * Per-vertex skin/group IDs (0–255), used for animation.
     * Null if this model has no skin data and cannot be vertex-animated.
     */
    public final int[] vertexSkins;

    /** Texture triangle vertex indices — defines UV reference frames. Null if model has no texture triangles. */
    public final int[] texFaceP;
    public final int[] texFaceQ;
    public final int[] texFaceR;

    // ── Bounding box (computed on decode) ────────────────────────────────────

    public final int minX, maxX;
    public final int minY, maxY;
    public final int minZ, maxZ;

    public ModelMesh(int modelId,
                     int[] vertexX, int[] vertexY, int[] vertexZ,
                     int[] faceVertexA, int[] faceVertexB, int[] faceVertexC,
                     short[] faceColors,
                     int[] faceRenderTypes,
                     int[] faceAlphas,
                     int[] faceTextureIds,
                     int[] vertexSkins,
                     int[] texFaceP,
                     int[] texFaceQ,
                     int[] texFaceR) {
        this.modelId = modelId;
        this.vertexCount = vertexX.length;
        this.faceCount   = faceVertexA.length;

        this.vertexX = vertexX;
        this.vertexY = vertexY;
        this.vertexZ = vertexZ;

        this.faceVertexA     = faceVertexA;
        this.faceVertexB     = faceVertexB;
        this.faceVertexC     = faceVertexC;
        this.faceColors      = faceColors;
        this.faceRenderTypes = faceRenderTypes;
        this.faceAlphas      = faceAlphas;
        this.faceTextureIds  = faceTextureIds;
        this.vertexSkins     = vertexSkins;
        this.texFaceP        = texFaceP;
        this.texFaceQ        = texFaceQ;
        this.texFaceR        = texFaceR;

        // Compute bounding box for camera auto-framing
        int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE;
        int mnY = Integer.MAX_VALUE, mxY = Integer.MIN_VALUE;
        int mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
        for (int i = 0; i < vertexCount; i++) {
            if (vertexX[i] < mnX) mnX = vertexX[i];
            if (vertexX[i] > mxX) mxX = vertexX[i];
            if (vertexY[i] < mnY) mnY = vertexY[i];
            if (vertexY[i] > mxY) mxY = vertexY[i];
            if (vertexZ[i] < mnZ) mnZ = vertexZ[i];
            if (vertexZ[i] > mxZ) mxZ = vertexZ[i];
        }
        this.minX = mnX; this.maxX = mxX;
        this.minY = mnY; this.maxY = mxY;
        this.minZ = mnZ; this.maxZ = mxZ;
    }

    /** Returns the longest axis of the bounding box (used to compute scale). */
    public float boundingRadius() {
        float dx = (maxX - minX) * 0.5f;
        float dy = (maxY - minY) * 0.5f;
        float dz = (maxZ - minZ) * 0.5f;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Centre of the bounding box (used to centre the model in the viewport). */
    public float centerX() { return (minX + maxX) * 0.5f; }
    public float centerY() { return (minY + maxY) * 0.5f; }
    public float centerZ() { return (minZ + maxZ) * 0.5f; }

    @Override
    public String toString() {
        return "ModelMesh(id=" + modelId + ", verts=" + vertexCount + ", faces=" + faceCount
                + (vertexSkins != null ? ", skins=yes" : "")
                + (texFaceP   != null ? ", texFaces=" + texFaceP.length : "") + ")";
    }
}
