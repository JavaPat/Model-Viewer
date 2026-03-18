package com.modelviewer.model;

import com.modelviewer.cache.CacheLibrary;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JagexCacheDecodeIntegrationTest {

    @Test
    void decodesModelsFromCurrentJagexLauncherCache() throws Exception {
        String localAppData = System.getenv("LOCALAPPDATA");
        assumeTrue(localAppData != null && !localAppData.isBlank(), "LOCALAPPDATA not set");

        File cacheDir = new File(localAppData, "Jagex/Old School Runescape/data");
        assumeTrue(cacheDir.isDirectory(), "Current Jagex cache directory not present");

        try (CacheLibrary cache = new CacheLibrary(cacheDir)) {
            int[] ids = cache.getAllModelIds();
            assumeTrue(ids.length > 0, "No model IDs in Jagex cache");

            int decoded = 0;
            int attempted = 0;
            for (int modelId : ids) {
                byte[] data = cache.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                if (data == null) {
                    continue;
                }

                attempted++;
                ModelMesh mesh = ModelDecoder.decode(modelId, data);
                if (mesh != null) {
                    decoded++;
                }

                if (attempted >= 20) {
                    break;
                }
            }

            assertTrue(decoded >= 10,
                    "Decoded only " + decoded + " of first " + attempted + " Jagex cache models");
        }
    }
}
