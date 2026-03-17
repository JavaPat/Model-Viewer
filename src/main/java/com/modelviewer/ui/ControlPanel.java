package com.modelviewer.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.IntConsumer;

/**
 * Bottom control bar.
 *
 * Layout (left → right):
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │ [Mode: ●Colour ○Wire ○Flat] │ [Reset] ║ Export: [RS2][MQO][OBJ] ║       │
 * │ Export All: [RS2][MQO] ║ <progress bar — hidden when idle> │ model info  │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Callbacks are registered by MainWindow after construction.
 */
public final class ControlPanel extends HBox {

    // ── Render mode ───────────────────────────────────────────────────────────
    private final ToggleGroup modeGroup = new ToggleGroup();

    // ── Animation controls ────────────────────────────────────────────────────
    private final Button      playPauseBtn  = new Button("⏵ Play");
    private final Slider      frameSlider   = new Slider(0, 0, 0);
    private final Label       frameLabel    = new Label("–");
    private final HBox        animBox;

    /** True while we are programmatically updating the slider (suppresses seek callback). */
    private boolean suppressSeek = false;

    // ── Status / model info ───────────────────────────────────────────────────
    private final Label modelLabel = new Label("No model loaded");

    // ── Callbacks ─────────────────────────────────────────────────────────────
    private IntConsumer onRenderModeChange;
    private Runnable    onResetCamera;
    private Runnable    onExportRS2;
    private Runnable    onExportMQO;
    private Runnable    onExportOBJ;
    private Runnable    onExportAllRS2;
    private Runnable    onExportAllMQO;
    private Runnable    onExportSelRS2;
    private Runnable    onExportSelMQO;
    private Runnable    onExportSelOBJ;
    private Runnable    onPlayPause;
    private IntConsumer onFrameSeek;

