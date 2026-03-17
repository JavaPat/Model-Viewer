package com.modelviewer.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A compact in-app debug console that surfaces cache, decode, and renderer
 * diagnostic messages without requiring the user to read the system log.
 *
 * Designed to be placed at the bottom of the main window layout.
 * {@link #log} is thread-safe and may be called from any thread.
 */
public final class DebugPanel extends VBox {

    private static final int           MAX_LINES = 300;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea textArea  = new TextArea();
    private       int      lineCount = 0;

    public DebugPanel() {
        setStyle("-fx-background-color: #0d0d0d;"
               + "-fx-border-color: #333333;"
               + "-fx-border-width: 1 0 0 0;");
        setPrefHeight(130);
        setMinHeight(80);

        // ── Header bar ───────────────────────────────────────────────────────
        Label titleLabel = new Label("Debug Console");
        titleLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 11;");
        titleLabel.setPadding(new Insets(2, 6, 2, 6));

        Button clearBtn = new Button("Clear");
        clearBtn.setStyle("-fx-background-color: #2e2e2e; -fx-text-fill: #999999;"
                        + "-fx-font-size: 10; -fx-cursor: hand;"
                        + "-fx-padding: 1 8 1 8;");
        clearBtn.setOnAction(e -> clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(titleLabel, spacer, clearBtn);
        header.setStyle("-fx-background-color: #161616; -fx-padding: 2 4 2 4;");

        // ── Text area ────────────────────────────────────────────────────────
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setStyle(
                "-fx-control-inner-background: #0d0d0d;"
              + "-fx-background-color: #0d0d0d;"
              + "-fx-text-fill: #b0c0b0;"
              + "-fx-font-family: 'Consolas', 'Courier New', monospace;"
              + "-fx-font-size: 11;"
              + "-fx-border-color: transparent;");
        VBox.setVgrow(textArea, Priority.ALWAYS);

        getChildren().addAll(header, textArea);
    }

    /**
     * Appends a timestamped line to the console.
     * Safe to call from any thread.
     */
    public void log(String message) {
        String line = TIME_FMT.format(LocalTime.now()) + "  " + message;
        Platform.runLater(() -> {
            if (lineCount >= MAX_LINES) {
                textArea.clear();
                lineCount = 0;
                textArea.appendText("--- log cleared (limit reached) ---\n");
                lineCount++;
            }
            textArea.appendText(line + "\n");
            lineCount++;
        });
    }

    /** Clears all messages. Safe to call from any thread. */
    public void clear() {
        Platform.runLater(() -> {
            textArea.clear();
            lineCount = 0;
        });
    }
}
