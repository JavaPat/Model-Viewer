package com.modelviewer.cache;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public final class CacheIndex {

    private static final Logger log = LoggerFactory.getLogger(CacheIndex.class);

    public final int indexId;

    private final int[] archiveIds;
    private final int[][] fileIds;

    private CacheIndex(int indexId, int[] archiveIds, int[][] fileIds) {
        this.indexId = indexId;
        this.archiveIds = archiveIds;
        this.fileIds = fileIds;
    }

    public static CacheIndex load(int indexId, CacheReader reader) {
        try {
            byte[] raw = reader.readRaw(255, indexId);
            if (raw == null) return null;

            byte[] data;

            // ✅ ALWAYS decompress reference tables (correct behaviour)
            try {
                data = CacheArchive.decompress(raw);
            } catch (Exception e) {
                log.warn("Failed to decompress index {} — using raw", indexId);
                data = raw;
            }

            return parse(indexId, data);

        } catch (IOException e) {
            log.warn("Failed to load reference table for index {}: {}", indexId, e.getMessage());
            return null;
        }
    }

    private static CacheIndex parse(int indexId, byte[] data) {
        if (data == null || data.length == 0) {
            log.error("Index {} parse received empty data", indexId);
            return null;
        }

        Buffer buf = new Buffer(data);

        int protocol = buf.readUnsignedByte();

        if (protocol < 5 || protocol > 7) {
            log.error("Invalid protocol {} for index {} — WRONG DATA", protocol, indexId);
            return null;
        }

        if (protocol >= 6) {
            buf.skip(4); // revision
        }

        int flags = buf.readUnsignedByte();
        boolean hasNames = (flags & 1) != 0;
        boolean hasWhirl = (flags & 2) != 0;
        boolean hasSizes = (flags & 4) != 0;
        boolean hasHash  = (flags & 8) != 0;

        // ✅ FIX: protocol 7 uses BIG SMART
        int archiveCount = (protocol >= 7)
                ? buf.readBigSmart()
                : buf.readUnsignedShort();

        if (archiveCount <= 0 || archiveCount > 1_000_000) {
            log.error("Index {} invalid archiveCount {}", indexId, archiveCount);
            return null;
        }

        int[] archiveIds = new int[archiveCount];
        int last = 0;

        for (int i = 0; i < archiveCount; i++) {
            int delta = (protocol >= 7)
                    ? buf.readBigSmart()
                    : buf.readUnsignedShort();

            last += delta;
            archiveIds[i] = last;
        }

        if (hasNames) buf.skip(archiveCount * 4);
        buf.skip(archiveCount * 4); // CRC
        if (hasHash)  buf.skip(archiveCount * 4);
        if (hasWhirl) buf.skip(archiveCount * 64);
        if (hasSizes) buf.skip(archiveCount * 8);

        buf.skip(archiveCount * 4); // revision

        int[] fileCounts = new int[archiveCount];

        for (int i = 0; i < archiveCount; i++) {
            fileCounts[i] = (protocol >= 7)
                    ? buf.readBigSmart()
                    : buf.readUnsignedShort();
        }

        int[][] fileIds = new int[archiveCount][];

        for (int i = 0; i < archiveCount; i++) {
            fileIds[i] = new int[fileCounts[i]];
            int lastFile = 0;

            for (int j = 0; j < fileCounts[i]; j++) {
                int delta = (protocol >= 7)
                        ? buf.readBigSmart()
                        : buf.readUnsignedShort();

                lastFile += delta;
                fileIds[i][j] = lastFile;
            }
        }

        log.info("Index {} loaded: {} archives", indexId, archiveCount);

        return new CacheIndex(indexId, archiveIds, fileIds);
    }

    // ── Accessors ─────────────────────────────────────────────────

    public int[] getArchiveIds() {
        return Arrays.copyOf(archiveIds, archiveIds.length);
    }

    public int getArchiveCount() {
        return archiveIds.length;
    }

    public int[] getFileIds(int archiveId) {
        int idx = Arrays.binarySearch(archiveIds, archiveId);
        if (idx < 0) return new int[0];
        return fileIds[idx] != null ? fileIds[idx] : new int[0];
    }

    public boolean hasArchive(int archiveId) {
        return Arrays.binarySearch(archiveIds, archiveId) >= 0;
    }
}