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
            int withGeometry = 0;
            int nullResult = 0;
            int attempted = 0;
            for (int modelId : ids) {
                byte[] data = cache.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                if (data == null) {
                    continue;
                }

                attempted++;
                ModelMesh mesh = ModelDecoder.decode(modelId, data);
                if (mesh == null) {
                    nullResult++;
                    System.out.println("  NULL: model " + modelId);
                } else {
                    decoded++;
                    boolean hasGeometry = mesh.vertexCount > 0 && mesh.faceCount > 0
                            && mesh.vertexX[0] != 0 || mesh.vertexY[0] != 0 || mesh.vertexZ[0] != 0;
                    if (hasGeometry) {
                        withGeometry++;
                        System.out.printf("  OK:   model %-6d  v=%-4d f=%-4d  X[%d..%d] Y[%d..%d] Z[%d..%d]%n",
                                modelId, mesh.vertexCount, mesh.faceCount,
                                mesh.minX, mesh.maxX, mesh.minY, mesh.maxY, mesh.minZ, mesh.maxZ);
                    } else {
                        System.out.printf("  ZERO: model %-6d  v=%-4d f=%-4d  (all-zero geometry)%n",
                                modelId, mesh.vertexCount, mesh.faceCount);
                    }
                }

                if (attempted >= 20) {
                    break;
                }
            }

            System.out.printf("Jagex cache: attempted=%d  decoded=%d  withGeometry=%d  null=%d%n",
                    attempted, decoded, withGeometry, nullResult);

            assertTrue(withGeometry >= 10,
                    "Only " + withGeometry + " of " + attempted + " Jagex models have real geometry"
                    + " (decoded=" + decoded + ", null=" + nullResult + ")");
        }
    }
}
