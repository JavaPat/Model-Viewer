package com.modelviewer.cache;

/**
 * A single file extracted from within a {@link CacheArchive}.
 *
 * Most OSRS archives contain exactly one file; archives in the config index (2)
 * and reference tables often contain multiple files with distinct IDs.
 */
public final class CacheFile {

    /** File ID within its parent archive. */
    public final int fileId;

    /** Raw (decompressed) file bytes. */
    public final byte[] data;

    public CacheFile(int fileId, byte[] data) {
        this.fileId = fileId;
        this.data   = data;
    }
}
