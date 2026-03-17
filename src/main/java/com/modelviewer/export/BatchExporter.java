package com.modelviewer.export;

import com.modelviewer.cache.CacheLibrary;
import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.PrimitiveIterator;

/**
 * Factory for JavaFX {@link Task} objects that batch-export every model in the
 * cache to a chosen output directory.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Design
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * Each batch-export operation is represented as a JavaFX Task<BatchResult>.
 * The task:
 *   - Runs on a background thread (caller's responsibility to start it)
 *   - Updates {@code progress} (0.0 → 1.0) after each model
 *   - Updates {@code message} with the current model ID and running counts
 *   - Honours {@code isCancelled()} to stop early
 *   - Streams models one at a time from cache — never loads all into memory
 *   - Skips models that fail to decode (logs the skip, keeps running)
 *   - Returns a {@link BatchResult} describing how many succeeded/failed/skipped
 *
 * Usage:
 * <pre>
 *   Task<BatchResult> task = BatchExporter.createTask(cache, modelIds, dir, ExportFormat.RS2);
 *   new Thread(task, "batch-export").start();
 *   // Bind progressBar.progressProperty() to task.progressProperty()
 *   // Bind statusLabel.textProperty() to task.messageProperty()
 * </pre>
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Supported export formats
 * ──────────────────────────────────────────────────────────────────────────────
 */
public final class BatchExporter {

    private static final Logger log = LoggerFactory.getLogger(BatchExporter.class);

    private BatchExporter() {}

    // ── Export format enum ────────────────────────────────────────────────────

    /** Enumerates all batch-exportable formats with their file extension. */
    public enum ExportFormat {
        RS2("rs2"),
        MQO("mqo"),
        OBJ("obj");

        public final String extension;

        ExportFormat(String ext) {
            this.extension = ext;
        }
    }

    // ── Batch result ──────────────────────────────────────────────────────────

    /**
     * Summary returned by the Task on completion.
     */
    public record BatchResult(int total, int succeeded, int failed, int skipped,
                              boolean wasCancelled) {
        public String summary() {
            if (wasCancelled) {
                return String.format("Cancelled — %d exported, %d failed, %d skipped",
                        succeeded, failed, skipped);
            }
            return String.format("Done — %d exported, %d failed, %d skipped",
                    succeeded, failed, skipped);
        }
    }

    // ── Task factory ──────────────────────────────────────────────────────────

    /**
     * Creates a batch-export Task.  The caller must start it on a background thread.
     *
     * @param cache     open cache library (must remain open for the duration of the task)
     * @param modelIds  iterator over model IDs to export (consumed exactly once)
     * @param total     total number of IDs in the iterator (used for progress reporting)
     * @param outputDir directory to write exported files into
     * @param format    output format (RS2, MQO, or OBJ)
     * @return a Task ready to be started
     */
    public static Task<BatchResult> createTask(CacheLibrary cache,
                                               PrimitiveIterator.OfInt modelIds,
                                               int total,
                                               File outputDir,
                                               ExportFormat format) {
        return new Task<>() {
            @Override
            protected BatchResult call() {
                int succeeded = 0;
                int failed    = 0;
                int skipped   = 0;
                int i         = 0;

                updateProgress(0, total);
                updateMessage("Starting export of " + total + " models…");

                while (modelIds.hasNext()) {
                    // Honour cancellation — checked at the top of each iteration
                    if (isCancelled()) {
                        updateMessage("Cancelling…");
                        break;
                    }

                    int modelId = modelIds.nextInt();
                    updateMessage(String.format("Exporting model #%d  (%d / %d)",
                            modelId, i + 1, total));
                    updateProgress(i, total);
                    i++;

                    try {
                        // Stream from cache — only this one model is in memory at a time
                        byte[] raw = cache.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                        if (raw == null) {
                            skipped++;
                            continue;
                        }

                        ModelMesh mesh = ModelDecoder.decode(modelId, raw);
                        if (mesh == null) {
                            skipped++;
                            log.debug("Skipped model {} — decode returned null", modelId);
                            continue;
                        }

                        File outFile = new File(outputDir, modelId + "." + format.extension);

                        switch (format) {
                            case RS2 -> RS2Exporter.export(mesh, outFile);
                            case MQO -> MQOExporter.export(mesh, outFile);
                            case OBJ -> ObjExporter.export(mesh, outFile);
                        }
                        succeeded++;

                    } catch (Exception ex) {
                        failed++;
                        log.warn("Failed to export model {}: {}", modelId, ex.getMessage());
                    }
                }

                // Final progress update
                updateProgress(total, total);
                boolean cancelled = isCancelled();
                BatchResult result = new BatchResult(total, succeeded, failed, skipped, cancelled);
                updateMessage(result.summary());
                log.info("Batch export complete: {}", result.summary());
                return result;
            }
        };
    }
}
