package com.modelviewer.ui;

import com.modelviewer.cache.CacheDetector;
import com.modelviewer.cache.CacheDetector.CacheCandidate;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Modal dialog that presents automatically-detected OSRS cache locations and
 * lets the user confirm or override the selection.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Layout
 * ──────────────────────────────────────────────────────────────────────────────
 *
 *   ┌────────────────────────────────────────────────────┐
 *   │  🔍 OSRS Cache Detection                            │
 *   │  RuneLite detected on this machine.                 │
 *   ├────────────────────────────────────────────────────┤
 *   │  Found 1 valid cache location:                      │
 *   │                                                     │
 *   │  ◉ [✓ RuneLite]  C:\Users\…\.runelite\jagexcache   │
 *   │      RuneLite (user data directory)                 │
 *   │                                                     │
 *   │  ○ [  Jagex  ]  C:\Users\…\jagexcache\…            │
 *   │      OSRS official / Jagex Launcher — NOT FOUND     │
 *   ├────────────────────────────────────────────────────┤
 *   │           [Browse Manually]  [Open Selected]        │
 *   └────────────────────────────────────────────────────┘
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Usage
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * <pre>
 *   CacheDetectionDialog.show(ownerWindow, detectedCandidates, allCandidates,
 *       dir -> openCache(dir));
 * </pre>
 *
 * The callback is invoked with the chosen {@link File} when the user clicks
 * "Open Selected".  It is never invoked if the user dismisses the dialog.
 */
public final class CacheDetectionDialog {

    private CacheDetectionDialog() {}

    /**
     * Shows the detection dialog.
     *
     * @param owner      owning window (for modality / positioning)
     * @param valid      list of valid detected candidates (may be empty)
     * @param all        list of all candidate paths probed (valid + invalid)
     * @param onAccept   callback invoked with the chosen directory on "Open Selected"
     */
    public static void show(Window owner,
                            List<CacheCandidate> valid,
                            List<CacheCandidate> all,
                            Consumer<File> onAccept) {
        Dialog<File> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("OSRS Cache Detection");
        dialog.setResizable(true);

        // ── Header ─────────────────────────────────────────────────────────────
        boolean rlInstalled = CacheDetector.isRuneLiteInstalled();

        Label headingLabel = new Label("OSRS Cache Detection");
        headingLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        headingLabel.setStyle("-fx-text-fill: #ddd;");

        String subText = buildSubText(rlInstalled, valid.size());
        Label subLabel = new Label(subText);
        subLabel.setStyle("-fx-text-fill: " + (rlInstalled ? "#6fc86f" : "#aaa") + ";");
        subLabel.setWrapText(true);

        VBox header = new VBox(4, headingLabel, subLabel);
        header.setPadding(new Insets(0, 0, 10, 0));

        // ── Candidate radio list ───────────────────────────────────────────────
        ToggleGroup toggleGroup = new ToggleGroup();
        VBox candidateBox = new VBox(8);
        candidateBox.setPadding(new Insets(4, 0, 4, 0));

        // "All" candidates — both valid and invalid — so user sees the full picture
        // Build the combined set: valid first, then invalid-only entries
        List<CacheCandidate> combined = buildCombinedList(valid, all);

        CacheCandidate[] firstValid = { combined.isEmpty() ? null
                : combined.stream().filter(CacheCandidate::isValid).findFirst().orElse(null) };

        for (CacheCandidate c : combined) {
            RadioButton radio = new RadioButton();
            radio.setToggleGroup(toggleGroup);
            radio.setStyle("-fx-text-fill: #ccc;");
            radio.setUserData(c);

            if (c.isValid()) {
                // Pre-select first valid candidate
                if (firstValid[0] != null && c == firstValid[0]) {
                    radio.setSelected(true);
                }
            } else {
                radio.setDisable(true);
            }

            // Tag badge: [RuneLite] or [Jagex]
            Label badge = badgeLabel(c);

            // Validity tick
            Label validityIcon = new Label(c.isValid() ? "✓" : "✗");
            validityIcon.setStyle("-fx-text-fill: " + (c.isValid() ? "#6fc86f" : "#c05050") + ";"
                                + " -fx-font-weight: bold;");

            // Path and source sub-labels
            Label pathLabel = new Label(c.directory().getAbsolutePath());
            pathLabel.setStyle("-fx-text-fill: " + (c.isValid() ? "#ccc" : "#666") + ";"
                             + " -fx-font-size: 11;");
            pathLabel.setWrapText(false);

            String statusText = c.source()
                    + (c.isValid() ? "" : "  —  directory not found");
            Label sourceLabel = new Label(statusText);
            sourceLabel.setStyle("-fx-text-fill: " + (c.isValid() ? "#777" : "#555") + ";"
                               + " -fx-font-size: 10;");

            VBox textStack = new VBox(1, pathLabel, sourceLabel);
            HBox row = new HBox(8, radio, validityIcon, badge, textStack);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));
            row.setStyle("-fx-background-color: " + (c.isValid() ? "#252525" : "#1e1e1e") + ";"
                       + " -fx-border-color: #333; -fx-border-radius: 4; -fx-background-radius: 4;");

