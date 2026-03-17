package com.modelviewer.model;

/**
 * A triangular face referencing three vertex indices.
 *
 * Colour is stored in OSRS HSL format (packed 16-bit short):
 *   bits 15-10 : hue        (0-63)
 *   bits  9- 7 : saturation (0-7)
 *   bits  6- 0 : lightness  (0-127)
 *
 * Alpha is stored as a byte where 0 = fully opaque, 255 = fully transparent
 * (inverted from the usual convention; only present on some models).
 *
 * renderType values (from the OSRS engine):
 *   0 = flat colour (one colour for the whole face)
 *   1 = texture mapped
 *   2 = flat colour with depth-test transparency
 *   3 = texture mapped with transparency
 */
public final class Face {

    /** Indices into the parent ModelMesh vertex array. */
    public final int a;
    public final int b;
    public final int c;

    /** HSL-packed 16-bit colour (converted to RGB when uploading to GPU). */
    public final short hslColor;

    /**
     * Render type flag:
     *   bit 0 = is textured
     *   bit 1 = is translucent
     */
    public final int renderType;

    /** 0 = opaque, 255 = fully transparent (OSRS convention). */
    public final int alpha;

    /** Index of the texture to use, or -1 if none. */
    public final int textureId;

    public Face(int a, int b, int c, short hslColor, int renderType, int alpha, int textureId) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.hslColor = hslColor;
        this.renderType = renderType;
        this.alpha = alpha;
        this.textureId = textureId;
    }

    /** Returns true if this face uses a texture (render type bit 0 set). */
    public boolean isTextured() {
        return (renderType & 1) != 0 && textureId >= 0;
    }
}
