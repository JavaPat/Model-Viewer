package com.modelviewer.model;

import com.modelviewer.cache.CacheDetector;
import com.modelviewer.cache.CacheLibrary;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelDecodeIntegrationTest {

    @Test
    void decodesRealCacheModelsWithoutInvalidGeometry() throws Exception {
        CacheDetector.CacheCandidate candidate = CacheDetector.detectAll().stream()
                .max(Comparator.comparingLong(c -> new File(c.directory(), "main_file_cache.idx7").length()))
                .orElse(null);
        assumeTrue(candidate != null && candidate.isValid(), "No local OSRS cache available");

        try (CacheLibrary cache = new CacheLibrary(new File(candidate.directory().getAbsolutePath()))) {
            int[] ids = cache.getAllModelIds();
            assumeTrue(ids.length > 0, "No cached model IDs available");

            int decoded = 0;
            for (int modelId : ids) {
                byte[] data = cache.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                if (data == null) {
                    continue;
                }

                ModelMesh mesh = ModelDecoder.decode(modelId, data);
                if (mesh == null) {
                    continue;
                }

                assertNotNull(mesh, "Model " + modelId + " failed to decode");
                assertTrue(mesh.vertexCount > 0, "Model " + modelId + " has no vertices");
                assertTrue(mesh.faceCount > 0, "Model " + modelId + " has no faces");
                ModelDecoder.validateFaceIndices(mesh.vertexCount, mesh.faceVertexA, mesh.faceVertexB, mesh.faceVertexC);

                int maxAbsCoord = 0;
                for (int i = 0; i < mesh.vertexCount; i++) {
                    maxAbsCoord = Math.max(maxAbsCoord, Math.abs(mesh.vertexX[i]));
                    maxAbsCoord = Math.max(maxAbsCoord, Math.abs(mesh.vertexY[i]));
                    maxAbsCoord = Math.max(maxAbsCoord, Math.abs(mesh.vertexZ[i]));
                }
                assertTrue(maxAbsCoord < 1_000_000, "Model " + modelId + " has implausible vertex coordinates");

                decoded++;
                if (decoded >= 10) {
                    break;
                }
            }

            assertTrue(decoded >= 10, "Fewer than 10 models decoded from the local cache");
        }
    }
}
