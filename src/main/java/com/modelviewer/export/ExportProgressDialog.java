package com.modelviewer.export;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Modal progress dialog for batch-export operations.
 *
 * Shows:
 *   - Animated progress bar bound to the Task's progressProperty
 *   - Status label bound to the Task's messageProperty
 *   - Numerical counter label ("1 024 / 5 000")
 *   - Cancel button that calls Task.cancel()
 *   - Close button that appears when the task finishes (success or cancelled)
 *
 * Usage:
 * <pre>
 *   Task<BatchResult> task = BatchExporter.createTask(…);
 *   ExportProgressDialog dialog = new ExportProgressDialog(ownerWindow, task, totalModels);
 *   dialog.show();
 *   Thread t = new Thread(task, "batch-export");
 *   t.setDaemon(true);
 *   t.start();
 * </pre>
 *
 * The dialog is APPLICATION_MODAL: the parent window cannot be interacted
 * with while the export is running, preventing concurrent cache reads from
 * conflicting with the batch export thread.
 */
public final class ExportProgressDialog extends Stage {

    private final Label       statusLabel   = new Label("Preparing…");
    private final Label       counterLabel  = new Label("0 / ?");
    private final ProgressBar progressBar   = new ProgressBar(0);
    private final Button      cancelButton  = new Button("Cancel");
    private final Button      closeButton   = new Button("Close");

    /**
     * Builds and configures the dialog.  Does NOT start the task.
     *
     * @param owner      parent window (for modality)
     * @param task       the batch-export task to monitor
     * @param totalCount total number of models to export (for the counter label)
     */
    public ExportProgressDialog(Window owner, Task<?> task, int totalCount) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.DECORATED);
        setTitle("Exporting Models…");
        setResizable(false);

        // ── Bindings ──────────────────────────────────────────────────────────
        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setPrefWidth(420);
        progressBar.setMinHeight(18);

        statusLabel.textProperty().bind(task.messageProperty());
        statusLabel.setStyle("-fx-text-fill: #ddd;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(420);

        // Counter: update whenever progress changes
        task.progressProperty().addListener((obs, old, nv) -> {
            double p = nv.doubleValue();
            if (p >= 0 && p <= 1) {
                int done = (int) Math.round(p * totalCount);
                Platform.runLater(() ->
                    counterLabel.setText(String.format("%,d / %,d", done, totalCount)));
            }
        });
        counterLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 11;");

        // ── Buttons ───────────────────────────────────────────────────────────
        cancelButton.setStyle("-fx-background-color: #b03030; -fx-text-fill: white;"
                            + " -fx-cursor: hand;");
        cancelButton.setOnAction(e -> {
            cancelButton.setDisable(true);
            cancelButton.setText("Cancelling…");
            task.cancel();
        });

        closeButton.setStyle("-fx-background-color: #3a7bd5; -fx-text-fill: white;"
                           + " -fx-cursor: hand;");
        closeButton.setVisible(false);
        closeButton.setOnAction(e -> close());

        HBox buttonBar = new HBox(10, cancelButton, closeButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        // ── Task lifecycle ────────────────────────────────────────────────────
        task.setOnSucceeded(e -> onFinished("Export Complete"));
        task.setOnCancelled(e -> onFinished("Export Cancelled"));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = (ex != null) ? ex.getMessage() : "Unknown error";
            statusLabel.textProperty().unbind();
            statusLabel.setText("Export failed: " + msg);
            onFinished("Export Failed");
        });

        // ── Layout ────────────────────────────────────────────────────────────
        VBox root = new VBox(12,
                statusLabel,
                new HBox(8, progressBar, counterLabel) {{
                    setAlignment(Pos.CENTER_LEFT);
                }},
                buttonBar
        );
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setStyle("-fx-background-color: #1e1e1e;");
        root.setPrefWidth(480);

        setScene(new Scene(root));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void onFinished(String newTitle) {
        Platform.runLater(() -> {
            setTitle(newTitle);
            cancelButton.setVisible(false);
            closeButton.setVisible(true);
            progressBar.progressProperty().unbind();
            progressBar.setProgress(1.0);
        });
    }
}
