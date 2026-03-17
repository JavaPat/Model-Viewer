package com.modelviewer.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Low-level reader that assembles raw archive bytes from the OSRS dat2/idx file store.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * OSRS file-store format (also called "flat file store" or "jagex store")
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * Files:
 *   main_file_cache.dat2        — main data file; contains all archive payload
 *   main_file_cache.idx0 … 255 — index files; one per cache index
 *
 * Index file layout  (6 bytes per entry):
 *   bytes 0-2 : total data size of the archive (big-endian 24-bit unsigned)
 *   bytes 3-5 : starting sector number in dat2 (big-endian 24-bit unsigned)
 *
 * dat2 sector layout  (always exactly 520 bytes):
 *
 *   For archives whose ID fits in 16 bits (ID < 65536) — "normal" sector:
 *     [0-1]   : archive ID  (uint16)
 *     [2-3]   : chunk       (uint16, 0-based sequence number)
 *     [4-6]   : next sector (uint24; 0 when this is the last sector)
 *     [7]     : index ID    (uint8, sanity-check against the idx file used)
 *     [8-519] : 512 bytes of payload
 *
 *   For archives with ID ≥ 65536 — "extended" sector:
 *     [0-3]   : archive ID  (uint32)
 *     [4-5]   : chunk       (uint16)
 *     [6-8]   : next sector (uint24)
 *     [9]     : index ID    (uint8)
 *     [10-519]: 510 bytes of payload
 */