    public ControlPanel() {
        // Build animBox in field initialiser order so ctrlButton() can be called here
        frameSlider.setPrefWidth(160);
        frameSlider.setMajorTickUnit(1);
        frameSlider.setSnapToTicks(true);
        frameSlider.setStyle("-fx-pref-width: 160; -fx-opacity: 0.85;");
        frameSlider.setDisable(true);

        frameLabel.setStyle("-fx-text-fill: #777; -fx-font-size: 10; -fx-min-width: 50;");

        playPauseBtn.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #ccc;"
                + " -fx-border-color: #444; -fx-border-radius: 3; -fx-background-radius: 3;"
                + " -fx-font-size: 11; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        playPauseBtn.setDisable(true);
        playPauseBtn.setOnAction(e -> { if (onPlayPause != null) onPlayPause.run(); });

        frameSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (!suppressSeek && onFrameSeek != null) {
                onFrameSeek.accept(nv.intValue());
            }
        });

        animBox = new HBox(4, sectionLabel("Anim:"), playPauseBtn, frameSlider, frameLabel);
        animBox.setAlignment(Pos.CENTER_LEFT);

        setSpacing(0);
        setPadding(new Insets(7, 12, 7, 12));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #333; -fx-border-width: 1 0 0 0;");

        // ── Render mode radio buttons ─────────────────────────────────────────
        RadioButton colourBtn = modeBtn("Colour",    0);
        RadioButton wireBtn   = modeBtn("Wireframe", 1);
        RadioButton flatBtn   = modeBtn("Flat",      2);
        colourBtn.setSelected(true);

        Label modeLabel = new Label("Mode ");
        modeLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");

        HBox modeBox = new HBox(6, modeLabel, colourBtn, wireBtn, flatBtn);
        modeBox.setAlignment(Pos.CENTER_LEFT);

        // ── Camera reset ──────────────────────────────────────────────────────
        Button resetBtn = ctrlButton("Reset Camera", () -> { if (onResetCamera != null) onResetCamera.run(); });

        // ── Divider ───────────────────────────────────────────────────────────
        // "Export Current" section
        Label exportCurLabel = sectionLabel("Export:");
        Button exportRS2Btn  = ctrlButton("RS2",  () -> { if (onExportRS2 != null) onExportRS2.run(); });
        Button exportMQOBtn  = ctrlButton("MQO",  () -> { if (onExportMQO != null) onExportMQO.run(); });
        Button exportOBJBtn  = ctrlButton("OBJ",  () -> { if (onExportOBJ != null) onExportOBJ.run(); });

        HBox exportCurBox = new HBox(4, exportCurLabel, exportRS2Btn, exportMQOBtn, exportOBJBtn);
        exportCurBox.setAlignment(Pos.CENTER_LEFT);

        // "Export Selected" section
        Label exportSelLabel = sectionLabel("Export Sel:");
        Button exportSelRS2Btn = ctrlButton("RS2", () -> { if (onExportSelRS2 != null) onExportSelRS2.run(); });
        Button exportSelMQOBtn = ctrlButton("MQO", () -> { if (onExportSelMQO != null) onExportSelMQO.run(); });
        Button exportSelOBJBtn = ctrlButton("OBJ", () -> { if (onExportSelOBJ != null) onExportSelOBJ.run(); });

        HBox exportSelBox = new HBox(4, exportSelLabel, exportSelRS2Btn, exportSelMQOBtn, exportSelOBJBtn);
        exportSelBox.setAlignment(Pos.CENTER_LEFT);

        // "Export All" section
        Label exportAllLabel = sectionLabel("Export All:");
        Button exportAllRS2Btn = accentButton("RS2", () -> { if (onExportAllRS2 != null) onExportAllRS2.run(); });
        Button exportAllMQOBtn = accentButton("MQO", () -> { if (onExportAllMQO != null) onExportAllMQO.run(); });

        HBox exportAllBox = new HBox(4, exportAllLabel, exportAllRS2Btn, exportAllMQOBtn);
        exportAllBox.setAlignment(Pos.CENTER_LEFT);

        // ── Model info label fills remaining space ─────────────────────────────
        modelLabel.setStyle("-fx-text-fill: #777; -fx-font-size: 11;");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                modeBox,
                divider(),
                resetBtn,
                divider(),
                exportCurBox,
                divider(),
                exportSelBox,
                divider(),
                exportAllBox,
                divider(),
                animBox,
                spacer,
                modelLabel
        );
    }

    // ── Callback registration ─────────────────────────────────────────────────

    public void setOnRenderModeChange(IntConsumer h) { onRenderModeChange = h; }
    public void setOnResetCamera(Runnable h)         { onResetCamera      = h; }
    public void setOnExportRS2(Runnable h)           { onExportRS2        = h; }
    public void setOnExportMQO(Runnable h)           { onExportMQO        = h; }
    public void setOnExportOBJ(Runnable h)           { onExportOBJ        = h; }
    public void setOnExportAllRS2(Runnable h)        { onExportAllRS2     = h; }
    public void setOnExportAllMQO(Runnable h)        { onExportAllMQO     = h; }
    public void setOnExportSelRS2(Runnable h)        { onExportSelRS2     = h; }
    public void setOnExportSelMQO(Runnable h)        { onExportSelMQO     = h; }
    public void setOnExportSelOBJ(Runnable h)        { onExportSelOBJ     = h; }
    public void setOnPlayPause(Runnable h)           { onPlayPause        = h; }
    public void setOnFrameSeek(IntConsumer h)        { onFrameSeek        = h; }

    /** Updates the model info label (thread-safe). */
    public void setModelInfo(String info) {
        Platform.runLater(() -> modelLabel.setText(info));
    }

    // ── Animation control API (called from MainWindow, must be FX thread) ─────

    /**
     * Called when a new animation is loaded.
     * Enables the controls and configures the slider range.
     *
     * @param frameCount total number of frames in the animation
     */
    public void setAnimationFrameCount(int frameCount) {
        Platform.runLater(() -> {
            suppressSeek = true;
            frameSlider.setMax(Math.max(0, frameCount - 1));
            frameSlider.setValue(0);
            suppressSeek = false;
            frameSlider.setDisable(frameCount <= 1);
            playPauseBtn.setDisable(false);
            updateFrameLabel(0, frameCount);
        });
    }

    /**
     * Called each time the animation advances to a new frame.
     * Updates the slider thumb and frame counter label without triggering a seek.
     */
    public void setCurrentFrame(int frame, int total) {
        Platform.runLater(() -> {
            suppressSeek = true;
            frameSlider.setValue(frame);
            suppressSeek = false;
            updateFrameLabel(frame, total);
        });
    }

    /** Toggles the Play/Pause button label to reflect current playback state. */
    public void setAnimationPlaying(boolean playing) {
        Platform.runLater(() -> playPauseBtn.setText(playing ? "⏸ Pause" : "⏵ Play"));
    }

    /** Resets animation controls to disabled/default state (called when a model is loaded). */
    public void clearAnimationControls() {
        Platform.runLater(() -> {
            suppressSeek = true;
            frameSlider.setMax(0);
            frameSlider.setValue(0);
            suppressSeek = false;
            frameSlider.setDisable(true);
            playPauseBtn.setDisable(true);
            playPauseBtn.setText("⏵ Play");
            frameLabel.setText("–");
        });
    }

    private void updateFrameLabel(int frame, int total) {
        frameLabel.setText((frame + 1) + "/" + total);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private RadioButton modeBtn(String text, int mode) {
        RadioButton btn = new RadioButton(text);
        btn.setToggleGroup(modeGroup);
        btn.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        btn.setOnAction(e -> { if (onRenderModeChange != null) onRenderModeChange.accept(mode); });
        return btn;
    }

    /** Standard grey control button. */
    private static Button ctrlButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #ccc;"
                   + " -fx-border-color: #444; -fx-border-radius: 3; -fx-background-radius: 3;"
                   + " -fx-font-size: 11; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    /** Blue accent button — used for "Export All" to visually distinguish from single export. */
    private static Button accentButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: #1e4a8a; -fx-text-fill: #9ac8ff;"
                   + " -fx-border-color: #2e6abf; -fx-border-radius: 3; -fx-background-radius: 3;"
                   + " -fx-font-size: 11; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #666; -fx-font-size: 10;");
        return l;
    }

    /** Thin vertical separator between logical groups. */
    private static Separator divider() {
        Separator sep = new Separator(javafx.geometry.Orientation.VERTICAL);
        sep.setStyle("-fx-opacity: 0.3;");
        HBox.setMargin(sep, new Insets(0, 8, 0, 8));
        return sep;
    }
}
