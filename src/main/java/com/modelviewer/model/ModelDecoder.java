package com.modelviewer.model;

import com.modelviewer.cache.FormatDetector;
import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelDecoder {

    private static final Logger log = LoggerFactory.getLogger(ModelDecoder.class);

    private ModelDecoder() {}

    public static ModelMesh decode(int modelId, byte[] data) {
        if (data == null || data.length < 20) {
            return null;
        }

        // Debug tail dump (very useful for format issues)
        int dumpStart = Math.max(0, data.length - 32);
        StringBuilder hex = new StringBuilder();
        for (int i = dumpStart; i < data.length; i++) {
            if (i > dumpStart) hex.append(' ');
            hex.append(String.format("%02X", data[i] & 0xFF));
        }

        String fmtLabel = FormatDetector.label(data);
        log.info("Model {} len={} format={} tail=[{}]", modelId, data.length, fmtLabel, hex);

        int b1 = data[data.length - 2] & 0xFF;
        int b2 = data[data.length - 1] & 0xFF;

        boolean isModern = (b1 == 0xFF && (b2 == 0xFE || b2 == 0xFD));
        boolean isNew    = (b1 == 0xFF && b2 == 0xFF);

        ModelMesh mesh = null;

        // ✅ Try modern first (OSRS main format)
        if (isModern) {
            mesh = new ModernModelDecoder().decode(modelId, data);
        }

        // ✅ Then try "new" format
        if (mesh == null && isNew) {
            mesh = new NewModelDecoder().decode(modelId, data);
        }

        // ✅ Fallback (old/legacy)
        if (mesh == null) {
            mesh = new LegacyModelDecoder().decode(modelId, data);
        }

        if (mesh != null) {
            return mesh;
        }

        log.warn("Failed to decode model {}", modelId);
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────

    static void readVertices(byte[] data,
                             int flagsOff, int xOff, int yOff, int zOff,
                             int count, int[] vx, int[] vy, int[] vz) {

        Buffer flagBuf = new Buffer(data); flagBuf.offset = flagsOff;
        Buffer xBuf    = new Buffer(data); xBuf.offset    = xOff;
        Buffer yBuf    = new Buffer(data); yBuf.offset    = yOff;
        Buffer zBuf    = new Buffer(data); zBuf.offset    = zOff;

        int bx = 0, by = 0, bz = 0;

        for (int i = 0; i < count; i++) {
            int flags = flagBuf.readUnsignedByte();

            int dx = ((flags & 1) != 0) ? xBuf.readSignedSmart() : 0;
            int dy = ((flags & 2) != 0) ? yBuf.readSignedSmart() : 0;
            int dz = ((flags & 4) != 0) ? zBuf.readSignedSmart() : 0;

            bx += dx;
            by += dy;
            bz += dz;

            vx[i] = bx;
            vy[i] = by;
            vz[i] = bz;
        }
    }

    static void readFaceIndices(byte[] data,
                                int compressOff, int indexOff,
                                int faceCount,
                                int[] fa, int[] fb, int[] fc) {

        Buffer compBuf = new Buffer(data); compBuf.offset = compressOff;
        Buffer idxBuf  = new Buffer(data); idxBuf.offset  = indexOff;

        int a = 0, b = 0, c = 0, last = 0;

        for (int i = 0; i < faceCount; i++) {
            int type = compBuf.readUnsignedByte() & 7; // OSRS uses lower 3 bits

            switch (type) {
                case 1 -> {
                    a = idxBuf.readUnsignedSmart() + last;
                    b = idxBuf.readUnsignedSmart() + a;
                    c = idxBuf.readUnsignedSmart() + b;
                    last = c;
                }
                case 2 -> {
                    b = c;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                case 3 -> {
                    a = c;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                case 4 -> {
                    int tmp = a; a = b; b = tmp;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                default -> throw new IllegalStateException("Invalid face type: " + type);
            }

            fa[i] = a;
            fb[i] = b;
            fc[i] = c;
        }
    }

    static void validateFaceIndices(int vertexCount, int[] fa, int[] fb, int[] fc) {
        for (int i = 0; i < fa.length; i++) {
            int a = fa[i], b = fb[i], c = fc[i];
            if (a < 0 || a >= vertexCount ||
                    b < 0 || b >= vertexCount ||
                    c < 0 || c >= vertexCount) {

                throw new IllegalStateException(
                        "Invalid indices at face " + i +
                                ": (" + a + "," + b + "," + c + ")" +
                                " vertexCount=" + vertexCount);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HSL → RGB
    // ─────────────────────────────────────────────────────────────

    public static int hslToRgb(int hsl) {
        int h = (hsl >> 10) & 0x3F;
        int s = (hsl >> 7) & 0x07;
        int l = hsl & 0x7F;

        float lf = l / 127.0f;

        if (s == 0) {
            int gray = Math.min(255, (int)(lf * 256f));
            return (gray << 16) | (gray << 8) | gray;
        }

        float sf = s / 7.0f;
        float q = (lf < 0.5f) ? lf * (1f + sf) : lf + sf - lf * sf;
        float p = 2f * lf - q;
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