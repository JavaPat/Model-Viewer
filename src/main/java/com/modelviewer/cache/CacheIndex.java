package com.modelviewer.cache;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

/**
 * Metadata about one cache index (e.g. index 7 = models).
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Reference table format (read from idx255, archive = indexId)
 * ──────────────────────────────────────────────────────────────────────────────
 *
 *   After decompression, the reference table begins with:
 *
 *   [0]    : protocol
 *               5 = basic (no revision)
 *               6 = versioned
 *               7 = uses "smart" integer encoding for counts
 *
 *   [1-4]  : revision (only if protocol ≥ 6)
 *
 *   [next] : flags byte
 *               bit 0 = archives have names
 *               bit 1 = archives have whirlpool hashes
 *               bit 2 = archives have sizes
 *               bit 3 = archives have unknown hash
 *
 *   [next] : archive count (unsigned short for protocol ≤ 6, big smart for 7)
 *
 *   Per archive (delta-encoded IDs):
 *     unsigned smart : delta from previous archive ID
 *
 *   If flag bit 0 set:
 *     per archive: int (name hash, djb2 of the archive name, or 0)
 *
 *   Per archive: int (CRC32)
 *
 *   If flag bit 1 set: per archive: 64 bytes (whirlpool hash) — skipped here
 *   If flag bit 2 set: per archive: int compressedSize + int decompressedSize
 *
 *   Per archive: int revision
 *   Per archive: unsigned short (or big smart) file count
 *
 *   For archives with > 1 file, per-file IDs (delta-encoded):
 *     unsigned smart : delta from previous file ID
 *
 * We only parse what we need: the archive IDs and file IDs for each archive.
 */
public final class CacheIndex {

    private static final Logger log = LoggerFactory.getLogger(CacheIndex.class);

    /** Cache index number (0–21 typical, or 255 for master). */
    public final int indexId;

    /**
     * Sorted array of archive IDs present in this index.
     * For the model index (7), each archiveId == modelId.
     */
    private final int[] archiveIds;

    /**
     * Per-archive file ID arrays.  {@code fileIds[i]} holds the file IDs for
     * the archive at {@code archiveIds[i]}.  Most model archives have one file.
     */
    private final int[][] fileIds;

    private CacheIndex(int indexId, int[] archiveIds, int[][] fileIds) {
        this.indexId    = indexId;
        this.archiveIds = archiveIds;
        this.fileIds    = fileIds;
    }

    /**
     * Parses the reference table for {@code indexId} from the master index.
     *
     * @param indexId   which index to load metadata for
     * @param reader    open {@link CacheReader} pointing at the cache directory
     * @return parsed {@link CacheIndex}, or null if the index is absent
     */
    public static CacheIndex load(int indexId, CacheReader reader) {
        try {
            byte[] raw = reader.readRaw(255, indexId);
            if (raw == null) return null;
            byte[] decompressed = CacheArchive.decompress(raw);
            return parse(indexId, decompressed);
        } catch (IOException e) {
            log.warn("Failed to load reference table for index {}: {}", indexId, e.getMessage());
            return null;
        }
    }

    private static CacheIndex parse(int indexId, byte[] data) {
        Buffer buf = new Buffer(data);

        int protocol = buf.readUnsignedByte();
        if (protocol < 5 || protocol > 7) {
            log.warn("Unknown reference table protocol {} for index {}", protocol, indexId);
            return null;
        }

        if (protocol >= 6) {
            buf.skip(4);   // revision
        }

        int flags        = buf.readUnsignedByte();
        boolean hasNames = (flags & 1) != 0;
        boolean hasWhirl = (flags & 2) != 0;
        boolean hasSizes = (flags & 4) != 0;
        boolean hasHash  = (flags & 8) != 0;

        // Archive count — "big smart" encoding for protocol 7
        int archiveCount = (protocol >= 7) ? buf.readBigSmart() : buf.readUnsignedShort();
        if (archiveCount <= 0) return new CacheIndex(indexId, new int[0], new int[0][]);

        // Archive IDs (delta-encoded)
        int[] archiveIds = new int[archiveCount];
        int last = 0;
        for (int i = 0; i < archiveCount; i++) {
            int delta = (protocol >= 7) ? buf.readBigSmart() : buf.readUnsignedShort();
            last += delta;
            archiveIds[i] = last;
        }

        if (hasNames) buf.skip(archiveCount * 4);   // name hashes
        buf.skip(archiveCount * 4);                 // CRC32 per archive
        if (hasHash)  buf.skip(archiveCount * 4);   // unknown hash
        if (hasWhirl) buf.skip(archiveCount * 64);  // whirlpool hashes (64 bytes each)
        if (hasSizes) buf.skip(archiveCount * 8);   // compressed + decompressed sizes

        buf.skip(archiveCount * 4);  // revision per archive

        // File counts per archive
        int[] fileCounts = new int[archiveCount];
        for (int i = 0; i < archiveCount; i++) {
            fileCounts[i] = (protocol >= 7) ? buf.readBigSmart() : buf.readUnsignedShort();
        }

        // Per-archive file IDs (delta-encoded within each archive)
        int[][] fileIds = new int[archiveCount][];
        for (int i = 0; i < archiveCount; i++) {
            fileIds[i] = new int[fileCounts[i]];
            int lastFile = 0;
            for (int j = 0; j < fileCounts[i]; j++) {
                int delta = (protocol >= 7) ? buf.readBigSmart() : buf.readUnsignedShort();
                lastFile += delta;
                fileIds[i][j] = lastFile;
            }
        }

        return new CacheIndex(indexId, archiveIds, fileIds);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns all archive IDs in this index in sorted order. */
    public int[] getArchiveIds() {
        return Arrays.copyOf(archiveIds, archiveIds.length);
    }

    /** Returns the number of archives in this index. */
    public int getArchiveCount() {
        return archiveIds.length;
    }

    /**
     * Returns the file IDs for the given archive, or an empty array if not found.
     * For model archives this almost always returns {@code [0]}.
     */
    public int[] getFileIds(int archiveId) {
        int idx = Arrays.binarySearch(archiveIds, archiveId);
        if (idx < 0) return new int[0];
        return fileIds[idx] != null ? fileIds[idx] : new int[0];
    }

    /** Returns true if the given archive ID exists in this index. */
    public boolean hasArchive(int archiveId) {
        return Arrays.binarySearch(archiveIds, archiveId) >= 0;
    }
}
