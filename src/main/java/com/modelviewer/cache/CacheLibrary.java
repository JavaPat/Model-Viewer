package com.modelviewer.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.PrimitiveIterator;

/**
 * High-level facade for reading from the OSRS cache.
 *
 * This is the primary entry-point for the rest of the application.
 * It manages the {@link CacheReader} and lazily-loaded {@link CacheIndex} objects.
 *
 * Important: CacheLibrary does NOT load the entire cache into memory.
 * Each call to {@link #readArchiveData} issues only the minimal disk I/O
 * needed to read that specific archive.
 */
public final class CacheLibrary implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CacheLibrary.class);

    /** OSRS cache index that contains raw animation frame data. */
    public static final int INDEX_ANIMATIONS = 0;

    /** OSRS cache index that contains model data. */
    public static final int INDEX_MODELS = 7;

    /** OSRS cache index that contains sprite images (UI graphics, texture source data). */
    public static final int INDEX_SPRITES  = 8;

    /** OSRS cache index that contains texture definitions (reference sprites in index 8). */
    public static final int INDEX_TEXTURES = 9;

    /** OSRS cache index that contains configuration definitions (NPCs, items, objects, etc.). */
    public static final int INDEX_CONFIGS = 2;

    /** Archive ID within index 2 for object/location definitions (LocType). */
    public static final int CONFIG_OBJECTS = 5;

    /** Archive ID within index 2 for NPC definitions (NpcType). */
    public static final int CONFIG_NPCS = 7;

    /** Archive ID within index 2 for item definitions (ItemDef). */
    public static final int CONFIG_ITEMS = 8;

    /** Archive ID within index 2 for animation sequence definitions (SeqType). */
    public static final int CONFIG_SEQS = 9;

    private final CacheReader reader;

    /** Lazily populated metadata index for each cache index. */
    private final CacheIndex[] indices = new CacheIndex[256];

    /** True once the model index metadata has been loaded. */
    private boolean modelIndexLoaded = false;

    public CacheLibrary(File cacheDir) throws IOException {
        this.reader = new CacheReader(cacheDir);
        log.info("Opened cache at: {}", cacheDir);
    }

    /**
     * Returns the metadata for the models index (index 7), loading it on first
     * call.  Returns null if the index is not present in the cache.
     */
    public CacheIndex getModelIndex() {
        if (!modelIndexLoaded) {
            indices[INDEX_MODELS] = CacheIndex.load(INDEX_MODELS, reader);
            modelIndexLoaded = true;
            if (indices[INDEX_MODELS] != null) {
                log.info("Model index loaded: {} models", indices[INDEX_MODELS].getArchiveCount());
            } else {
                log.warn("Model index (7) not found in cache");
            }
        }
        return indices[INDEX_MODELS];
    }

    /**
     * Lazily loads and returns the metadata for any cache index.
     *
     * @param indexId 0–21 (standard) or 255 (master)
     * @return parsed {@link CacheIndex}, or null if absent
     */
    public CacheIndex getIndex(int indexId) {
        if (indices[indexId] == null) {
            indices[indexId] = CacheIndex.load(indexId, reader);
        }
        return indices[indexId];
    }

    /**
     * Reads and decompresses a single archive from the cache.
     *
     * For model loading, call with {@code indexId=7, archiveId=<modelId>}.
     *
     * @param indexId   cache index number
     * @param archiveId archive number within that index
     * @return decompressed archive bytes, or null if not found
     */
    public byte[] readArchiveData(int indexId, int archiveId) {
        try {
            byte[] raw = reader.readRaw(indexId, archiveId);
            if (raw == null) return null;
            return CacheArchive.decompress(raw);
        } catch (IOException e) {
            log.warn("Failed to read archive {}/{}: {}", indexId, archiveId, e.getMessage());
            return null;
        }
    }

    /**
     * Reads a single file from a multi-file archive.
     *
     * Most model archives contain exactly one file (fileId 0), but this
     * handles the general case.
     *
     * @param indexId   cache index
     * @param archiveId archive within that index
     * @param fileId    file within that archive
     * @return raw file bytes, or null if not found
     */
    public byte[] readFile(int indexId, int archiveId, int fileId) {
        try {
            byte[] raw = reader.readRaw(indexId, archiveId);
            if (raw == null) return null;
            byte[] decompressed = CacheArchive.decompress(raw);

            CacheIndex idx = getIndex(indexId);
            int[] fileIds = (idx != null) ? idx.getFileIds(archiveId) : new int[]{0};

            CacheFile[] files = CacheArchive.extractFiles(archiveId, decompressed, fileIds);
            for (CacheFile f : files) {
                if (f.fileId == fileId) return f.data;
            }
            return null;
        } catch (IOException e) {
            log.warn("Failed to read file {}/{}/{}: {}", indexId, archiveId, fileId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns a sorted array of all model IDs available in this cache (index 7).
     *
     * Cross-references the reference table (idx255) against the physical idx7 file
     * so that tombstoned / empty archive entries are excluded.  Models whose
     * reference-table entry exists but whose idx7 slot has dataSize=0 or sectorNum=0
     * would return null from {@link #readArchiveData} and are silently omitted here.
     *
     * Returns an empty array if the model index could not be loaded.
     */
    public int[] getAllModelIds() {
        CacheIndex idx = getModelIndex();
        if (idx == null) return new int[0];

        int[] ids = idx.getArchiveIds();
        try {
            boolean[] populated = reader.getPopulatedFlags(INDEX_MODELS);
            if (populated.length == 0) return ids;   // idx7 not open — return as-is

            int kept = 0;
            for (int id : ids) {
                if (id < populated.length && populated[id]) kept++;
            }
            if (kept == ids.length) return ids;      // nothing to filter

            int[] filtered = new int[kept];
            int fi = 0;
            for (int id : ids) {
                if (id < populated.length && populated[id]) filtered[fi++] = id;
            }
            int skipped = ids.length - kept;
            if (skipped > 0) {
                log.info("Model list: {} of {} reference-table IDs have data in idx7 ({} empty slots omitted)",
                        kept, ids.length, skipped);
            }
            return filtered;
        } catch (Exception e) {
            log.warn("Could not scan idx7 for populated entries: {}", e.getMessage());
            return ids;
        }
    }

    /**
     * Returns a sorted array of all animation IDs available in this cache (index 0).
     * Each ID corresponds to one animation archive (one set of frame data).
     * Returns an empty array if the animation index could not be loaded.
     */
    public int[] getAllAnimationIds() {
        CacheIndex idx = getIndex(INDEX_ANIMATIONS);
        if (idx == null) {
            log.warn("Animation index ({}) not found in cache", INDEX_ANIMATIONS);
            return new int[0];
        }
        log.info("Animation index loaded: {} animations", idx.getArchiveCount());
        return idx.getArchiveIds();
    }

    /** Returns the number of models in this cache without allocating an ID array. */
    public int getModelCount() {
        CacheIndex idx = getModelIndex();
        return idx == null ? 0 : idx.getArchiveCount();
    }

    /**
     * Returns all definition IDs for the given config archive (e.g. {@link #CONFIG_NPCS}).
     *
     * The config index (index 2) stores NPC, item, and object definitions as
     * multi-file archives.  Within each config archive, each file ID corresponds
     * to one definition ID.
     *
     * @param configArchiveId one of {@link #CONFIG_NPCS}, {@link #CONFIG_ITEMS},
     *                        or {@link #CONFIG_OBJECTS}
     * @return sorted array of all definition IDs, or an empty array if not found
     */
    public int[] getDefinitionIds(int configArchiveId) {
        CacheIndex idx = getIndex(INDEX_CONFIGS);
        if (idx == null) return new int[0];
        return idx.getFileIds(configArchiveId);
    }

    /**
     * Reads the raw bytes for one definition from the config index.
     *
     * @param configArchiveId one of {@link #CONFIG_NPCS}, {@link #CONFIG_ITEMS},
     *                        or {@link #CONFIG_OBJECTS}
     * @param defId           the definition ID (file ID within the archive)
     * @return raw decompressed bytes, or null if not found
     */
    public byte[] readDefinitionData(int configArchiveId, int defId) {
        return readFile(INDEX_CONFIGS, configArchiveId, defId);
    }

    /**
     * Returns an iterator over all model IDs in sorted order.
     * Prefer this over {@link #getAllModelIds()} in batch-export paths so the
     * caller never needs to hold the full ID array.
     */
    public PrimitiveIterator.OfInt modelIdIterator() {
        CacheIndex idx = getModelIndex();
        if (idx == null) return Arrays.stream(new int[0]).iterator();
        return Arrays.stream(idx.getArchiveIds()).iterator();
    }

    /**
     * Returns the best automatically-detected OSRS cache directory, or the
     * standard Jagex default path if nothing was found by {@link CacheDetector}.
     *
     * Prefer using {@link CacheDetector#detectAll()} directly when you need the
     * full list of candidates (e.g. for the selection dialog in the UI).
     */
    public static File getDefaultCacheDir() {
        CacheDetector.CacheCandidate best = CacheDetector.detectBest();
        if (best != null) {
            log.debug("Auto-detected cache: {}", best);
            return best.directory();
        }
        // Nothing found — fall back to the RuneLite path so the user sees a
        // reasonable default in the directory chooser even if it doesn't exist yet.
        return CacheDetector.getRuneLiteUserDir() != null
                ? new File(CacheDetector.getRuneLiteUserDir(), "jagexcache/oldschool/LIVE")
                : CacheDetector.getJagexDefaultCacheDir();
    }

    @Override
    public void close() throws IOException {
        reader.close();
        log.info("Cache closed");
    }
}
