package com.modelviewer.database;

import com.modelviewer.animation.SeqDefinition;
import com.modelviewer.animation.SeqLoader;
import com.modelviewer.cache.CacheLibrary;
import com.modelviewer.definitions.*;
import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Scans the entire OSRS cache and populates the asset index database.
 *
 * Extends {@link Task} so it can be bound directly to a progress dialog and
 * run on a daemon thread without additional wrapping.
 *
 * <p>Indexing is performed in five sequential phases:
 * <ol>
 *   <li>Models     — decode geometry to extract vertex/face counts</li>
 *   <li>NPCs       — extract name, combat level, model ID list</li>
 *   <li>Items      — extract name and inventory model ID</li>
 *   <li>Objects    — extract name and model ID list</li>
 *   <li>Animations — extract frame count and skeleton (framemap) ID</li>
 * </ol>
 *
 * Inserts are batched in transactions of {@link #BATCH_SIZE} rows for
 * maximum SQLite throughput.  Each corrupted or missing entry is skipped
 * with a warning so a bad record never aborts the entire index run.
 */
public final class AssetIndexer extends Task<AssetIndexer.Result> {

    private static final Logger log = LoggerFactory.getLogger(AssetIndexer.class);

    /** Number of rows per INSERT transaction. */
    private static final int BATCH_SIZE = 2_000;

    private final CacheLibrary    cache;
    private final DatabaseManager db;

    public AssetIndexer(CacheLibrary cache, DatabaseManager db) {
        this.cache = cache;
        this.db    = db;
    }

    // ── Task entry point ──────────────────────────────────────────────────────

    @Override
    protected Result call() throws Exception {
        long startMs = System.currentTimeMillis();
        int  errors  = 0;
        int  modelsIdx = 0, npcsIdx = 0, itemsIdx = 0, objsIdx = 0, seqsIdx = 0;

        updateMessage("Clearing existing index…");
        db.clearAll();

        // Phase 1: Models
        int[] modelIds = cache.getAllModelIds();
        updateMessage("Indexing models (0 / " + modelIds.length + ")…");
        int[] modelResult = indexModels(modelIds);
        modelsIdx = modelResult[0];
        errors   += modelResult[1];

        if (isCancelled()) return new Result(modelsIdx, 0, 0, 0, 0, errors, System.currentTimeMillis() - startMs);

        // Phase 2: NPCs
        int[] npcIds = cache.getDefinitionIds(CacheLibrary.CONFIG_NPCS);
        updateMessage("Indexing NPCs (0 / " + npcIds.length + ")…");
        int[] npcResult = indexNpcs(npcIds);
        npcsIdx = npcResult[0];
        errors += npcResult[1];

        if (isCancelled()) return new Result(modelsIdx, npcsIdx, 0, 0, 0, errors, System.currentTimeMillis() - startMs);

        // Phase 3: Items
        int[] itemIds = cache.getDefinitionIds(CacheLibrary.CONFIG_ITEMS);
        updateMessage("Indexing items (0 / " + itemIds.length + ")…");
        int[] itemResult = indexItems(itemIds);
        itemsIdx = itemResult[0];
        errors  += itemResult[1];

        if (isCancelled()) return new Result(modelsIdx, npcsIdx, itemsIdx, 0, 0, errors, System.currentTimeMillis() - startMs);

        // Phase 4: Objects
        int[] objIds = cache.getDefinitionIds(CacheLibrary.CONFIG_OBJECTS);
        updateMessage("Indexing objects (0 / " + objIds.length + ")…");
        int[] objResult = indexObjects(objIds);
        objsIdx = objResult[0];
        errors += objResult[1];

        if (isCancelled()) return new Result(modelsIdx, npcsIdx, itemsIdx, objsIdx, 0, errors, System.currentTimeMillis() - startMs);

        // Phase 5: Animations
        int[] seqIds = cache.getDefinitionIds(CacheLibrary.CONFIG_SEQS);
        updateMessage("Indexing animations (0 / " + seqIds.length + ")…");
        int[] seqResult = indexAnimations(seqIds);
        seqsIdx = seqResult[0];
        errors += seqResult[1];

        int total = modelsIdx + npcsIdx + itemsIdx + objsIdx + seqsIdx;
        if (!isCancelled()) {
            db.markIndexed(cache.toString());
            updateMessage("Index complete — " + total + " assets indexed");
            updateProgress(1.0, 1.0);
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Indexing complete: {} assets, {} errors, {}ms", total, errors, elapsed);
        return new Result(modelsIdx, npcsIdx, itemsIdx, objsIdx, seqsIdx, errors, elapsed);
    }

    // ── Phase implementations ─────────────────────────────────────────────────

    /** Returns [indexed, errors]. */
    private int[] indexModels(int[] ids) throws SQLException {
        final String sql =
            "INSERT OR REPLACE INTO models(id, vertex_count, face_count, has_texture) VALUES(?,?,?,?)";
        int indexed = 0, errors = 0;
        Connection conn = db.getConnection();

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                if (isCancelled()) break;
                int id = ids[i];
                try {
                    byte[]    data = cache.readArchiveData(CacheLibrary.INDEX_MODELS, id);
                    ModelMesh mesh = (data != null) ? ModelDecoder.decode(id, data) : null;
                    if (mesh != null) {
                        boolean hasTexture = mesh.faceTextureIds != null;
                        ps.setInt(1, id);
                        ps.setInt(2, mesh.vertexCount);
                        ps.setInt(3, mesh.faceCount);
                        ps.setInt(4, hasTexture ? 1 : 0);
                        ps.executeUpdate();
                        indexed++;
                    }
                } catch (Exception e) {
                    log.debug("Skipping model {}: {}", id, e.getMessage());
                    errors++;
                }

                if (indexed % BATCH_SIZE == 0 && indexed > 0) {
                    conn.commit();
                }
                if (i % 500 == 0 || i == ids.length - 1) {
                    int phase0Total = ids.length;
                    updateMessage("Indexing models (" + indexed + " / " + phase0Total + ")…");
                    // Models are the biggest phase; allocate 0–50% of overall progress
                    updateProgress((double) i / (ids.length * 2.0), 1.0);
                }
            }
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
        return new int[]{indexed, errors};
    }

    private int[] indexNpcs(int[] ids) throws SQLException {
        final String sql =
            "INSERT OR REPLACE INTO npcs(id, name, combat_level, model_ids) VALUES(?,?,?,?)";
        int indexed = 0, errors = 0;
        Connection conn = db.getConnection();

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                if (isCancelled()) break;
                int id = ids[i];
                try {
                    byte[]        data = cache.readDefinitionData(CacheLibrary.CONFIG_NPCS, id);
                    NpcDefinition def  = NpcLoader.decode(id, data);
                    ps.setInt(1, id);
                    ps.setString(2, def.name != null ? def.name : "null");
                    ps.setInt(3, def.combatLevel);
                    ps.setString(4, idsToString(def.models));
                    ps.executeUpdate();
                    indexed++;
                } catch (Exception e) {
                    log.debug("Skipping NPC {}: {}", id, e.getMessage());
                    errors++;
                }
                if (indexed % BATCH_SIZE == 0 && indexed > 0) conn.commit();
                if (i % 500 == 0 || i == ids.length - 1) {
                    updateMessage("Indexing NPCs (" + indexed + " / " + ids.length + ")…");
                    updateProgress(0.5 + 0.1 * i / ids.length, 1.0);
                }
            }
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
        return new int[]{indexed, errors};
    }

    private int[] indexItems(int[] ids) throws SQLException {
        final String sql =
            "INSERT OR REPLACE INTO items(id, name, inventory_model) VALUES(?,?,?)";
        int indexed = 0, errors = 0;
        Connection conn = db.getConnection();

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                if (isCancelled()) break;
                int id = ids[i];
                try {
                    byte[]         data = cache.readDefinitionData(CacheLibrary.CONFIG_ITEMS, id);
                    ItemDefinition def  = ItemLoader.decode(id, data);
                    ps.setInt(1, id);
                    ps.setString(2, def.name != null ? def.name : "null");
                    ps.setInt(3, def.inventoryModel);
                    ps.executeUpdate();
                    indexed++;
                } catch (Exception e) {
                    log.debug("Skipping item {}: {}", id, e.getMessage());
                    errors++;
                }
                if (indexed % BATCH_SIZE == 0 && indexed > 0) conn.commit();
                if (i % 500 == 0 || i == ids.length - 1) {
                    updateMessage("Indexing items (" + indexed + " / " + ids.length + ")…");
                    updateProgress(0.6 + 0.1 * i / ids.length, 1.0);
                }
            }
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
        return new int[]{indexed, errors};
    }

    private int[] indexObjects(int[] ids) throws SQLException {
        final String sql =
            "INSERT OR REPLACE INTO objects(id, name, model_ids) VALUES(?,?,?)";
        int indexed = 0, errors = 0;
        Connection conn = db.getConnection();

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                if (isCancelled()) break;
                int id = ids[i];
                try {
                    byte[]           data = cache.readDefinitionData(CacheLibrary.CONFIG_OBJECTS, id);
                    ObjectDefinition def  = ObjectLoader.decode(id, data);
                    ps.setInt(1, id);
                    ps.setString(2, def.name != null ? def.name : "null");
                    ps.setString(3, idsToString(def.objectModels));
                    ps.executeUpdate();
                    indexed++;
                } catch (Exception e) {
                    log.debug("Skipping object {}: {}", id, e.getMessage());
                    errors++;
                }
                if (indexed % BATCH_SIZE == 0 && indexed > 0) conn.commit();
                if (i % 500 == 0 || i == ids.length - 1) {
                    updateMessage("Indexing objects (" + indexed + " / " + ids.length + ")…");
                    updateProgress(0.7 + 0.2 * i / ids.length, 1.0);
                }
            }
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
        return new int[]{indexed, errors};
    }

    private int[] indexAnimations(int[] ids) throws SQLException {
        final String sql =
            "INSERT OR REPLACE INTO animations(id, frame_count, skeleton_id) VALUES(?,?,?)";
        int indexed = 0, errors = 0;
        Connection conn = db.getConnection();

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                if (isCancelled()) break;
                int id = ids[i];
                try {
                    byte[]        data = cache.readDefinitionData(CacheLibrary.CONFIG_SEQS, id);
                    SeqDefinition def  = SeqLoader.decode(id, data);
                    // skeleton_id = high 16 bits of the first packed frameId
                    int skeletonId = (def.frameCount > 0 && def.frameIds != null)
                            ? (def.frameIds[0] >>> 16) : -1;
                    ps.setInt(1, id);
                    ps.setInt(2, def.frameCount);
                    ps.setInt(3, skeletonId);
                    ps.executeUpdate();
                    indexed++;
                } catch (Exception e) {
                    log.debug("Skipping animation {}: {}", id, e.getMessage());
                    errors++;
                }
                if (indexed % BATCH_SIZE == 0 && indexed > 0) conn.commit();
                if (i % 200 == 0 || i == ids.length - 1) {
                    updateMessage("Indexing animations (" + indexed + " / " + ids.length + ")…");
                    updateProgress(0.9 + 0.1 * i / Math.max(1, ids.length), 1.0);
                }
            }
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
        return new int[]{indexed, errors};
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String idsToString(int[] ids) {
        if (ids == null || ids.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids[i]);
        }
        return sb.toString();
    }

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Summary returned when the Task completes (or is cancelled mid-run).
     * Per-category counts reflect only the phases that completed before the task stopped.
     */
    public record Result(int modelsIndexed, int npcsIndexed, int itemsIndexed,
                         int objectsIndexed, int animationsIndexed,
                         int totalErrors, long elapsedMs) {

        public int totalIndexed() {
            return modelsIndexed + npcsIndexed + itemsIndexed + objectsIndexed + animationsIndexed;
        }

        /** One-line summary used in the control panel info label. */
        public String summary() {
            long secs = elapsedMs / 1000;
            return String.format("Indexed %,d assets (%d errors) in %ds",
                    totalIndexed(), totalErrors, secs);
        }

        /** Multi-line breakdown displayed in the completion dialog. */
        public String detailedSummary() {
            long secs = elapsedMs / 1000;
            return String.format(
                    "Models:      %,d%n" +
                    "NPCs:        %,d%n" +
                    "Items:       %,d%n" +
                    "Objects:     %,d%n" +
                    "Animations:  %,d%n%n" +
                    "%,d assets indexed (%d errors) in %ds",
                    modelsIndexed, npcsIndexed, itemsIndexed, objectsIndexed, animationsIndexed,
                    totalIndexed(), totalErrors, secs);
        }
    }
}
