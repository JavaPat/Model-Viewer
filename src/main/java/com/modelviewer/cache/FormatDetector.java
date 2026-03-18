package com.modelviewer.cache;

/**
 * Detects the binary format of a raw OSRS/RSPS model file by inspecting its
 * terminal sentinel bytes.
 *
 * <pre>
 * Detection rule (strict — evaluated in order):
 *
 *   data[-2] == 0xFF  AND  data[-1] == 0xFE   → MODERN
 *        The exact two-byte sentinel used by RuneLite jagexcache (~2019+).
 *        Trailer size: 2 bytes. Header size: 21 bytes.
 *
 *   data[-2] == 0xFF  AND  data[-1] == 0xFD   → NEW
 *        Older post-legacy footer variant with a 23-byte trailer.
 *
 *   Otherwise                                  → LEGACY
 *        Covers RS2 317, original OSRS format, and anything without one of the
 *        explicit sentinels above.
 * </pre>
 */
public final class FormatDetector {

    /** Known model binary format variants. */
    public enum Format {
        /**
         * Modern OSRS format (~2019+).
         * Strict sentinel: last two bytes are exactly {@code [0xFF, 0xFE]}.
         */
        MODERN,
        /**
         * Newer pre-modern footer variant identified by the {@code FF FD}
         * sentinel. Some caches still contain these alongside legacy entries.
         */
        NEW,
        /**
         * RS2 317 / original OSRS format, and all formats without the strict
         * {@code FF FE}/{@code FF FD} sentinels. No sentinel bytes.
         */
        LEGACY
    }

    private FormatDetector() {}

    /**
     * Detects the format of the supplied raw model bytes.
     *
     * @param data raw decompressed model bytes; may be null
     * @return detected {@link Format}, never null (falls back to {@link Format#LEGACY})
     */
    public static Format detect(byte[] data) {
        if (data == null || data.length < 3) return Format.LEGACY;
        int last1 = data[data.length - 1] & 0xFF;
        int last2 = data[data.length - 2] & 0xFF;
        if (last2 == 0xFF && last1 == 0xFE) return Format.MODERN;
        if (last2 == 0xFF && last1 == 0xFD) return Format.NEW;
        return Format.LEGACY;
    }

    /**
     * Returns a human-readable label for log messages, including the version byte
     * where applicable.
     */
    public static String label(byte[] data) {
        return switch (detect(data)) {
            case MODERN -> "modern(v" + (data[data.length - 1] & 0xFF) + ")";
            case NEW -> "new(v" + (data[data.length - 1] & 0xFF) + ")";
            case LEGACY -> "legacy";
        };
    }
}
