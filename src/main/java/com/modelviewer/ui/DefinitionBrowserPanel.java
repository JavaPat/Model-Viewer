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

import java.util.function.Consumer;

/**
 * A reusable left-panel that shows a searchable, loadable list of definition
 * IDs (NPC, Item, Object, or Sequence) and fires a callback when the user
 * requests one to be loaded.
 *
 * <p>Structure mirrors {@link ModelListPanel} but is parameterised by a title
 * string and an entry-label prefix (e.g. "NPC #", "Item #", "Object #").
 *
 * Interaction:
 *   – Hover         → tooltip + blue highlight
 *   – Up / Down     → keyboard navigation (native ListView behaviour)
 *   – Enter         → load the focused item
 *   – Double-click  → load the clicked item
 *   – Loaded item   → persistent green accent so the current entry is always visible
 */
public final class DefinitionBrowserPanel extends VBox {

    private final String entryPrefix;

    private final TextField         searchField = new TextField();
    private final ListView<Integer> listView    = new ListView<>();
    private final Button            loadButton  = new Button("Load");
    private final Label             statusLabel = new Label("No cache loaded");

    /** Backing list of ALL available definition IDs. */
    private final ObservableList<Integer> allIds      = FXCollections.observableArrayList();
    private final FilteredList<Integer>   filteredIds = new FilteredList<>(allIds, id -> true);

    private Consumer<Integer> onLoad;

    /** ID of the definition that is currently loaded/displayed. -1 = none. */
    private int loadedId = -1;

    /**
     * Creates a new browser panel.
     *
     * @param title       header label shown at the top of the panel
     * @param entryPrefix prefix used in list cells, e.g. "NPC #" or "Item #"
     */
    public DefinitionBrowserPanel(String title, String entryPrefix) {
        this.entryPrefix = entryPrefix;

        setSpacing(6);
        setPadding(new Insets(8));
        setPrefWidth(220);
        setStyle("-fx-background-color: #1e1e1e;");

        // ── Header label ────────────────────────────────────────────────────
        Label headerLabel = new Label(title);
        headerLabel.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");

        // ── Search field ────────────────────────────────────────────────────
        searchField.setPromptText("Search ID…");
        searchField.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #ddd;"
                           + " -fx-prompt-text-fill: #666; -fx-border-color: #444;");
        searchField.textProperty().addListener((obs, old, nv) -> applyFilter(nv));

        // ── List view ───────────────────────────────────────────────────────
        listView.setItems(filteredIds);
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listView.setStyle("-fx-background-color: #252525; -fx-border-color: #444;");
        listView.setCellFactory(lv -> new EntryCell());

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

        getChildren().addAll(headerLabel, searchField, listView, loadButton, statusLabel);
    }

    /**
     * Populates the list with all available definition IDs.
     * Safe to call from any thread.
     */
    public void setIds(int[] ids) {
        Platform.runLater(() -> {
            allIds.clear();
            for (int id : ids) allIds.add(id);
            applyFilter(searchField.getText());
            statusLabel.setText(ids.length + " entries available");
        });
    }

    /**
     * Registers the callback that fires when a definition should be loaded.
     * Invoked on the JavaFX Application Thread.
     */
    public void setOnLoad(Consumer<Integer> callback) {
        this.onLoad = callback;
    }

    /**
     * Marks {@code id} as the currently loaded/displayed definition.
     * The corresponding list cell gains a green accent; the previous one is cleared.
     * Must be called on the FX thread.
     */
    public void setLoadedId(int id) {
        this.loadedId = id;
        listView.refresh();
    }

    /**
     * Shows a status message below the list (e.g. "Loading…", "Error").
     * Safe to call from any thread.
     */
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
        statusLabel.setText(filteredIds.size() + " / " + allIds.size() + " entries");
    }

    private void loadSelected() {
        Integer selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null && onLoad != null) {
            onLoad.accept(selected);
        }
    }

    // ── Cell ─────────────────────────────────────────────────────────────────

    private final class EntryCell extends ListCell<Integer> {

        private final Tooltip tip = new Tooltip();

        EntryCell() {
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
                setText(entryPrefix + id);
                tip.setText(entryPrefix + id + "   —   ↑↓ to navigate  •  Enter or double-click to load");
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
