package com.modelviewer.model;

import com.modelviewer.cache.CacheDetector;
import com.modelviewer.cache.CacheIndex;
import com.modelviewer.cache.CacheLibrary;
import com.modelviewer.cache.FormatDetector;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic test to investigate why FF FD (new/v253) format models fail to decode.
 *
 * Compares readArchiveData vs readFile for failing models, checks archive file counts,
 * and dumps raw header bytes to identify the true header layout.
 */
class FfdFormatDiagnosticTest {

    @Test
    void diagnoseFailingFfdModels() throws Exception {
        CacheDetector.CacheCandidate candidate = CacheDetector.detectAll().stream()
                .max(Comparator.comparingLong(c -> new File(c.directory(), "main_file_cache.idx7").length()))
                .orElse(null);
        assumeTrue(candidate != null && candidate.isValid(), "No local OSRS cache available");

        try (CacheLibrary cache = new CacheLibrary(new File(candidate.directory().getAbsolutePath()))) {
            CacheIndex modelIndex = cache.getModelIndex();
            assumeTrue(modelIndex != null, "No model index");

            int[] ids = cache.getAllModelIds();
            int ffdCount = 0;
            int maxInspect = 20;

            for (int modelId : ids) {
                byte[] dataFromArchive = cache.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                if (dataFromArchive == null) continue;

                if (FormatDetector.detect(dataFromArchive) != FormatDetector.Format.NEW) continue;

                ffdCount++;
                if (ffdCount > maxInspect) break;

                // --- Compare readArchiveData vs readFile ---
                byte[] dataFromFile = cache.readFile(CacheLibrary.INDEX_MODELS, modelId, 0);

                boolean dataDiffers = !Arrays.equals(dataFromArchive, dataFromFile);

                // --- Archive file count from CacheIndex ---
                int[] fileIds = modelIndex.getFileIds(modelId);

                // --- Dump header bytes (last 40 bytes) ---
                int dumpLen = Math.min(40, dataFromArchive.length);
                int start = dataFromArchive.length - dumpLen;
                StringBuilder archiveTail = new StringBuilder();
                StringBuilder fileTail = new StringBuilder();
                for (int i = start; i < dataFromArchive.length; i++) {
                    if (i > start) archiveTail.append(' ');
                    archiveTail.append(String.format("%02X", dataFromArchive[i] & 0xFF));
                }
                if (dataFromFile != null) {
                    int fs = Math.min(40, dataFromFile.length);
                    int fstart = dataFromFile.length - fs;
                    for (int i = fstart; i < dataFromFile.length; i++) {
                        if (i > fstart) fileTail.append(' ');
                        fileTail.append(String.format("%02X", dataFromFile[i] & 0xFF));
                    }
                }

                // --- First 40 bytes ---
                int headLen = Math.min(40, dataFromArchive.length);
                StringBuilder archiveHead = new StringBuilder();
                for (int i = 0; i < headLen; i++) {
                    if (i > 0) archiveHead.append(' ');
                    archiveHead.append(String.format("%02X", dataFromArchive[i] & 0xFF));
                }

                // --- Attempt decode with readFile data ---
                ModelMesh meshFromArchive = ModelDecoder.decode(modelId, dataFromArchive);
                ModelMesh meshFromFile   = (dataFromFile != null) ? ModelDecoder.decode(modelId, dataFromFile) : null;

                System.out.printf(
                    "Model %d  len=%d  fileIds=%s  dataDiffers=%b  fileLen=%s%n" +
                    "  archiveDecode=%s  fileDecode=%s%n" +
                    "  head40: [%s]%n" +
                    "  tail40: [%s]%n" +
                    "  fileTail: [%s]%n",
                    modelId, dataFromArchive.length,
                    Arrays.toString(fileIds), dataDiffers,
                    dataFromFile != null ? dataFromFile.length : "null",
                    meshFromArchive != null ? ("v=" + meshFromArchive.vertexCount + " f=" + meshFromArchive.faceCount) : "NULL",
                    meshFromFile    != null ? ("v=" + meshFromFile.vertexCount    + " f=" + meshFromFile.faceCount)    : "NULL",
                    archiveHead, archiveTail, fileTail
                );
            }

            System.out.println("Total FF FD models inspected: " + ffdCount);
        }
    }
}
