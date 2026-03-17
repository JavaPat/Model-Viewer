package com.modelviewer.model;

import com.modelviewer.cache.FormatDetector;
import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline facade for decoding raw OSRS model bytes into a {@link ModelMesh}.
 *
 * <p>Delegates to a fixed list of {@link IModelDecoder} implementations tried in
 * registration order.  The first decoder whose {@link IModelDecoder#supports}
 * returns {@code true} is invoked; if it returns a non-null result that result is
 * returned immediately.  If it returns {@code null} (parse failure) the next
 * matching decoder is tried.  If all decoders fail, {@code null} is returned.</p>
 *
 * <p>Registered decoders (in order):
 * <ol>
 *   <li>{@link ModernModelDecoder} — handles MODERN and NEW_V12 sentinel formats</li>
 *   <li>{@link LegacyModelDecoder} — fallback, handles the legacy RS2/OSRS format</li>
 * </ol>
 * </p>
 *
 * <p>The static helper methods {@link #readVertices} and {@link #readFaceIndices}
 * are package-private so that the individual decoder implementations can share
 * them.  {@link #hslToRgb} is public because {@link com.modelviewer.render.MeshUploader}
 * calls it.</p>
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * OSRS Model Format (reference)
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * Three major format variants exist, distinguished by a sentinel at the end:
 *
 *   OLD FORMAT:    data[-2] != 0xFF  (no sentinel)
 *   NEW FORMAT:    data[-1] == 0xFF AND data[-2] == 0xFF
 *                  data[-3] == version byte (e.g. 12); trailer = 3 bytes
 *   MODERN FORMAT: data[-2] == 0xFF AND data[-1] != 0xFF
 *                  data[-1] == version byte (e.g. 0xFE=254, 0xFD=253); trailer = 2 bytes
 *                  Used in OSRS caches from ~2019+ (RuneLite jagexcache)
 *
 * ── Face index delta-encoding ─────────────────────────────────────────────────
 *
 * The faceCompressTypes byte for each face has 3 meaningful low bits:
 *   1 = type 1 — all 3 vertex indices new; read 3 unsigned smarts
 *   2 = type 2 — share edge BC from previous face; read 1 unsigned smart (new C)
 *   3 = type 3 — share edge AC from previous face; read 1 unsigned smart (new C)
 *   4 = type 4 — share edge AB (swapped) from previous; read 1 unsigned smart
 */
public final class ModelDecoder {

    private static final Logger log = LoggerFactory.getLogger(ModelDecoder.class);

    /** Ordered list of decoders tried for each model. */
    private static final IModelDecoder[] REGISTERED_DECODERS = {
        new ModernModelDecoder(),
        new LegacyModelDecoder()
    };

    private ModelDecoder() {}

    /**
     * Decodes raw model bytes into a {@link ModelMesh} using the registered
     * decoder pipeline.
     *
     * @param modelId the archive ID (for debugging / caching)
     * @param data    raw decompressed bytes from the cache
     * @return decoded mesh, or null on failure
     */
    public static ModelMesh decode(int modelId, byte[] data) {
        if (data == null || data.length < 20) {
            return null;
        }

        // Dump the last 32 bytes so we can see the sentinel + header bytes
        int dumpStart = Math.max(0, data.length - 32);
        StringBuilder hex = new StringBuilder();
        for (int i = dumpStart; i < data.length; i++) {
            if (i > dumpStart) hex.append(' ');
            hex.append(String.format("%02X", data[i] & 0xFF));
        }
        String fmtLabel = FormatDetector.label(data);
        log.info("Model {} len={} format={} tail=[{}]", modelId, data.length, fmtLabel, hex);

        byte b1 = data[data.length - 2];
        byte b2 = data[data.length - 1];
        boolean isModern = b1 == (byte) 0xFF && b2 == (byte) 0xFE;
        boolean isNew = b1 == (byte) 0xFF && b2 == (byte) 0xFD;

        ModelMesh mesh;
        if (isNew) {
            System.out.println("Model " + modelId + " using format: FF FD");
            System.out.println("Trying FF FD decode for " + modelId);
            mesh = new NewModelDecoder().decode(modelId, data);
        } else if (isModern) {
            System.out.println("Model " + modelId + " using format: FF FE");
            System.out.println("Trying modern decode for " + modelId);
            mesh = new ModernModelDecoder().decode(modelId, data);
        } else {
            System.out.println("Model " + modelId + " using format: legacy");
            System.out.println("Trying legacy decode for " + modelId);
            mesh = new LegacyModelDecoder().decode(modelId, data);
        }

        if (mesh != null) {
            System.out.println("Decoded model " + modelId);
            return mesh;
        }

        log.debug("Skipped model {} (unsupported format)", modelId);
        return null;
    }

    // ── Shared helpers (package-private) ─────────────────────────────────────

    /**
     * Reads delta-encoded vertex positions from three separate streams
     * (X, Y, Z deltas are stored in separate regions).
     *
     * Each vertex has a 1-byte flag in {@code vertexFlagsOff} that indicates
     * which axes have a non-zero delta (bits 0/1/2 → X/Y/Z respectively).
     * Deltas are "signed smart" values (1 or 2 bytes each).
     */
    static void readVertices(byte[] data,
                             int flagsOff, int xOff, int yOff, int zOff,
                             int count, int[] vx, int[] vy, int[] vz) {
        Buffer flagBuf = new Buffer(data); flagBuf.offset = flagsOff;
        Buffer xBuf    = new Buffer(data); xBuf.offset    = xOff;
        Buffer yBuf    = new Buffer(data); yBuf.offset    = yOff;
        Buffer zBuf    = new Buffer(data); zBuf.offset    = zOff;

        int bx = 0, by = 0, bz = 0;
        for (int i = 0; i < count; i++) {
            int vflags = flagBuf.readUnsignedByte();
            int dx = ((vflags & 1) != 0) ? xBuf.readSignedSmart() : 0;
            int dy = ((vflags & 2) != 0) ? yBuf.readSignedSmart() : 0;
            int dz = ((vflags & 4) != 0) ? zBuf.readSignedSmart() : 0;
            bx += dx; by += dy; bz += dz;
            vx[i] = bx; vy[i] = by; vz[i] = bz;
        }
    }

    /**
     * Reads the triangle index data, which is split between two sections:
     *   - {@code compressOff}: one byte per face, low 3 bits = strip type
     *   - {@code indexOff}: delta-encoded vertex indices using UNSIGNED smarts
     *
     * Strip types (low 3 bits of the compress byte) — RS2/OSRS reference encoding:
     *   1 → new triangle  : a=usmart+last, b=usmart+last, c=usmart+last  (3 new verts)
     *   2 → strip forward : a=old_b, b=old_c, c=usmart+last              (rotate A→B→C→new)
     *   3 → strip back    : a=old_a, b=old_a, c=usmart+last              (A stays, B=old_A, new C)
     *   4 → strip swap    : a=old_b, b=old_a, c=usmart+last              (swap A↔B, new C)
     *
     * "last" always tracks the most recently emitted vertex index and acts as the
     * base for the next unsigned-smart delta read.  Deltas are always ≥ 0.
     */
    static void readFaceIndices(byte[] data,
                                int compressOff, int indexOff,
                                int faceCount,
                                int[] fa, int[] fb, int[] fc) {
        Buffer compBuf = new Buffer(data); compBuf.offset = compressOff;
        Buffer idxBuf  = new Buffer(data); idxBuf.offset  = indexOff;

        int a = 0, b = 0, c = 0, last = 0;
        boolean firstLogged = false;
        for (int i = 0; i < faceCount; i++) {
            int type = compBuf.readUnsignedByte() & 7;  // lower 3 bits are the strip type
            switch (type) {
                case 1 -> {
                    // All three vertices are new.
                    a = idxBuf.readUnsignedSmart() + last; last = a;
                    b = idxBuf.readUnsignedSmart() + last; last = b;
                    c = idxBuf.readUnsignedSmart() + last; last = c;
                }
                case 2 -> {
                    a = b;
                    b = c;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                case 3 -> {
                    b = a;
                    c = idxBuf.readUnsignedSmart() + last; last = c;
                }
                case 4 -> {
                    // Swap A and B (reverse winding), then add new C.
                    int tmp = a; a = b; b = tmp;
                    c = idxBuf.readUnsignedSmart() + last; last = c;
                }
                default -> throw new IllegalStateException("Invalid face type: " + type);
            }
            fa[i] = a; fb[i] = b; fc[i] = c;
            if (!firstLogged && type != 0) {
                log.debug("  face[0] type={} → ({},{},{})", type, a, b, c);
                firstLogged = true;
            }
        }
    }

    // ── HSL colour conversion ─────────────────────────────────────────────────

    /**
     * Converts a packed OSRS HSL colour (16-bit) to a 24-bit RGB value.
     *
     * HSL bit layout:
     *   bits 15-10 : hue        (0-63  → 0°-360° in 64 steps)
     *   bits  9- 7 : saturation (0-7   → 0%-100% in 8 steps)
     *   bits  6- 0 : lightness  (0-127 → 0%-100% in 128 steps)
     *
     * @return 0x00RRGGBB packed int
     */
    public static int hslToRgb(int hsl) {
        int h = (hsl >> 10) & 0x3F;
        int s = (hsl >>  7) & 0x07;
        int l =  hsl        & 0x7F;

        float lf = l / 127.0f;

        if (s == 0) {
            int gray = Math.min(255, (int)(lf * 256f));
            return (gray << 16) | (gray << 8) | gray;
        }

        float sf = s / 7.0f;
        float q  = (lf < 0.5f) ? lf * (1f + sf) : lf + sf - lf * sf;
        float p  = 2f * lf - q;
        float hf = h / 63.0f;

        float r = hueToRgbChannel(p, q, hf + 1f/3f);
        float g = hueToRgbChannel(p, q, hf);
        float b = hueToRgbChannel(p, q, hf - 1f/3f);

        int ri = Math.min(255, (int)(r * 256f));
        int gi = Math.min(255, (int)(g * 256f));
        int bi = Math.min(255, (int)(b * 256f));
        return (ri << 16) | (gi << 8) | bi;
    }

    static float hueToRgbChannel(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f/6f) return p + (q - p) * 6f * t;
        if (t < 1f/2f) return q;
        if (t < 2f/3f) return p + (q - p) * (2f/3f - t) * 6f;
        return p;
    }
}
