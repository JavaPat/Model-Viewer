package com.modelviewer.render;

import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;

/**
 * Uploads raw ARGB pixel data (or sprite/PNG bytes from the cache) to an
 * OpenGL 2D texture and returns the GL texture handle.
 *
 * <p>The GL handle must be freed with {@code glDeleteTextures} when no longer
 * needed.  All methods must be called from the OpenGL thread.</p>
 */
public final class TextureLoader {

    private static final Logger log = LoggerFactory.getLogger(TextureLoader.class);

    /** Solid 8×8 magenta texture used as a fallback when a sprite cannot be parsed. */
    private static final int FALLBACK_SIZE = 8;

    private TextureLoader() {}

    /**
     * Uploads an ARGB pixel array as a GL_RGBA8 texture.
     *
     * @param argb   pixel data in ARGB order, row-major, top-to-bottom
     * @param width  image width in pixels
     * @param height image height in pixels
     * @return GL texture handle (>0), or 0 on failure
     */
    public static int uploadArgb(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0) return createFallback();
        try {
            IntBuffer buf = BufferUtils.createIntBuffer(argb.length);
            buf.put(argb).flip();

            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            glBindTexture(GL_TEXTURE_2D, 0);
            return tex;
        } catch (Exception e) {
            log.warn("uploadArgb failed: {}", e.getMessage());
            return createFallback();
        }
    }

    /**
     * Attempts to decode raw cache bytes as a texture:
     * <ol>
     *   <li>If the data starts with the PNG magic bytes, uses {@link ImageIO}.</li>
     *   <li>Otherwise, attempts to parse as an OSRS sprite (paletted or raw ARGB).</li>
     *   <li>Falls back to a solid magenta texture on any parse failure.</li>
     * </ol>
     *
     * @param data  raw bytes from the cache (index 8 sprite or index 9 texture)
     * @return GL texture handle, always > 0
     */
    public static int uploadCacheBytes(byte[] data) {
        if (data == null || data.length < 4) return createFallback();

        // Try PNG
        if (isPng(data)) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null) {
                    return uploadBufferedImage(img);
                }
            } catch (Exception e) {
                log.debug("PNG decode failed, trying sprite parser: {}", e.getMessage());
            }
        }

        // Try OSRS sprite (paletted)
        int[] argb = parseOsrsSprite(data);
        if (argb != null && argb.length > 0) {
            // Determine dimensions: sprite parser embeds width in first element via side-channel
            // We use the decoded array; for paletted sprites the size is encoded in the data.
            int side = (int) Math.sqrt(argb.length);
            return uploadArgb(argb, side, argb.length / Math.max(1, side));
        }

        log.debug("Could not parse texture bytes ({} bytes), using fallback", data.length);
        return createFallback();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G';
    }

    private static int uploadBufferedImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        return uploadArgb(argb, w, h);
    }

    /**
     * Minimal OSRS sprite parser.
     *
     * OSRS sprites stored in index 8 use a paletted format where:
     * <ul>
     *   <li>Data end-4 = maxWidth (int), end-8 = maxHeight (int).</li>
     *   <li>Data end-10 = frameCount (short).</li>
     *   <li>Before pixel data: palette (3 bytes per colour + 1 transparent entry).</li>
     *   <li>Pixel data: byte indices into palette.</li>
     * </ul>
     *
     * @return ARGB pixel array for frame 0, or null on parse failure
     */
    private static int[] parseOsrsSprite(byte[] data) {
        if (data.length < 14) return null;
        try {
            int end = data.length;

            // Read maxWidth, maxHeight from the last 8 bytes
            int maxWidth  = readInt(data, end - 4);
            int maxHeight = readInt(data, end - 8);

            if (maxWidth <= 0 || maxHeight <= 0 || maxWidth > 4096 || maxHeight > 4096) {
                return null;
            }

            // frameCount is 2 bytes before the dimension ints
            int frameCount = readShort(data, end - 10);
            if (frameCount <= 0 || frameCount > 100) return null;

            // Offset area: for each frame: 2+2+2+2 = 8 bytes (offsetX,Y, width, height)
            // Starting at end-10-frameCount*8
            int offBase = end - 10 - frameCount * 8;
            if (offBase < 0) return null;

            int offX    = readShort(data, offBase);
            int offY    = readShort(data, offBase + 2);
            int fWidth  = readShort(data, offBase + 4);
            int fHeight = readShort(data, offBase + 6);

            if (fWidth <= 0 || fHeight <= 0 || fWidth > 4096 || fHeight > 4096) return null;

            // Palette size = 1 byte at offBase-3, then palette colours (3 bytes each), first is transparent
            int paletteBase = offBase - 3;
            if (paletteBase < 0) return null;
            int paletteSize = (data[paletteBase] & 0xFF) + 1;
            int[] palette   = new int[paletteSize];
            int palOff = paletteBase - (paletteSize - 1) * 3;
            if (palOff < 0) return null;
            palette[0] = 0x00000000; // transparent
            for (int i = 1; i < paletteSize; i++) {
                int r = data[palOff + (i - 1) * 3    ] & 0xFF;
                int g = data[palOff + (i - 1) * 3 + 1] & 0xFF;
                int b = data[palOff + (i - 1) * 3 + 2] & 0xFF;
                palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }

            // Pixel data starts at 0
            int pixelCount = fWidth * fHeight;
            if (pixelCount > data.length) return null;
            int[] argb = new int[pixelCount];
            for (int i = 0; i < pixelCount && i < data.length; i++) {
                int idx = data[i] & 0xFF;
                argb[i] = (idx < palette.length) ? palette[idx] : 0xFF000000;
            }
            return argb;
        } catch (Exception e) {
            return null;
        }
    }

    private static int createFallback() {
        // Solid magenta 8×8
        int[] pixels = new int[FALLBACK_SIZE * FALLBACK_SIZE];
        java.util.Arrays.fill(pixels, 0xFFFF00FF);
        return uploadArgb(pixels, FALLBACK_SIZE, FALLBACK_SIZE);
    }

    private static int readInt(byte[] data, int off) {
        return ((data[off] & 0xFF) << 24) | ((data[off+1] & 0xFF) << 16)
             | ((data[off+2] & 0xFF) << 8) | (data[off+3] & 0xFF);
    }

    private static int readShort(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off+1] & 0xFF);
    }
}