public final class CacheReader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CacheReader.class);

    private static final int SECTOR_SIZE       = 520;
    private static final int NORMAL_HEADER     = 8;   // header bytes in a normal sector
    private static final int EXTENDED_HEADER   = 10;  // header bytes in an extended sector
    private static final int NORMAL_DATA_SIZE  = 512;
    private static final int EXTENDED_DATA_SIZE = 510;

    /** Master index file (idx255), used to read reference tables. */
    private static final int MASTER_INDEX = 255;

    private final RandomAccessFile dat2;

    /**
     * Array of open idx file handles, indexed by index ID.
     * Null entries indicate that the index is not present on disk.
     */
    private final RandomAccessFile[] idxFiles;

    /**
     * Tracks which index IDs have already had a "idx file not open" warning
     * emitted so the message appears once, not once per archive read.
     */
    private final Set<Integer> warnedMissingIdx = new HashSet<>();

    /**
     * Opens the cache store at {@code cacheDir}.
     *
     * @param cacheDir  directory containing main_file_cache.dat2 and the idx files
     * @throws IOException if dat2 or idx255 cannot be opened
     */
    public CacheReader(File cacheDir) throws IOException {
        File dat2File = new File(cacheDir, "main_file_cache.dat2");
        if (!dat2File.exists()) {
            throw new IOException("main_file_cache.dat2 not found in: " + cacheDir);
        }
        this.dat2 = new RandomAccessFile(dat2File, "r");

        this.idxFiles = new RandomAccessFile[256];
        for (int i = 0; i <= 21; i++) {               // indices 0–21 are typical in OSRS
            File f = new File(cacheDir, "main_file_cache.idx" + i);
            if (f.exists()) {
                idxFiles[i] = new RandomAccessFile(f, "r");
            }
        }
        // Always open the master index (idx255)
        File masterIdx = new File(cacheDir, "main_file_cache.idx255");
        if (masterIdx.exists()) {
            idxFiles[MASTER_INDEX] = new RandomAccessFile(masterIdx, "r");
        } else {
            throw new IOException("main_file_cache.idx255 not found in: " + cacheDir);
        }

        // Log which idx files are present so missing ones are immediately visible
        List<Integer> opened = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i <= 21; i++) {
            if (idxFiles[i] != null) opened.add(i);
            else missing.add(i);
        }
        log.info("Cache opened — idx files present: {}", opened);
        if (!missing.isEmpty()) {
            log.warn("Cache opened — idx files MISSING (reads will return null): {}", missing);
        }
    }

    /**
     * Reads and assembles the raw (still-compressed) bytes for a given archive.
     *
     * @param indexId   the cache index (e.g. 7 for models, 255 for master index)
     * @param archiveId the archive number within that index
     * @return raw compressed archive bytes, or null if the archive does not exist
     * @throws IOException on I/O error
     */
    public synchronized byte[] readRaw(int indexId, int archiveId) throws IOException {
        RandomAccessFile idx = idxFiles[indexId];
        if (idx == null) {
            // Emit once per indexId so the log isn't flooded on every ID click
            if (warnedMissingIdx.add(indexId)) {
                log.warn("readRaw: main_file_cache.idx{} is not open — "
                       + "all reads from index {} will return null. "
                       + "Check that the cache directory contains this file.", indexId, indexId);
            }
            return null;
        }

        // Each index entry is 6 bytes; check bounds
        long idxOffset = (long) archiveId * 6L;
        if (idxOffset + 6 > idx.length()) {
            log.warn("readRaw: index={} archiveId={} is beyond the idx file "
                   + "(idx size={} bytes, covers {} archives)",
                   indexId, archiveId, idx.length(), idx.length() / 6);
            return null;
        }

        // Read the 6-byte index entry
        idx.seek(idxOffset);
        byte[] idxEntry = new byte[6];
        idx.readFully(idxEntry);

        int dataSize   = ((idxEntry[0] & 0xFF) << 16) | ((idxEntry[1] & 0xFF) << 8) | (idxEntry[2] & 0xFF);
        int sectorNum  = ((idxEntry[3] & 0xFF) << 16) | ((idxEntry[4] & 0xFF) << 8) | (idxEntry[5] & 0xFF);

        if (dataSize == 0 || sectorNum == 0) return null;   // empty/unused slot

        // Decide which sector format to use based on archive ID
        boolean extended = archiveId > 0xFFFF;
        int headerSize   = extended ? EXTENDED_HEADER   : NORMAL_HEADER;
        int chunkSize    = extended ? EXTENDED_DATA_SIZE : NORMAL_DATA_SIZE;

        byte[] result = new byte[dataSize];
        int bytesRead  = 0;
        int chunkIndex = 0;

        // Follow the sector chain until all bytes are collected
        while (bytesRead < dataSize) {
            long sectorOffset = (long) sectorNum * SECTOR_SIZE;
            if (sectorOffset + SECTOR_SIZE > dat2.length()) {
                throw new IOException(
                    String.format("Sector %d out of dat2 bounds (index=%d archive=%d)",
                        sectorNum, indexId, archiveId));
            }

            byte[] sector = new byte[SECTOR_SIZE];
            dat2.seek(sectorOffset);
            dat2.readFully(sector);

            // Parse sector header to validate the sector belongs to this archive/chunk
            int readArchiveId;
            int readChunk;
            int nextSector;
            int readIndexId;

            if (extended) {
                readArchiveId = ((sector[0] & 0xFF) << 24) | ((sector[1] & 0xFF) << 16)
                              | ((sector[2] & 0xFF) <<  8) |  (sector[3] & 0xFF);
                readChunk     = ((sector[4] & 0xFF) << 8) | (sector[5] & 0xFF);
                nextSector    = ((sector[6] & 0xFF) << 16) | ((sector[7] & 0xFF) << 8) | (sector[8] & 0xFF);
                readIndexId   = sector[9] & 0xFF;
            } else {
                readArchiveId = ((sector[0] & 0xFF) << 8) | (sector[1] & 0xFF);
                readChunk     = ((sector[2] & 0xFF) << 8) | (sector[3] & 0xFF);
                nextSector    = ((sector[4] & 0xFF) << 16) | ((sector[5] & 0xFF) << 8) | (sector[6] & 0xFF);
                readIndexId   = sector[7] & 0xFF;
            }

            // Sanity checks — corrupt cache will cause misleading decode errors later
            if (readArchiveId != archiveId) {
                throw new IOException(
                    String.format("Archive ID mismatch in sector %d: expected %d, got %d",
                        sectorNum, archiveId, readArchiveId));
            }
            if (readChunk != chunkIndex) {
                throw new IOException(
                    String.format("Chunk index mismatch in sector %d: expected %d, got %d",
                        sectorNum, chunkIndex, readChunk));
            }
            if (readIndexId != indexId) {
                throw new IOException(
                    String.format("Index ID mismatch in sector %d: expected %d, got %d",
                        sectorNum, indexId, readIndexId));
            }

            // Copy payload bytes (limited to remaining bytes needed)
            int toCopy = Math.min(chunkSize, dataSize - bytesRead);
            System.arraycopy(sector, headerSize, result, bytesRead, toCopy);
            bytesRead += toCopy;
            chunkIndex++;
            sectorNum = nextSector;
        }

        return result;
    }

    /**
     * Returns a boolean array where {@code result[archiveId] == true} means that
     * archive has a non-zero entry in the given idx file (dataSize > 0 and sectorNum > 0).
     *
     * Reads the entire idx file in one shot (it is typically < 1 MB) so this is
     * fast enough to call during cache open.  Returns an empty array if the index
     * file is not open.
     */
    public synchronized boolean[] getPopulatedFlags(int indexId) throws IOException {
        RandomAccessFile idx = idxFiles[indexId];
        if (idx == null) return new boolean[0];

        int totalEntries = (int) (idx.length() / 6);
        if (totalEntries == 0) return new boolean[0];

        byte[] raw = new byte[totalEntries * 6];
        idx.seek(0);
        idx.readFully(raw);

        boolean[] flags = new boolean[totalEntries];
        for (int i = 0; i < totalEntries; i++) {
            int off      = i * 6;
            int dataSize = ((raw[off]   & 0xFF) << 16) | ((raw[off+1] & 0xFF) << 8) | (raw[off+2] & 0xFF);
            int sector   = ((raw[off+3] & 0xFF) << 16) | ((raw[off+4] & 0xFF) << 8) | (raw[off+5] & 0xFF);
            flags[i] = (dataSize > 0 && sector > 0);
        }
        return flags;
    }

    /**
     * Returns how many index files were successfully opened.
     * Useful for populating the index list in the UI.
     */
    public int getAvailableIndexCount() {
        int count = 0;
        for (int i = 0; i < idxFiles.length; i++) {
            if (idxFiles[i] != null) count++;
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        dat2.close();
        for (RandomAccessFile f : idxFiles) {
            if (f != null) f.close();
        }
    }
}
