package com.modelviewer.ui;

import com.modelviewer.search.SearchResult;
import com.modelviewer.search.SearchService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Global search bar displayed at the top of the main window.
 *
 * <p>Layout:
 * <pre>
 *   [Search assets…                          ] [status label]
 * </pre>
 *
 * Typing triggers a 250 ms debounce before executing the SQL search.
 * Results appear in a {@link ContextMenu} anchored below the text field,
 * grouped by asset type.
 *
 * <p>Register the asset-load callback with {@link #setOnResultSelected}.
 * The search service is injected via {@link #setSearchService}.
 */
public final class SearchPanel extends HBox {

    private static final int    DEBOUNCE_MS    = 250;
    private static final int    LIMIT_PER_CAT  = 15;
    private static final String NO_INDEX_TEXT  =
            "Asset index not built — use File → Build Asset Index";

    private final TextField   searchField    = new TextField();
    private final Label       statusLabel    = new Label();
    private final ContextMenu resultsMenu    = new ContextMenu();

    private SearchService            searchService;
    private Consumer<SearchResult>   onResultSelected;

    /** PauseTransition used to debounce keystrokes. */
    private final PauseTransition debounce =
            new PauseTransition(Duration.millis(DEBOUNCE_MS));

    public SearchPanel() {
        setSpacing(8);
        setPadding(new Insets(6, 12, 6, 12));
        setStyle("-fx-background-color: #252525; -fx-border-color: #333;"
               + " -fx-border-width: 0 0 1 0;");

        // ── Search field ───────────────────────────────────────────────────
        searchField.setPromptText("Search assets…");
        searchField.setPrefWidth(380);
        searchField.setStyle(
                "-fx-background-color: #2d2d2d; -fx-text-fill: #ddd;"
              + "-fx-prompt-text-fill: #666; -fx-border-color: #444;"
              + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 13;");

        // Debounce: restart the timer on every keystroke
        searchField.textProperty().addListener((obs, old, nv) -> {
            debounce.stop();
            resultsMenu.hide();
            if (nv.isBlank()) {
                statusLabel.setText("");
                return;
            }
            debounce.setOnFinished(e -> triggerSearch(nv.trim()));
            debounce.playFromStart();
        });

        // Close the popup when Escape is pressed
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                resultsMenu.hide();
                searchField.clear();
            }
        });

        // ── Status label ───────────────────────────────────────────────────
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(searchField, spacer, statusLabel);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Injects the search service (may be null if the index is not yet built). */
    public void setSearchService(SearchService service) {
        this.searchService = service;
        updateStatus();
    }

    /** Registers the callback invoked when the user clicks a search result. */
    public void setOnResultSelected(Consumer<SearchResult> callback) {
        this.onResultSelected = callback;
    }

    /** Updates the status label to reflect the current index state. */
    public void updateStatus() {
        Platform.runLater(() -> {
            if (searchService == null) {
                statusLabel.setText(NO_INDEX_TEXT);
                statusLabel.setStyle("-fx-text-fill: #c87050; -fx-font-size: 11;");
            } else {
                statusLabel.setText("");
            }
        });
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void triggerSearch(String query) {
        if (searchService == null) {
            showNoIndexPopup();
            return;
        }

        // Run query on the FX thread (SQLite reads are fast)
        List<SearchResult> hits = searchService.search(query, LIMIT_PER_CAT);
        populateMenu(hits, query);
    }

    private void populateMenu(List<SearchResult> hits, String query) {
        resultsMenu.getItems().clear();

        if (hits.isEmpty()) {
            MenuItem empty = new MenuItem("No results for \u201c" + query + "\u201d");
            empty.setStyle("-fx-text-fill: #888;");
            empty.setDisable(true);
            resultsMenu.getItems().add(empty);
            showMenu();
            return;
        }

        // Group by type, preserving display order
        Map<SearchResult.AssetType, List<SearchResult>> grouped =
                hits.stream().collect(Collectors.groupingBy(SearchResult::type,
                        Collectors.toList()));

        boolean first = true;
        for (SearchResult.AssetType type : SearchResult.AssetType.values()) {
            List<SearchResult> group = grouped.get(type);
            if (group == null || group.isEmpty()) continue;

            if (!first) resultsMenu.getItems().add(new SeparatorMenuItem());
            first = false;

            // Category header (disabled item acting as a label)
            MenuItem header = new MenuItem(type.displayName);
            header.setStyle("-fx-text-fill: #5b9bd5; -fx-font-weight: bold; -fx-font-size: 11;");
            header.setDisable(true);
            resultsMenu.getItems().add(header);

            for (SearchResult hit : group) {
                MenuItem item = new MenuItem(hit.label());
                item.setStyle("-fx-text-fill: #ddd; -fx-padding: 2 4 2 16;");
                item.setOnAction(e -> {
                    resultsMenu.hide();
                    searchField.clear();
                    if (onResultSelected != null) onResultSelected.accept(hit);
                });
                resultsMenu.getItems().add(item);
            }
        }

        showMenu();
    }

    private void showNoIndexPopup() {
        resultsMenu.getItems().clear();
        MenuItem msg = new MenuItem("Index not built — use File \u2192 Build Asset Index");
        msg.setStyle("-fx-text-fill: #c87050;");
        msg.setDisable(true);
        resultsMenu.getItems().add(msg);
        showMenu();
    }

    private void showMenu() {
        if (!resultsMenu.isShowing()) {
            resultsMenu.show(searchField, Side.BOTTOM, 0, 2);
        }
    }
}
