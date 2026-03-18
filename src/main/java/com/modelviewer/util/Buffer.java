package com.modelviewer.util;

public final class Buffer {

    public final byte[] data;
    public int offset;

    public Buffer(byte[] data) {
        this.data = data;
        this.offset = 0;
    }

    // ── Primitive reads ─────────────────────────────────────────

    public int readUnsignedByte() {
        return data[offset++] & 0xFF;
    }

    public int readSignedByte() {
        return data[offset++];
    }

    public int readUnsignedShort() {
        int high = data[offset++] & 0xFF;
        int low  = data[offset++] & 0xFF;
        return (high << 8) | low;
    }

    public int readSignedShort() {
        int v = readUnsignedShort();
        return v >= 0x8000 ? v - 0x10000 : v;
    }

    public int readInt() {
        int b0 = data[offset++] & 0xFF;
        int b1 = data[offset++] & 0xFF;
        int b2 = data[offset++] & 0xFF;
        int b3 = data[offset++] & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    public int readUint24() {
        int b1 = data[offset++] & 0xFF;
        int b2 = data[offset++] & 0xFF;
        int b3 = data[offset++] & 0xFF;
        return (b1 << 16) | (b2 << 8) | b3;
    }

    // ── SMARTS (FIXED + SAFE) ───────────────────────────────────

    // EXACT RuneLite-style unsigned smart
    public int readUnsignedSmart() {
        int peek = data[offset] & 0xFF;
        if (peek < 128) {
            return readUnsignedByte();
        } else {
            return readUnsignedShort() - 32768;
        }
    }

    // EXACT RuneLite-style signed smart
    public int readSignedSmart() {
        int peek = data[offset] & 0xFF;
        if (peek < 128) {
            return readUnsignedByte() - 64;
        } else {
            return readUnsignedShort() - 49152;
        }
    }

    public int readBigSmart() {
        int peek = data[offset] & 0xFF;

        if (peek < 128) {
            int value = readUnsignedShort();
            return value == 32767 ? -1 : value;
        } else {
            return readInt() & 0x7FFFFFFF;
        }
    }

    // 🔥 CRITICAL: used in some OSRS model formats
    public int readShortSmart() {
        int peek = data[offset] & 0xFF;
        if (peek < 128) {
            return readUnsignedByte() - 64;
        } else {
            return readUnsignedShort() - 49152;
        }
    }

    // ❌ REMOVE ambiguity — DO NOT USE
    public int readSmart() {
        throw new UnsupportedOperationException("Use readUnsignedSmart or readSignedSmart explicitly");
    }

    // ── Utility ────────────────────────────────────────────────

    public void skip(int n) {
        offset += n;
    }

    public int remaining() {
        return data.length - offset;
    }

    public String readString() {
        int start = offset;

        while (offset < data.length && data[offset] != 0) {
            offset++;
        }

        String result = new String(
                data,
                start,
                offset - start,
                java.nio.charset.StandardCharsets.ISO_8859_1
        );

        if (offset < data.length) {
            offset++; // skip null terminator
        }

        return result;
    }

}