package com.modelviewer.ui;

import com.modelviewer.database.AssetIndexer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.util.Duration;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Modal progress dialog for the asset-index build operation.
 *
 * Mirrors the pattern of {@link com.modelviewer.export.ExportProgressDialog}:
 * binds to a {@link AssetIndexer} Task's progress and message properties and
 * shows a Cancel button while indexing is running, then a Close button when
 * the task finishes (success or cancellation).
 */
public final class IndexProgressDialog extends Stage {

    private final Label       statusLabel  = new Label("Preparing…");
    private final ProgressBar progressBar  = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Button      cancelButton = new Button("Cancel");
    private final Button      closeButton  = new Button("Close");

    public IndexProgressDialog(Window owner, AssetIndexer task) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.DECORATED);
        setTitle("Building Asset Index\u2026");
        setResizable(false);

        // ── Bindings ──────────────────────────────────────────────────────
        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setPrefWidth(440);
        progressBar.setMinHeight(18);

        statusLabel.textProperty().bind(task.messageProperty());
        statusLabel.setStyle("-fx-text-fill: #ddd;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(440);

        // ── Buttons ───────────────────────────────────────────────────────
        cancelButton.setStyle("-fx-background-color: #b03030; -fx-text-fill: white;"
                            + " -fx-cursor: hand;");
        cancelButton.setOnAction(e -> {
            cancelButton.setDisable(true);
            cancelButton.setText("Cancelling\u2026");
            task.cancel();
        });

        closeButton.setStyle("-fx-background-color: #3a7bd5; -fx-text-fill: white;"
                           + " -fx-cursor: hand;");
        closeButton.setVisible(false);
        closeButton.setOnAction(e -> close());

        HBox buttonBar = new HBox(10, cancelButton, closeButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        // ── Task lifecycle ────────────────────────────────────────────────
        task.setOnSucceeded(e -> {
            AssetIndexer.Result r = task.getValue();
            Platform.runLater(() -> {
                statusLabel.textProperty().unbind();
                statusLabel.setText(r.detailedSummary());
                onFinished("Asset Database Built Successfully");

                // Auto-close after 3 s; clicking Close stops the timer
                PauseTransition autoClose = new PauseTransition(Duration.seconds(3));
                autoClose.setOnFinished(ev -> close());
                autoClose.play();
                closeButton.setOnAction(ev -> { autoClose.stop(); close(); });
            });
        });
        task.setOnCancelled(e -> Platform.runLater(() -> {
            statusLabel.textProperty().unbind();
            statusLabel.setText("Indexing cancelled.");
            onFinished("Indexing Cancelled");
        }));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = (ex != null) ? ex.getMessage() : "Unknown error";
            Platform.runLater(() -> {
                statusLabel.textProperty().unbind();
                statusLabel.setText("Indexing failed: " + msg);
                onFinished("Indexing Failed");
            });
        });

        // ── Layout ────────────────────────────────────────────────────────
        VBox root = new VBox(12, statusLabel, progressBar, buttonBar);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setStyle("-fx-background-color: #1e1e1e;");
        root.setPrefWidth(500);

        setScene(new Scene(root));
    }

    private void onFinished(String newTitle) {
        setTitle(newTitle);
        progressBar.progressProperty().unbind();
        progressBar.setProgress(1.0);
        cancelButton.setVisible(false);
        closeButton.setVisible(true);
    }
}
