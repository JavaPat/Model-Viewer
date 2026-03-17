package com.modelviewer.animation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes OSRS framemap and frame binary data from cache index 0.
 *
 * ── Framemap binary format (file 0 of a framemap archive) ──────────────────
 *   ushort  length            — number of transform nodes
 *   byte[length]  types       — transform type per node
 *   for each node:
 *       ushort  groupCount    — number of vertex groups for this node
 *       ushort[groupCount]    — vertex group IDs
 *
 * ── Frame binary format (file frameIndex of a framemap archive) ────────────
 *   ushort  framemapId        — archive ID of the owning framemap (redundant)
 *   ushort  count             — number of active transforms
 *   ushort[count]  transformIds — indices into framemap nodes
 *   short[count]   dx
 *   short[count]   dy
 *   short[count]   dz
 */
public final class FramemapDecoder {

    private static final Logger log = LoggerFactory.getLogger(FramemapDecoder.class);

    private FramemapDecoder() {}

    /**
     * Decodes a {@link FramemapDef} from the raw bytes of file 0 of a framemap archive.
     *
     * @param data raw (decompressed) bytes
     * @return decoded FramemapDef, or {@code null} if data is null / too short / corrupt
     */
    public static FramemapDef decodeFramemap(byte[] data) {
        if (data == null || data.length < 2) return null;
        try {
            int pos = 0;
            int length = readUShort(data, pos); pos += 2;
            if (length <= 0 || length > 65535) return null;

            int[] types = new int[length];
            for (int i = 0; i < length; i++) {
                types[i] = data[pos++] & 0xFF;
            }

            int[][] groups = new int[length][];
            for (int i = 0; i < length; i++) {
                if (pos + 2 > data.length) return null;
                int count = readUShort(data, pos); pos += 2;
                groups[i] = new int[count];
                for (int j = 0; j < count; j++) {
                    if (pos + 2 > data.length) return null;
                    groups[i][j] = readUShort(data, pos); pos += 2;
                }
            }

            return new FramemapDef(length, types, groups);
        } catch (Exception e) {
            log.debug("FramemapDecoder: framemap parse failed — {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decodes an {@link AnimationFrame} from the raw bytes of one frame file.
     *
     * @param framemap the owning skeleton definition
     * @param data     raw (decompressed) bytes
     * @return decoded AnimationFrame, or {@code null} if data is null / too short / corrupt
     */
    public static AnimationFrame decodeFrame(FramemapDef framemap, byte[] data) {
        if (framemap == null || data == null || data.length < 4) return null;
        try {
            int pos = 0;
            /* int framemapId = */ readUShort(data, pos); pos += 2;  // skip, redundant
            int count = readUShort(data, pos); pos += 2;
            if (count < 0 || count > 65535) return null;

            int[] transformIds = new int[count];
            int[] dx = new int[count];
            int[] dy = new int[count];
            int[] dz = new int[count];

            if (pos + count * 2 > data.length) return null;
            for (int i = 0; i < count; i++) {
                transformIds[i] = readUShort(data, pos); pos += 2;
            }

            if (pos + count * 6 > data.length) return null;
            for (int i = 0; i < count; i++) {
                dx[i] = readShort(data, pos); pos += 2;
            }
            for (int i = 0; i < count; i++) {
                dy[i] = readShort(data, pos); pos += 2;
            }
            for (int i = 0; i < count; i++) {
                dz[i] = readShort(data, pos); pos += 2;
            }

            return new AnimationFrame(framemap, count, transformIds, dx, dy, dz);
        } catch (Exception e) {
            log.debug("FramemapDecoder: frame parse failed — {}", e.getMessage());
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int readUShort(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static int readShort(byte[] b, int pos) {
        return (short) (((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF));
    }
}
