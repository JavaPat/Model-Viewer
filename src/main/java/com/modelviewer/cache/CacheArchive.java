package com.modelviewer.cache;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Decompresses raw archive bytes into one or more {@link CacheFile} objects.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Compressed archive format (the byte stream returned by CacheReader.readRaw):
 * ──────────────────────────────────────────────────────────────────────────────
 *
 *   [0]     : compression type
 *               0 = none
 *               1 = BZIP2  (header "BZh1" must be prepended before decompression)
 *               2 = GZIP
 *               3 = LZMA
 *   [1-4]   : compressed data length (int32, big-endian)
 *   if type != 0:
 *     [5-8] : decompressed length (int32, big-endian)
 *     [9..] : compressed bytes (length = compressed data length)
 *   else:
 *     [5..] : raw bytes (length = compressed data length)
 *
 *   After the payload, an optional 2-byte version number may be appended.
 *
 * Multi-file archive layout (after decompression):
 * ──────────────────────────────────────────────────────────────────────────────
 *   If the archive contains exactly one file, the decompressed bytes ARE the file.
 *   If the archive contains multiple files, the decompressed bytes begin with:
 *     [last byte]         : number of file chunks (usually 1)
 *     [(n_files*4) bytes] : per-file sizes (relative/delta encoded), from the END
 *
 *   The chunk count and file sizes are stored at the END of the decompressed
 *   data so the structure can be read without knowing the file count in advance.
 */
public final class CacheArchive {

    private static final int COMPRESSION_NONE  = 0;
    private static final int COMPRESSION_BZIP2 = 1;
    private static final int COMPRESSION_GZIP  = 2;
    private static final int COMPRESSION_LZMA  = 3;

    /** BZIP2 block-size header that Jagex strips before storing ("BZh1"). */
    private static final byte[] BZIP2_HEADER = {0x42, 0x5A, 0x68, 0x31};

    private CacheArchive() {}

    /**
     * Decompresses the raw bytes from {@link CacheReader#readRaw} and returns
     * the decompressed payload.
     *
     * @throws IOException on decompression failure or malformed data
     */
    public static byte[] decompress(byte[] raw) throws IOException {
        if (raw == null || raw.length < 5) {
            throw new IOException("Archive data too short: " + (raw == null ? 0 : raw.length));
        }

        int compressionType  = raw[0] & 0xFF;
        int compressedLength = readInt(raw, 1);

        if (compressionType == COMPRESSION_NONE) {
            // No compression — payload starts at byte 5
            byte[] out = new byte[compressedLength];
            System.arraycopy(raw, 5, out, 0, compressedLength);
            return out;
        }

        int decompressedLength = readInt(raw, 5);
        int dataOffset         = 9;  // 1 (type) + 4 (comp len) + 4 (decomp len)

        return switch (compressionType) {
            case COMPRESSION_BZIP2 -> decompressBZip2(raw, dataOffset, compressedLength, decompressedLength);
            case COMPRESSION_GZIP  -> decompressGZip (raw, dataOffset, compressedLength, decompressedLength);
            case COMPRESSION_LZMA  -> decompressLZMA (raw, dataOffset, compressedLength, decompressedLength);
            default -> throw new IOException("Unknown compression type: " + compressionType);
        };
    }

    /**
     * Splits a decompressed multi-file archive into individual {@link CacheFile} objects.
     *
     * If the archive holds a single file, the decompressed bytes are returned
     * directly as file 0.  Otherwise, the file boundaries are computed from the
     * size table at the end of the decompressed data.
     *
     * @param archiveId the parent archive ID (used when only one file is present)
     * @param decompressed decompressed archive payload (from {@link #decompress})
     * @param fileIds ordered array of file IDs expected in this archive
     */
    public static CacheFile[] extractFiles(int archiveId, byte[] decompressed, int[] fileIds) {
        if (fileIds == null || fileIds.length == 0) {
            // Treat the whole payload as one file
            return new CacheFile[]{new CacheFile(archiveId, decompressed)};
        }
        if (fileIds.length == 1) {
            return new CacheFile[]{new CacheFile(fileIds[0], decompressed)};
        }

        // Multi-file: read the chunk count from the last byte
        int numChunks = decompressed[decompressed.length - 1] & 0xFF;
        int numFiles  = fileIds.length;

        // File sizes are stored as a delta-encoded table, numChunks entries per file
        // Table starts at: decompressed.length - 1 - (numFiles * numChunks * 4)
        int tableOffset = decompressed.length - 1 - (numFiles * numChunks * 4);
        if (tableOffset < 0) {
            // Corrupt or unexpected format — return single file
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

        // Extract each file by copying the correct slice of the payload
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

    // ── Decompression helpers ────────────────────────────────────────────────

    private static byte[] decompressBZip2(byte[] raw, int offset, int compLen, int decompLen)
            throws IOException {
        // Jagex strips the 4-byte "BZh1" magic before storing; we must re-add it
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

    /** Big-endian 32-bit int read from an arbitrary byte-array offset. */
    private static int readInt(byte[] b, int off) {
        return ((b[off]   & 0xFF) << 24)
             | ((b[off+1] & 0xFF) << 16)
             | ((b[off+2] & 0xFF) <<  8)
             |  (b[off+3] & 0xFF);
    }
}
