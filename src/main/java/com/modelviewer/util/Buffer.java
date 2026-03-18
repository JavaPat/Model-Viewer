package com.modelviewer.util;

/**
 * Big-endian byte buffer reader used for parsing the OSRS cache binary format.
 *
 * OSRS (and its ancestor RS2) uses big-endian byte order for all multi-byte
 * integers.  This class also implements the "smart" variable-length integer
 * encodings that OSRS uses to pack small values efficiently:
 *
 *   - Signed smart   : 1 byte if value fits in [-64, 63], otherwise 2 bytes
 *   - Unsigned smart : 1 byte if value in [0, 127],        otherwise 2 bytes
 *   - Big smart      : 2 bytes if high bit is 0,            otherwise 4 bytes
 */
public final class Buffer {

    /** Raw data backing this buffer. */
    public final byte[] data;

    /** Current read position (advances automatically after each read). */
    public int offset;

    public Buffer(byte[] data) {
        this.data = data;
        this.offset = 0;
    }

    // ── Primitive reads ──────────────────────────────────────────────────────

    /** Reads one unsigned byte (0–255). */
    public int readUnsignedByte() {
        return data[offset++] & 0xFF;
    }

    /** Reads one signed byte (-128–127). */
    public int readSignedByte() {
        return data[offset++];
    }

    /** Reads two bytes as a big-endian unsigned short (0–65535). */
    public int readUnsignedShort() {
        int high = data[offset++] & 0xFF;
        int low  = data[offset++] & 0xFF;
        return (high << 8) | low;
    }

    /** Reads two bytes as a big-endian signed short. */
    public int readSignedShort() {
        int v = readUnsignedShort();
        return v >= 0x8000 ? v - 0x10000 : v;
    }

    /** Reads three bytes as a big-endian unsigned 24-bit integer. */
    public int readUint24() {
        int b0 = data[offset++] & 0xFF;
        int b1 = data[offset++] & 0xFF;
        int b2 = data[offset++] & 0xFF;
        return (b0 << 16) | (b1 << 8) | b2;
    }

    /** Reads four bytes as a big-endian signed int. */
    public int readInt() {
        int b0 = data[offset++] & 0xFF;
        int b1 = data[offset++] & 0xFF;
        int b2 = data[offset++] & 0xFF;
        int b3 = data[offset++] & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    // ── Smart integer encodings ──────────────────────────────────────────────

    /**
     * Reads a "signed smart" value.
     *
     * Encoding:
     *   If the first byte < 128 → read 1 byte, return (value - 64)
     *   Otherwise              → read 2 bytes as unsigned short, return (value - 49152)
     *
     * This gives a compact encoding for values in the common range [-64, 63]
     * while still supporting the full range [-16384, 16383] in 2 bytes.
     */
    public int readSignedSmart() {
        int peek = data[offset] & 0xFF;
        if (peek < 128) {
            return readUnsignedByte() - 64;
        } else {
            return readUnsignedShort() - 49152;
        }
    }

    /**
     * Reads an "unsigned smart" value.
     *
     * Encoding:
     *   If the first byte < 128 → read 1 byte, return value (0–127)
     *   Otherwise              → read 2 bytes, return (value - 32768) (0–32767)
     */
    public int readUnsignedSmart() {
        int peek = data[offset] & 0xFF;
        if (peek < 128) {
            return readUnsignedByte();
        } else {
            return readUnsignedShort() - 32768;
        }
    }

    /**
     * Reads the standard OSRS "smart" value used by model face/index streams.
     *
     * Encoding:
     *   If the first byte < 128 → read 1 byte, return value (0–127)
     *   Otherwise              → read 2 bytes, return (value - 32768) (0–32767)
     */
    public int readSmart() {
        return readUnsignedSmart();
    }

    /**
     * Reads a "big smart" value used in newer cache formats for large IDs.
     *
     * Encoding:
     *   If the high bit of the first short is 0 → read 2 bytes, return value (0–32767)
     *   Otherwise                               → read 4 bytes as signed int
     */
    public int readBigSmart() {
        int peek = data[offset] & 0xFF;
        if (peek < 128) {
            int v = readUnsignedShort();
            return v == 32767 ? -1 : v;   // sentinel for "null" reference
        } else {
            return readInt() & 0x7FFFFFFF;
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    /** Skips {@code n} bytes forward. */
    public void skip(int n) {
        offset += n;
    }

    /** Returns how many bytes remain from the current offset. */
    public int remaining() {
        return data.length - offset;
    }

    /** Reads {@code len} bytes into a new array. */
    public byte[] readBytes(int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, offset, out, 0, len);
        offset += len;
        return out;
    }

    /**
     * Reads a null-terminated string (bytes until byte value 0) and decodes
     * it using ISO-8859-1 (Latin-1), which is the encoding used by OSRS for
     * all in-game text strings stored in the cache.
     *
     * @return the decoded string, never null (may be empty)
     */
    public String readString() {
        int start = offset;
        while (offset < data.length && data[offset] != 0) {
            offset++;
        }
        String result = new String(data, start, offset - start, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (offset < data.length) {
            offset++; // consume the null terminator
        }
        return result;
    }
}