            candidateBox.getChildren().add(row);
        }

        if (combined.isEmpty()) {
            Label noneLabel = new Label("No OSRS cache was found in any standard location.\n"
                                      + "Click \"Browse Manually\" to locate your cache folder.");
            noneLabel.setStyle("-fx-text-fill: #999;");
            noneLabel.setWrapText(true);
            candidateBox.getChildren().add(noneLabel);
        }

        // Wrap in a scroll pane in case there are many candidates
        ScrollPane scroll = new ScrollPane(candidateBox);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(40 + combined.size() * 76, 320));
        scroll.setStyle("-fx-background-color: #1e1e1e; -fx-border-color: transparent;");

        // ── Buttons ────────────────────────────────────────────────────────────
        ButtonType openType   = new ButtonType("Open Selected",   ButtonBar.ButtonData.OK_DONE);
        ButtonType browseType = new ButtonType("Browse Manually", ButtonBar.ButtonData.LEFT);
        ButtonType cancelType = ButtonType.CANCEL;

        dialog.getDialogPane().getButtonTypes().addAll(openType, browseType, cancelType);

        // Disable "Open Selected" if nothing valid is selected
        Button openBtn = (Button) dialog.getDialogPane().lookupButton(openType);
        openBtn.setDisable(firstValid[0] == null);
        openBtn.setStyle("-fx-background-color: #3a7bd5; -fx-text-fill: white;");

        toggleGroup.selectedToggleProperty().addListener((obs, o, nv) ->
            openBtn.setDisable(nv == null || !((CacheCandidate) nv.getUserData()).isValid()));

        // ── Content layout ─────────────────────────────────────────────────────
        VBox content = new VBox(12, header, new Separator(), scroll);
        content.setPadding(new Insets(16));
        content.setStyle("-fx-background-color: #1e1e1e;");
        content.setPrefWidth(560);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setStyle("-fx-background-color: #1e1e1e;");

        // ── Result converter ───────────────────────────────────────────────────
        dialog.setResultConverter(btn -> {
            if (btn == browseType) return null;  // handled below
            if (btn == openType) {
                Toggle sel = toggleGroup.getSelectedToggle();
                if (sel != null) return ((CacheCandidate) sel.getUserData()).directory();
            }
            return null;
        });

        // "Browse Manually" opens a directory chooser outside the normal result path
        Button browseBtn = (Button) dialog.getDialogPane().lookupButton(browseType);
        browseBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();   // prevent dialog from closing via normal result converter
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select OSRS Cache Directory");
            File manualDir = dc.showDialog(owner);
            if (manualDir != null) {
                dialog.setResult(manualDir);
                dialog.close();
                onAccept.accept(manualDir);
            }
        });

        Optional<File> result = dialog.showAndWait();
        result.ifPresent(onAccept);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String buildSubText(boolean rlInstalled, int validCount) {
        if (rlInstalled && validCount > 0) {
            return "RuneLite is installed — " + validCount + " valid cache location"
                    + (validCount == 1 ? "" : "s") + " found.";
        }
        if (rlInstalled) {
            return "RuneLite is installed but no cache was found yet.\n"
                 + "Run OSRS at least once to download the cache, or browse manually.";
        }
        if (validCount > 0) {
            return validCount + " cache location" + (validCount == 1 ? "" : "s") + " found.";
        }
        return "No OSRS installation was detected automatically.";
    }

    private static Label badgeLabel(CacheCandidate c) {
        Label badge = new Label(c.fromRuneLite() ? " RuneLite " : "  Jagex  ");
        badge.setStyle(
            "-fx-font-size: 10; -fx-font-weight: bold; -fx-background-radius: 3;"
            + " -fx-padding: 1 4 1 4; -fx-text-fill: white;"
            + " -fx-background-color: " + (c.fromRuneLite() ? "#8a5b00" : "#1e4a8a") + ";");
        return badge;
    }

    /**
     * Builds the combined display list: valid candidates first (in detection order),
     * followed by any invalid candidates that exist only in the "all" list.
     * Invalid ones are shown so the user can see what was checked and understand
     * why it wasn't auto-selected.
     */
    private static List<CacheCandidate> buildCombinedList(List<CacheCandidate> valid,
                                                           List<CacheCandidate> all) {
        List<CacheCandidate> result = new ArrayList<>(valid);
        Set<String> validPaths = new java.util.HashSet<>();
        for (CacheCandidate c : valid) {
            validPaths.add(canonical(c.directory()));
        }
        for (CacheCandidate c : all) {
            if (!validPaths.contains(canonical(c.directory()))) {
                result.add(c);
            }
        }
        return result;
    }

    private static String canonical(File f) {
        try { return f.getCanonicalPath(); }
        catch (Exception e) { return f.getAbsolutePath(); }
    }
}
