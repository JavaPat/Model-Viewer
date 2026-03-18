package com.modelviewer.cache;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public final class CacheArchive {

    private static final int COMPRESSION_NONE  = 0;
    private static final int COMPRESSION_BZIP2 = 1;
    private static final int COMPRESSION_GZIP  = 2;
    private static final int COMPRESSION_LZMA  = 3;

    private static final byte[] BZIP2_HEADER = {0x42, 0x5A, 0x68, 0x31};

    private CacheArchive() {}

    public static byte[] decompress(byte[] raw) throws IOException {
        if (raw == null || raw.length < 5) {
            throw new IOException("Archive data too short: " + (raw == null ? 0 : raw.length));
        }

        int compressionType  = raw[0] & 0xFF;
        int compressedLength = readInt(raw, 1);

        // 🔥 CRITICAL FIX: clamp compressed length
        int maxAvailable = raw.length - 5;
        if (compressedLength > maxAvailable) {
            compressedLength = maxAvailable;
        }

        if (compressionType == COMPRESSION_NONE) {
            byte[] out = new byte[compressedLength];
            System.arraycopy(raw, 5, out, 0, compressedLength);
            return out;
        }

        if (raw.length < 9) {
            throw new IOException("Archive header too short for compressed data");
        }

        int decompressedLength = readInt(raw, 5);
        int dataOffset = 9;

        // 🔥 CRITICAL FIX: clamp again for safety
        int actualCompressedLength = Math.min(compressedLength, raw.length - dataOffset);

        return switch (compressionType) {
            case COMPRESSION_BZIP2 ->
                    decompressBZip2(raw, dataOffset, actualCompressedLength, decompressedLength);

            case COMPRESSION_GZIP  ->
                    decompressGZip(raw, dataOffset, actualCompressedLength, decompressedLength);

            case COMPRESSION_LZMA  ->
                    decompressLZMA(raw, dataOffset, actualCompressedLength, decompressedLength);

            default -> throw new IOException("Unknown compression type: " + compressionType);
        };
    }

    public static CacheFile[] extractFiles(int archiveId, byte[] decompressed, int[] fileIds) {
        if (fileIds == null || fileIds.length == 0) {
            return new CacheFile[]{new CacheFile(archiveId, decompressed)};
        }
        if (fileIds.length == 1) {
            return new CacheFile[]{new CacheFile(fileIds[0], decompressed)};
        }

        int numChunks = decompressed[decompressed.length - 1] & 0xFF;
        int numFiles  = fileIds.length;

        int tableOffset = decompressed.length - 1 - (numFiles * numChunks * 4);
        if (tableOffset < 0) {
            return new CacheFile[]{new CacheFile(archiveId, decompressed)};
        }

        int[] fileSizes = new int[numFiles];
        int pos = tableOffset;

        for (int chunk = 0; chunk < numChunks; chunk++) {
            int accumulated = 0;
            for (int i = 0; i < numFiles; i++) {
                accumulated += readInt(decompressed, pos);
                pos += 4;

                if (chunk == 0) {
                    fileSizes[i] = accumulated;
                } else {
                    fileSizes[i] += accumulated;
                }
            }
        }

        CacheFile[] files = new CacheFile[numFiles];
        int dataPos = 0;

        for (int i = 0; i < numFiles; i++) {
            byte[] fileData = new byte[fileSizes[i]];
            System.arraycopy(decompressed, dataPos, fileData, 0, fileSizes[i]);
            dataPos += fileSizes[i];
            files[i] = new CacheFile(fileIds[i], fileData);
        }

        return files;
    }

    // ── Decompression helpers ─────────────────────────────

    private static byte[] decompressBZip2(byte[] raw, int offset, int compLen, int decompLen)
            throws IOException {

        byte[] withHeader = new byte[compLen + BZIP2_HEADER.length];
        System.arraycopy(BZIP2_HEADER, 0, withHeader, 0, BZIP2_HEADER.length);
        System.arraycopy(raw, offset, withHeader, BZIP2_HEADER.length, compLen);

        try (InputStream in = new BZip2CompressorInputStream(new ByteArrayInputStream(withHeader))) {
            return readFully(in, decompLen);
        }
    }

    private static byte[] decompressGZip(byte[] raw, int offset, int compLen, int decompLen)
            throws IOException {

        try (InputStream in = new GZIPInputStream(
                new ByteArrayInputStream(raw, offset, compLen))) {

            return readFully(in, decompLen);
        }
    }

    private static byte[] decompressLZMA(byte[] raw, int offset, int compLen, int decompLen)
            throws IOException {

        try (InputStream in = new LZMACompressorInputStream(
                new ByteArrayInputStream(raw, offset, compLen))) {

            return readFully(in, decompLen);
        }
    }

    private static byte[] readFully(InputStream in, int expectedLength) throws IOException {
        byte[] out = new byte[expectedLength];
        int read = 0;

        while (read < expectedLength) {
            int n = in.read(out, read, expectedLength - read);
            if (n == -1) break;
            read += n;
        }

        return out;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off]   & 0xFF) << 24)
                | ((b[off+1] & 0xFF) << 16)
                | ((b[off+2] & 0xFF) <<  8)
                |  (b[off+3] & 0xFF);
    }
}