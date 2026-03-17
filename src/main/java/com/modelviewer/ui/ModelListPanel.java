package com.modelviewer.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left-hand panel containing a searchable list of model IDs.
 *
 * When the user selects and loads a model, the provided {@code onLoadModel}
 * callback is invoked with the chosen model ID.  The callback runs on the
 * JavaFX Application Thread.
 *
 * Interaction:
 *   – Hover         → tooltip + blue highlight
 *   – Up / Down     → keyboard navigation (native ListView behaviour)
 *   – Enter         → load the focused item
 *   – Double-click  → load the clicked item
 *   – Loaded item   → persistent green accent so the current model is always visible
 */
public final class ModelListPanel extends VBox {

    private final TextField         searchField  = new TextField();
    private final ListView<Integer> listView     = new ListView<>();
    private final Button            loadButton   = new Button("Load Model");
    private final Label             statusLabel  = new Label("No cache loaded");

    /** Backing list of ALL available model IDs. */
    private final ObservableList<Integer> allIds      = FXCollections.observableArrayList();
    private final FilteredList<Integer>   filteredIds = new FilteredList<>(allIds, id -> true);

    private Consumer<Integer> onLoadModel;

    /** ID of the model that is currently loaded/displayed. -1 = none. */
    private int loadedId = -1;

    public ModelListPanel() {
        setSpacing(6);
        setPadding(new Insets(8));
        setPrefWidth(220);
        setStyle("-fx-background-color: #1e1e1e;");

        // ── Search field ────────────────────────────────────────────────────
        searchField.setPromptText("Search model ID…");
        searchField.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #ddd;"
                           + " -fx-prompt-text-fill: #666; -fx-border-color: #444;");
        searchField.textProperty().addListener((obs, old, nv) -> applyFilter(nv));

        // ── List view ───────────────────────────────────────────────────────
        listView.setItems(filteredIds);
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listView.setStyle("-fx-background-color: #252525; -fx-border-color: #444;");
        listView.setCellFactory(lv -> new ModelCell());

        // Double-click loads immediately
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) loadSelected();
        });

        // Enter key loads the focused item
        listView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                loadSelected();
                e.consume();
            }
        });

        VBox.setVgrow(listView, Priority.ALWAYS);

        // ── Load button ─────────────────────────────────────────────────────
        loadButton.setMaxWidth(Double.MAX_VALUE);
        loadButton.setDisable(true);
        loadButton.setStyle("-fx-background-color: #3a7bd5; -fx-text-fill: white;"
                          + " -fx-font-weight: bold; -fx-cursor: hand;");
        loadButton.setOnAction(e -> loadSelected());

        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, nv) -> loadButton.setDisable(nv == null));

        // ── Status label ────────────────────────────────────────────────────
        statusLabel.setStyle("-fx-text-fill: #777; -fx-font-size: 11;");
        statusLabel.setWrapText(true);

        getChildren().addAll(
                new Label("Models") {{
                    setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");
                }},
                searchField,
                listView,
                loadButton,
                statusLabel
        );
    }

    /** Populates the list with all available model IDs (call from any thread). */
    public void setModelIds(int[] ids) {
        Platform.runLater(() -> {
            allIds.clear();
            for (int id : ids) allIds.add(id);
            applyFilter(searchField.getText());
            statusLabel.setText(ids.length + " models available");
        });
    }

    /** Registers the callback that fires when a model should be loaded. */
    public void setOnLoadModel(Consumer<Integer> callback) {
        this.onLoadModel = callback;
    }

    /**
     * Marks {@code id} as the currently loaded/displayed model.
     * The corresponding list cell gains a green accent; the previous one is cleared.
     * Must be called on the FX thread.
     */
    public void setLoadedId(int id) {
        this.loadedId = id;
        listView.refresh();
    }

    /** Returns an unmodifiable snapshot of all currently selected model IDs. */
    public List<Integer> getSelectedIds() {
        return new ArrayList<>(listView.getSelectionModel().getSelectedItems());
    }

    /** Shows a status message below the list (e.g. "Loading…", "Error"). */
    public void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void applyFilter(String text) {
        String trimmed = (text == null) ? "" : text.trim();
        if (trimmed.isEmpty()) {
            filteredIds.setPredicate(id -> true);
        } else {
            filteredIds.setPredicate(id -> String.valueOf(id).contains(trimmed));
        }
        statusLabel.setText(filteredIds.size() + " / " + allIds.size() + " models");
    }

    private void loadSelected() {
        Integer selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null && onLoadModel != null) {
            onLoadModel.accept(selected);
        }
    }

    // ── Cell ─────────────────────────────────────────────────────────────────

    private final class ModelCell extends ListCell<Integer> {

        private final Tooltip tip = new Tooltip();

        ModelCell() {
            // Re-apply style whenever hover or selection state changes
            hoverProperty()   .addListener((obs, old, nv) -> refreshStyle());
            selectedProperty().addListener((obs, old, nv) -> refreshStyle());
        }

        @Override
        protected void updateItem(Integer id, boolean empty) {
            super.updateItem(id, empty);
            if (empty || id == null) {
                setText(null);
                setTooltip(null);
                setStyle("-fx-background-color: transparent;");
            } else {
                setText("Model #" + id);
                tip.setText("Model ID: " + id + "   —   ↑↓ to navigate  •  Enter or double-click to load");
                setTooltip(tip);
                refreshStyle();
            }
        }

        private void refreshStyle() {
            Integer id = getItem();
            if (isEmpty() || id == null) return;

            boolean loaded   = (id == loadedId);
            boolean selected = isSelected();
            boolean hovered  = isHover();

            if (loaded && selected) {
                setStyle("-fx-background-color: #2a5c30; -fx-text-fill: #ffffff;"
                       + " -fx-border-color: #4caf50; -fx-border-width: 0 0 0 3;"
                       + " -fx-padding: 2 4 2 5;");
            } else if (loaded) {
                setStyle("-fx-background-color: #1a3d1a; -fx-text-fill: #90ee90;"
                       + " -fx-border-color: #4caf50; -fx-border-width: 0 0 0 3;"
                       + " -fx-padding: 2 4 2 5;");
            } else if (selected) {
                setStyle("-fx-background-color: #3a7bd5; -fx-text-fill: #ffffff;"
                       + " -fx-padding: 2 4 2 8;");
            } else if (hovered) {
                setStyle("-fx-background-color: #2e3a4e; -fx-text-fill: #ffffff;"
                       + " -fx-padding: 2 4 2 8;");
            } else {
                setStyle("-fx-background-color: transparent; -fx-text-fill: #cccccc;"
                       + " -fx-padding: 2 4 2 8;");
            }
        }
    }
}
