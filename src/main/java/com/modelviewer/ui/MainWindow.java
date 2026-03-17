package com.modelviewer.ui;

import com.modelviewer.animation.AnimationApplier;
import com.modelviewer.animation.AnimationFrame;
import com.modelviewer.animation.FramemapDecoder;
import com.modelviewer.animation.FramemapDef;
import com.modelviewer.animation.SeqDefinition;
import com.modelviewer.animation.SeqLoader;
import com.modelviewer.cache.CacheDetector;
import com.modelviewer.cache.CacheLibrary;
import com.modelviewer.database.AssetIndexer;
import com.modelviewer.database.DatabaseManager;
import com.modelviewer.definitions.*;
import com.modelviewer.export.*;
import com.modelviewer.model.ModelAssembler;
import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import com.modelviewer.search.SearchResult;
import com.modelviewer.search.SearchService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application main window.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Menu bar  [File: Open Cache…, Quit]  [Help: About]                     │
 * ├──────────────────┬──────────────────────────────────────────────────────┤
 * │                  │                                                      │
 * │  TabPane         │            ViewportPanel  (3-D OpenGL)               │
 * │  (left, ~22 %)   │                                                      │
 * │   Models | NPCs  │                                                      │
 * │   Items | Objects│                                                      │
 * ├──────────────────┴──────────────────────────────────────────────────────┤
 * │  ControlPanel: Mode | Reset ║ Export RS2/MQO/OBJ ║ Export All RS2/MQO  │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
public final class MainWindow extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    // ── Child panels ──────────────────────────────────────────────────────────
    private final ModelListPanel         modelListPanel = new ModelListPanel();
    private final ViewportPanel          viewportPanel  = new ViewportPanel();
    private final ControlPanel           controlPanel   = new ControlPanel();
    private final DebugPanel             debugPanel     = new DebugPanel();

    private final DefinitionBrowserPanel npcPanel    = new DefinitionBrowserPanel("NPCs",       "NPC #");
    private final DefinitionBrowserPanel itemPanel   = new DefinitionBrowserPanel("Items",      "Item #");
    private final DefinitionBrowserPanel objectPanel = new DefinitionBrowserPanel("Objects",    "Object #");
    private final DefinitionBrowserPanel seqPanel    = new DefinitionBrowserPanel("Sequences",  "Seq #");

    // ── Application state ─────────────────────────────────────────────────────
    private CacheLibrary cacheLibrary = null;
    private ModelMesh    currentMesh  = null;
    private Stage        primaryStage;

    /**
     * Single-thread executor for background cache I/O and single-model decoding.
     * Single-threaded to prevent concurrent seeks on the cache RandomAccessFile.
     */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osrs-cache-io");
        t.setDaemon(true);
        return t;
    });

    // ── Animation playback ────────────────────────────────────────────────────

    /**
     * Single-thread scheduler for frame-advance ticks.
     * Separate from ioExecutor so animation playback never blocks cache reads.
     */
    private final ScheduledExecutorService animExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "osrs-anim-thread");
                t.setDaemon(true);
                return t;
            });

    /** Cancelled and replaced each time a new animation starts. */
    private ScheduledFuture<?> animFuture = null;

    /**
     * Unique ID counter for per-frame posed mesh copies.
     * Each frame gets a fresh ID so the GPU LRU cache always re-uploads it.
     * Starts at a large negative value to avoid collisions with real model IDs
     * and the synthetic NPC/item/object IDs.
     */
    private final AtomicInteger animMeshIdCounter = new AtomicInteger(Integer.MIN_VALUE);

    // ── Animation playback state (accessed from animExecutor + FX thread) ─────

    /** Base mesh for the current animation (rest pose). Non-null while an animation is active. */
    private volatile ModelMesh        animBaseMesh  = null;
    /** Decoded frames for the current animation. Non-null while an animation is active. */
    private volatile AnimationFrame[] animFrames    = null;
    /** Seq definition for the current animation (loop / duration info). */
    private volatile SeqDefinition    animSeqDef    = null;
    /** Current frame index — written by animExecutor, read by seekToFrame(). */
    private volatile int              animFrameIdx  = 0;
    /** True when playback is paused (no self-rescheduling tick). */
    private volatile boolean          animPaused    = true;

    // ── Database / search ──────────────────────────────────────────────────────
    private DatabaseManager dbManager   = null;
    private SearchService   searchService = null;
    private final SearchPanel searchPanel = new SearchPanel();

    // ──────────────────────────────────────────────────────────────────────────
    // JavaFX lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        MenuBar menuBar = buildMenuBar(stage);

        // Build the TabPane that wraps all four browser panels on the left side
        TabPane tabPane = buildTabPane();

        SplitPane splitPane = new SplitPane(tabPane, viewportPanel);
        splitPane.setOrientation(Orientation.HORIZONTAL);
        splitPane.setDividerPositions(0.22);
        SplitPane.setResizableWithParent(tabPane, Boolean.FALSE);

        VBox topBar = new VBox(menuBar, searchPanel);
        searchPanel.setOnResultSelected(this::handleSearchResult);

        VBox bottomBar = new VBox(controlPanel, debugPanel);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(splitPane);
        root.setBottom(bottomBar);
        root.setStyle("-fx-background-color: #1a1a1a;");

        // ── Wire up ControlPanel callbacks ────────────────────────────────────
        modelListPanel.setOnLoadModel(this::loadModel);
        controlPanel.setOnRenderModeChange(viewportPanel::setRenderMode);
        controlPanel.setOnResetCamera(viewportPanel::resetCamera);

        // Single-model export
        controlPanel.setOnExportRS2(() -> exportCurrentModel(BatchExporter.ExportFormat.RS2));
        controlPanel.setOnExportMQO(() -> exportCurrentModel(BatchExporter.ExportFormat.MQO));
        controlPanel.setOnExportOBJ(() -> exportCurrentModel(BatchExporter.ExportFormat.OBJ));

        // Selected-models export
        controlPanel.setOnExportSelRS2(() -> exportSelectedModels(BatchExporter.ExportFormat.RS2));
        controlPanel.setOnExportSelMQO(() -> exportSelectedModels(BatchExporter.ExportFormat.MQO));
        controlPanel.setOnExportSelOBJ(() -> exportSelectedModels(BatchExporter.ExportFormat.OBJ));

        // Batch export
        controlPanel.setOnExportAllRS2(() -> startBatchExport(BatchExporter.ExportFormat.RS2));
        controlPanel.setOnExportAllMQO(() -> startBatchExport(BatchExporter.ExportFormat.MQO));

        // Animation controls
        controlPanel.setOnPlayPause(this::toggleAnimationPlayback);
        controlPanel.setOnFrameSeek(this::seekToFrame);

        // ── Wire up definition panel callbacks ────────────────────────────────
        npcPanel.setOnLoad(this::loadNpc);
        itemPanel.setOnLoad(this::loadItem);
        objectPanel.setOnLoad(this::loadObject);
        seqPanel.setOnLoad(this::loadSeq);

        Scene scene = new Scene(root, 1280, 780);
        stage.setTitle("OSRS Model Viewer");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        // ── Initialise database ────────────────────────────────────────────────
        initDatabase();

        viewportPanel.setRenderEventHandler(msg -> debugPanel.log(msg));
        viewportPanel.start();

        // Auto-detect OSRS cache and open (or prompt the user if ambiguous)
        autoDetectCache(stage);
    }

    @Override
    public void stop() {
        shutdown();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tab pane construction
    // ──────────────────────────────────────────────────────────────────────────

    private TabPane buildTabPane() {
        Tab modelsTab  = new Tab("Models",  modelListPanel);
        Tab npcsTab    = new Tab("NPCs",    npcPanel);
        Tab itemsTab   = new Tab("Items",   itemPanel);
        Tab objectsTab = new Tab("Objects", objectPanel);
        Tab seqsTab    = new Tab("Seqs",    seqPanel);

        modelsTab .setClosable(false);
        npcsTab   .setClosable(false);
        itemsTab  .setClosable(false);
        objectsTab.setClosable(false);
        seqsTab   .setClosable(false);

        TabPane tabPane = new TabPane(modelsTab, npcsTab, itemsTab, objectsTab, seqsTab);
        tabPane.setStyle(
                "-fx-background-color: #1e1e1e;"
              + "-fx-tab-min-width: 48;"
        );
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabPane;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cache operations
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Runs cache auto-detection and either opens the cache silently (when
     * exactly one valid candidate exists and there are no other paths to show)
     * or presents {@link CacheDetectionDialog} so the user can confirm or
     * override the selection.
     */
    private void autoDetectCache(javafx.stage.Window owner) {
        List<CacheDetector.CacheCandidate> valid = CacheDetector.detectAll();
        List<CacheDetector.CacheCandidate> all   = CacheDetector.detectAllCandidates();

        if (valid.size() == 1 && all.size() == 1) {
            // Single unambiguous result — open silently without interrupting user
            openCache(valid.get(0).directory());
            return;
        }

        if (valid.isEmpty() && all.isEmpty()) {
            modelListPanel.setStatus("No OSRS cache found — use File → Open Cache Directory…");
            return;
        }

        CacheDetectionDialog.show(owner, valid, all, this::openCache);

        if (valid.isEmpty()) {
            modelListPanel.setStatus("No OSRS cache found — use File → Open Cache Directory…");
        }
    }

    private void openCache(File dir) {
        modelListPanel.setStatus("Opening cache…");
        debugPanel.log("Opening cache: " + dir.getAbsolutePath());
        ioExecutor.submit(() -> {
            try {
                if (cacheLibrary != null) {
                    cacheLibrary.close();
                    cacheLibrary = null;
                }

                // Validate key cache files before opening
                validateCacheFiles(dir);

                cacheLibrary = new CacheLibrary(dir);
                int[] ids = cacheLibrary.getAllModelIds();
                modelListPanel.setModelIds(ids);

                debugPanel.log("Cache ready — " + ids.length + " models with data in index 7");

                // Populate the definition panels
                int[] npcIds  = cacheLibrary.getDefinitionIds(CacheLibrary.CONFIG_NPCS);
                int[] itemIds = cacheLibrary.getDefinitionIds(CacheLibrary.CONFIG_ITEMS);
                int[] objIds  = cacheLibrary.getDefinitionIds(CacheLibrary.CONFIG_OBJECTS);
                npcPanel.setIds(npcIds);
                itemPanel.setIds(itemIds);
                objectPanel.setIds(objIds);
                debugPanel.log("Definitions — NPCs: " + npcIds.length
                        + "  Items: " + itemIds.length
                        + "  Objects: " + objIds.length);

                // Animations live in index 0; populate from its archive ID list
                int[] animIds = cacheLibrary.getAllAnimationIds();
                seqPanel.setIds(animIds);
                if (animIds.length == 0) {
                    debugPanel.log("Animation index (idx0) is empty — no animation data in this cache");
                } else {
                    debugPanel.log("Animation index 0 — " + animIds.length + " animations");
                }

                if (ids.length < 500) {
                    debugPanel.log("NOTE: Only " + ids.length + " models cached locally. This is a sparse"
                            + " RuneLite cache — items/NPCs/objects whose models are not among these"
                            + " " + ids.length + " will show 'model not in local cache'."
                            + " For full coverage use the official OSRS client cache"
                            + " (%APPDATA%\\Jagex\\Old School RuneLite\\jagexcache).");
                }

                Platform.runLater(() ->
                        modelListPanel.setStatus(ids.length + " models found"));
            } catch (Exception ex) {
                log.error("Failed to open cache", ex);
                debugPanel.log("ERROR opening cache: " + ex.getMessage());
                Platform.runLater(() ->
                        modelListPanel.setStatus("Error: " + ex.getMessage()));
            }
        });
    }

    /**
     * Logs warnings to the debug panel for any missing index files that
     * would silently prevent assets from loading.
     */
    private void validateCacheFiles(File dir) {
        File dat2  = new File(dir, "main_file_cache.dat2");
        File idx255 = new File(dir, "main_file_cache.idx255");
        File idx7  = new File(dir, "main_file_cache.idx7");
        File idx0  = new File(dir, "main_file_cache.idx0");
        File idx2  = new File(dir, "main_file_cache.idx2");

        if (!dat2.exists()) {
            debugPanel.log("MISSING: main_file_cache.dat2 — cache will not open");
            return;
        }
        if (!idx255.exists()) {
            debugPanel.log("MISSING: main_file_cache.idx255 — master index absent");
        }
        if (!idx7.exists()) {
            debugPanel.log("MISSING: main_file_cache.idx7 — models will not load (this is the model index)");
            log.warn("Cache validation: main_file_cache.idx7 not found in {} — all model reads will return null", dir);
        } else {
            debugPanel.log("OK: main_file_cache.idx7 present ("
                    + String.format("%.1f", idx7.length() / 1024.0) + " KB, "
                    + (idx7.length() / 6) + " entries)");
        }
        if (!idx0.exists()) {
            debugPanel.log("MISSING: main_file_cache.idx0 — animations will not load");
        }
        if (!idx2.exists()) {
            debugPanel.log("MISSING: main_file_cache.idx2 — NPC/item/object definitions will not load");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Single-model loading
    // ──────────────────────────────────────────────────────────────────────────

    private void loadModel(int modelId) {
        if (cacheLibrary == null) {
            modelListPanel.setStatus("Open a cache directory first");
            return;
        }
        stopAnimation();
        controlPanel.clearAnimationControls();
        modelListPanel.setStatus("Loading model #" + modelId + "…");
        controlPanel.setModelInfo("Loading…");

        ioExecutor.submit(() -> {
            try {
                debugPanel.log("[MODEL] Requesting model #" + modelId
                        + " from index " + CacheLibrary.INDEX_MODELS);
                log.info("[MODEL] type=model  index={}  archiveId={}", CacheLibrary.INDEX_MODELS, modelId);
                byte[] data = cacheLibrary.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                if (data == null) {
                    log.warn("[MODEL] Not found: index={}  archiveId={}", CacheLibrary.INDEX_MODELS, modelId);
                    debugPanel.log("[MODEL] NOT FOUND — index 7, archiveId=" + modelId
                            + " (check that idx7 is present in the cache directory)");
                    Platform.runLater(() -> {
                        modelListPanel.setStatus("Model #" + modelId + " not found");
                        controlPanel.setModelInfo("Not found");
                    });
                    return;
                }
                log.info("[MODEL] Read {} bytes for archiveId={}", data.length, modelId);
                debugPanel.log("[MODEL] Read " + data.length + " bytes for model #" + modelId);

                ModelMesh mesh = ModelDecoder.decode(modelId, data);
                if (mesh == null) {
                    log.debug("Skipped model {} (unsupported format)", modelId);
                    Platform.runLater(() -> {
                        modelListPanel.setStatus("Model #" + modelId + " unsupported");
                        controlPanel.setModelInfo("Unsupported model");
                    });
                    return;
                }

                debugPanel.log(String.format("[MODEL] #%d — %d verts, %d faces  bbox X[%d..%d] Y[%d..%d] Z[%d..%d]",
                        modelId, mesh.vertexCount, mesh.faceCount,
                        mesh.minX, mesh.maxX, mesh.minY, mesh.maxY, mesh.minZ, mesh.maxZ));

                this.currentMesh = mesh;
                viewportPanel.displayMesh(mesh);

                String info = String.format("Model #%d  |  %,d verts  |  %,d faces",
                        modelId, mesh.vertexCount, mesh.faceCount);
                Platform.runLater(() -> {
                    modelListPanel.setStatus("Loaded model #" + modelId);
                    modelListPanel.setLoadedId(modelId);
                    controlPanel.setModelInfo(info);
                });
            } catch (Exception ex) {
                log.error("Error loading model {}", modelId, ex);
                debugPanel.log("[MODEL] ERROR: " + ex.getMessage());
                Platform.runLater(() -> {
                    modelListPanel.setStatus("Error: " + ex.getMessage());
                    controlPanel.setModelInfo("Error");
                });
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NPC loading
    // ──────────────────────────────────────────────────────────────────────────

    private void loadNpc(int npcId) {
        if (cacheLibrary == null) {
            npcPanel.setStatus("Open a cache directory first");
            return;
        }
        stopAnimation();
        controlPanel.clearAnimationControls();
        npcPanel.setStatus("Loading NPC #" + npcId + "…");

        ioExecutor.submit(() -> {
            try {
                byte[] defData = cacheLibrary.readDefinitionData(CacheLibrary.CONFIG_NPCS, npcId);
                if (defData == null || defData.length == 0) {
                    debugPanel.log("[NPC] Def #" + npcId + " not in local cache (index 2 archive 7 missing/empty)");
                } else {
                    debugPanel.log("[NPC] Def #" + npcId + " — " + defData.length + " bytes");
                }
                NpcDefinition def = NpcLoader.decode(npcId, defData);

                if (def.models == null || def.models.length == 0) {
                    debugPanel.log("[NPC] #" + npcId + " (" + def.name + ") has no model IDs in definition");
                    Platform.runLater(() -> {
                        npcPanel.setStatus("NPC #" + npcId + " has no models");
                        controlPanel.setModelInfo("No models");
                    });
                    return;
                }
                debugPanel.log("[NPC] #" + npcId + " (" + def.name + ") — "
                        + def.models.length + " model part(s): " + java.util.Arrays.toString(def.models));

                // Load each component model
                List<ModelMesh> parts = new ArrayList<>();
                for (int modelId : def.models) {
                    byte[] modelData = cacheLibrary.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                    if (modelData != null) {
                        ModelMesh part = ModelDecoder.decode(modelId, modelData);
                        parts.add(part); // null parts are tolerated by assembler
                    }
                }

                // Use a negative synthetic ID to avoid collisions with real model IDs
                ModelMesh assembled = ModelAssembler.assemble(
                        -(npcId + 1),
                        parts,
                        def.recolorFind, def.recolorReplace,
                        def.scaleXZ, def.scaleY, def.scaleXZ);

                if (assembled == null) {
                    Platform.runLater(() -> {
                        npcPanel.setStatus("NPC #" + npcId + " — no renderable parts");
                        controlPanel.setModelInfo("No renderable parts");
                    });
                    return;
                }

                currentMesh = assembled;
                viewportPanel.displayMesh(assembled);

                String displayName = def.name;
                int    modelCount  = def.models.length;
                Platform.runLater(() -> {
                    npcPanel.setStatus("Loaded NPC #" + npcId
                            + (!"null".equals(displayName) ? " (" + displayName + ")" : ""));
                    npcPanel.setLoadedId(npcId);
                    controlPanel.setModelInfo(String.format("NPC #%d  |  %s  |  %d part(s)  |  %,d verts  |  %,d faces",
                            npcId, displayName, modelCount,
                            assembled.vertexCount, assembled.faceCount));
                });
            } catch (Exception ex) {
                log.error("Error loading NPC {}", npcId, ex);
                Platform.runLater(() -> {
                    npcPanel.setStatus("Error: " + ex.getMessage());
                    controlPanel.setModelInfo("Error");
                });
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Item loading
    // ──────────────────────────────────────────────────────────────────────────

    private void loadItem(int itemId) {
        if (cacheLibrary == null) {
            itemPanel.setStatus("Open a cache directory first");
            return;
        }
        stopAnimation();
        controlPanel.clearAnimationControls();
        itemPanel.setStatus("Loading item #" + itemId + "…");

        ioExecutor.submit(() -> {
            try {
                byte[] defData = cacheLibrary.readDefinitionData(CacheLibrary.CONFIG_ITEMS, itemId);
                if (defData == null || defData.length == 0) {
                    debugPanel.log("[ITEM] Def #" + itemId + " not in local cache (index 2 archive 8 missing/empty)");
                } else {
                    debugPanel.log("[ITEM] Def #" + itemId + " — " + defData.length + " bytes");
                }
                ItemDefinition def = ItemLoader.decode(itemId, defData);

                // Choose the best model to display: prefer inventoryModel, fall back to maleModel0
                int modelId = def.inventoryModel;
                if (modelId == -1) {
                    modelId = def.maleModel0;
                }

                if (modelId == -1) {
                    debugPanel.log("[ITEM] #" + itemId + " (" + def.name + ") has no model ID in definition");
                    Platform.runLater(() -> {
                        itemPanel.setStatus("Item #" + itemId + " has no model");
                        controlPanel.setModelInfo("No model");
                    });
                    return;
                }
                final int resolvedModelId = modelId;
                debugPanel.log("[ITEM] #" + itemId + " (" + def.name + ") → model #" + resolvedModelId);

                byte[] modelData = cacheLibrary.readArchiveData(CacheLibrary.INDEX_MODELS, resolvedModelId);
                if (modelData == null) {
                    debugPanel.log("[ITEM] Model #" + resolvedModelId + " not in local cache (idx7 missing this model)");
                    Platform.runLater(() -> {
                        itemPanel.setStatus("Item #" + itemId + " — model #" + resolvedModelId + " not cached locally");
                        controlPanel.setModelInfo("Model not in local cache");
                    });
                    return;
                }

                ModelMesh part = ModelDecoder.decode(modelId, modelData);

                List<ModelMesh> parts = new ArrayList<>();
                parts.add(part);

                // Use a large negative offset to avoid collisions with NPC synthetic IDs
                ModelMesh assembled = ModelAssembler.assemble(
                        -(itemId + 1_000_001),
                        parts,
                        def.recolorFind, def.recolorReplace,
                        128, 128, 128); // items don't have a scale opcode; default 1.0×

                if (assembled == null) {
                    Platform.runLater(() -> {
                        itemPanel.setStatus("Item #" + itemId + " — no renderable parts");
                        controlPanel.setModelInfo("No renderable parts");
                    });
                    return;
                }

                currentMesh = assembled;
                viewportPanel.displayMesh(assembled);

                String displayName = def.name;
                Platform.runLater(() -> {
                    itemPanel.setStatus("Loaded item #" + itemId
                            + (!"null".equals(displayName) ? " (" + displayName + ")" : ""));
                    itemPanel.setLoadedId(itemId);
                    controlPanel.setModelInfo(String.format("Item #%d  |  %s  |  %,d verts  |  %,d faces",
                            itemId, displayName,
                            assembled.vertexCount, assembled.faceCount));
                });
            } catch (Exception ex) {
                log.error("Error loading item {}", itemId, ex);
                Platform.runLater(() -> {
                    itemPanel.setStatus("Error: " + ex.getMessage());
                    controlPanel.setModelInfo("Error");
                });
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Object loading
    // ──────────────────────────────────────────────────────────────────────────

    private void loadObject(int objectId) {
        if (cacheLibrary == null) {
            objectPanel.setStatus("Open a cache directory first");
            return;
        }
        stopAnimation();
        controlPanel.clearAnimationControls();
        objectPanel.setStatus("Loading object #" + objectId + "…");

        ioExecutor.submit(() -> {
            try {
                byte[] defData = cacheLibrary.readDefinitionData(CacheLibrary.CONFIG_OBJECTS, objectId);
                if (defData == null || defData.length == 0) {
                    debugPanel.log("[OBJECT] Def #" + objectId + " not in local cache (index 2 archive 5 missing/empty)");
                } else {
                    debugPanel.log("[OBJECT] Def #" + objectId + " — " + defData.length + " bytes");
                }
                ObjectDefinition def = ObjectLoader.decode(objectId, defData);

                if (def.objectModels == null || def.objectModels.length == 0) {
                    debugPanel.log("[OBJECT] #" + objectId + " (" + def.name + ") has no model IDs in definition");
                    Platform.runLater(() -> {
                        objectPanel.setStatus("Object #" + objectId + " has no models");
                        controlPanel.setModelInfo("No models");
                    });
                    return;
                }
                debugPanel.log("[OBJECT] #" + objectId + " (" + def.name + ") — "
                        + def.objectModels.length + " model part(s): " + java.util.Arrays.toString(def.objectModels));

                // Load each component model
                List<ModelMesh> parts = new ArrayList<>();
                for (int modelId : def.objectModels) {
                    byte[] modelData = cacheLibrary.readArchiveData(CacheLibrary.INDEX_MODELS, modelId);
                    if (modelData != null) {
                        ModelMesh part = ModelDecoder.decode(modelId, modelData);
                        parts.add(part); // null parts are tolerated by assembler
                    }
                }

                // Use a large negative offset separate from NPC and item synthetic IDs
                ModelMesh assembled = ModelAssembler.assemble(
                        -(objectId + 2_000_001),
                        parts,
                        def.recolorFind, def.recolorReplace,
                        def.scaleX, def.scaleY, def.scaleZ);

                if (assembled == null) {
                    Platform.runLater(() -> {
                        objectPanel.setStatus("Object #" + objectId + " — no renderable parts");
                        controlPanel.setModelInfo("No renderable parts");
                    });
                    return;
                }

                currentMesh = assembled;
                viewportPanel.displayMesh(assembled);

                String displayName = def.name;
                int    modelCount  = def.objectModels.length;
                Platform.runLater(() -> {
                    objectPanel.setStatus("Loaded object #" + objectId
                            + (!"null".equals(displayName) ? " (" + displayName + ")" : ""));
                    objectPanel.setLoadedId(objectId);
                    controlPanel.setModelInfo(String.format("Object #%d  |  %s  |  %d part(s)  |  %,d verts  |  %,d faces",
                            objectId, displayName, modelCount,
                            assembled.vertexCount, assembled.faceCount));
                });
            } catch (Exception ex) {
                log.error("Error loading object {}", objectId, ex);
                Platform.runLater(() -> {
                    objectPanel.setStatus("Error: " + ex.getMessage());
                    controlPanel.setModelInfo("Error");
                });
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sequence loading + animation playback
    // ──────────────────────────────────────────────────────────────────────────

    private void loadSeq(int animId) {
        if (cacheLibrary == null) {
            seqPanel.setStatus("Open a cache directory first");
            return;
        }
        seqPanel.setStatus("Loading animation #" + animId + "…");
        stopAnimation();

        ioExecutor.submit(() -> {
            try {
                log.info("[ANIMATION] index={}  archiveId={}", CacheLibrary.INDEX_ANIMATIONS, animId);

                // Decode the seq definition (index 2, archive 9) for frame IDs + loop metadata
                byte[] defData = cacheLibrary.readDefinitionData(CacheLibrary.CONFIG_SEQS, animId);
                SeqDefinition def = SeqLoader.decode(animId, defData);

                String loopInfo = def.loopOffset >= 0
                        ? "loops from frame " + def.loopOffset
                        : "plays once";

                if (def.frameIds == null || def.frameIds.length == 0) {
                    debugPanel.log("[ANIM] #" + animId + " — no frame IDs in seq definition");
                    String info = String.format("Animation #%d  |  0 frames  |  no frame data",
                            animId);
                    Platform.runLater(() -> {
                        seqPanel.setStatus("Animation #" + animId + " has no frames");
                        seqPanel.setLoadedId(animId);
                        controlPanel.setModelInfo(info);
                    });
                    return;
                }

                // Determine which framemap archive to use (from the first frame's packed ID)
                int framemapId = def.frameIds[0] >>> 16;

                // Read and decode the framemap (skeleton) — file 0 of the framemap archive
                byte[] framemapData = cacheLibrary.readFile(
                        CacheLibrary.INDEX_ANIMATIONS, framemapId, 0);
                if (framemapData == null) {
                    debugPanel.log("[ANIM] #" + animId + " — framemap #" + framemapId
                            + " not found in cache (index 0 missing or sparse)");
                    String info = String.format("Animation #%d  |  %d frame(s)  |  %s  |  framemap not cached",
                            animId, def.frameCount, loopInfo);
                    Platform.runLater(() -> {
                        seqPanel.setStatus("Animation #" + animId + " — framemap not in local cache");
                        seqPanel.setLoadedId(animId);
                        controlPanel.setModelInfo(info);
                    });
                    return;
                }

                FramemapDef framemap = FramemapDecoder.decodeFramemap(framemapData);
                if (framemap == null) {
                    debugPanel.log("[ANIM] #" + animId + " — could not decode framemap #" + framemapId
                            + " (" + framemapData.length + " bytes)");
                    String info = String.format("Animation #%d  |  framemap decode failed", animId);
                    Platform.runLater(() -> {
                        seqPanel.setStatus("Animation #" + animId + " — framemap parse failed");
                        controlPanel.setModelInfo(info);
                    });
                    return;
                }

                // Load all frames referenced by the seq definition
                AnimationFrame[] frames = new AnimationFrame[def.frameCount];
                int loaded = 0;
                for (int i = 0; i < def.frameCount; i++) {
                    int frameFile = def.frameIds[i] & 0xFFFF;
                    byte[] frameData = cacheLibrary.readFile(
                            CacheLibrary.INDEX_ANIMATIONS, framemapId, frameFile);
                    frames[i] = FramemapDecoder.decodeFrame(framemap, frameData);
                    if (frames[i] != null) loaded++;
                }

                debugPanel.log(String.format(
                        "[ANIM] #%d — framemap #%d, %d/%d frames decoded, %s",
                        animId, framemapId, loaded, def.frameCount, loopInfo));

                String info = String.format("Animation #%d  |  %d frame(s)  |  %s",
                        animId, def.frameCount, loopInfo);
                Platform.runLater(() -> {
                    seqPanel.setStatus("Loaded animation #" + animId);
                    seqPanel.setLoadedId(animId);
                    controlPanel.setModelInfo(info);
                });

                // Attempt to play the animation on the current mesh
                ModelMesh base = currentMesh;
                if (base == null) {
                    debugPanel.log("[ANIM] #" + animId
                            + " — no model loaded; load an NPC/model first to preview animation");
                    return;
                }
                if (base.vertexSkins == null) {
                    debugPanel.log("[ANIM] #" + animId
                            + " — current mesh has no skin data; animation transforms cannot be applied");
                    return;
                }

                debugPanel.log("[ANIM] #" + animId + " — starting playback on model "
                        + base.modelId + " (" + base.vertexCount + " skinned verts)");
                startAnimationLoop(base, frames, def);

            } catch (Exception ex) {
                log.error("Error loading animation {}", animId, ex);
                Platform.runLater(() -> {
                    seqPanel.setStatus("Error: " + ex.getMessage());
                    controlPanel.setModelInfo("Error");
                });
            }
        });
    }

    /**
     * Stores animation state in fields and starts playback from frame 0.
     * Called from the IO executor after all frames have been loaded.
     */
    private void startAnimationLoop(ModelMesh base, AnimationFrame[] frames, SeqDefinition def) {
        stopAnimation();

        animBaseMesh = base;
        animFrames   = frames;
        animSeqDef   = def;
        animFrameIdx = 0;
        animPaused   = false;

        controlPanel.setAnimationFrameCount(def.frameCount);
        controlPanel.setAnimationPlaying(true);

        scheduleNextTick(0);
    }

    /**
     * Self-rescheduling tick that renders one frame and schedules the next.
     * Only runs while {@code animPaused == false} and the future is not cancelled.
     */
    private void scheduleNextTick(long delayMs) {
        animFuture = animExecutor.schedule(() -> {
            try {
                AnimationFrame[] frames = animFrames;
                SeqDefinition    def    = animSeqDef;
                ModelMesh        base   = animBaseMesh;
                if (frames == null || def == null || base == null) return;

                int idx = animFrameIdx;

                // Display this frame
                AnimationFrame frame = (idx < frames.length) ? frames[idx] : null;
                if (frame != null) {
                    int poseId = animMeshIdCounter.getAndDecrement();
                    ModelMesh posed = AnimationApplier.apply(base, frame, poseId);
                    viewportPanel.displayMesh(posed);
                }

                // Update ControlPanel slider/label
                int totalFrames = def.frameCount;
                controlPanel.setCurrentFrame(idx, totalFrames);

                // Compute how long to hold this frame
                long holdMs = 600;
                if (def.frameDurations != null && idx < def.frameDurations.length) {
                    holdMs = Math.max(50, def.frameDurations[idx] * 600L);
                }

                // Advance frame index
                idx++;
                if (idx >= frames.length) {
                    if (def.loopOffset >= 0 && def.loopOffset < frames.length) {
                        idx = def.loopOffset;
                    } else if (def.loopOffset < 0) {
                        // Play-once complete — freeze on last frame
                        animPaused = true;
                        controlPanel.setAnimationPlaying(false);
                        return;
                    } else {
                        idx = 0;
                    }
                }
                animFrameIdx = idx;

                // Reschedule next tick unless paused or cancelled
                if (!animPaused && animFuture != null && !animFuture.isCancelled()) {
                    scheduleNextTick(holdMs);
                }
            } catch (Exception e) {
                log.warn("Animation tick error", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Cancels any running animation playback loop. */
    private void stopAnimation() {
        animPaused = true;
        if (animFuture != null && !animFuture.isDone()) {
            animFuture.cancel(false);
            animFuture = null;
        }
        animBaseMesh = null;
        animFrames   = null;
        animSeqDef   = null;
        animFrameIdx = 0;
    }

    /** Toggles between playing and paused. Called from the Play/Pause button. */
    private void toggleAnimationPlayback() {
        if (animFrames == null) return;
        if (animPaused) {
            // Resume playback from current frame
            animPaused = false;
            controlPanel.setAnimationPlaying(true);
            scheduleNextTick(0);
        } else {
            // Pause: cancel the pending tick
            animPaused = true;
            if (animFuture != null) {
                animFuture.cancel(false);
                animFuture = null;
            }
            controlPanel.setAnimationPlaying(false);
        }
    }

    /**
     * Jumps to a specific frame index (called by the frame slider).
     * Works while paused or playing; when playing, the tick loop picks up from
     * the new index naturally on its next reschedule.
     */
    private void seekToFrame(int frameIdx) {
        AnimationFrame[] frames = animFrames;
        ModelMesh        base   = animBaseMesh;
        if (frames == null || base == null) return;

        frameIdx = Math.max(0, Math.min(frameIdx, frames.length - 1));
        animFrameIdx = frameIdx;

        // Display the target frame immediately
        AnimationFrame frame = frames[frameIdx];
        if (frame != null) {
            int poseId = animMeshIdCounter.getAndDecrement();
            ModelMesh posed = AnimationApplier.apply(base, frame, poseId);
            viewportPanel.displayMesh(posed);
        }

        SeqDefinition def = animSeqDef;
        if (def != null) {
            controlPanel.setCurrentFrame(frameIdx, def.frameCount);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Single-model export  (RS2 / MQO / OBJ)
    // ──────────────────────────────────────────────────────────────────────────

    private void exportCurrentModel(BatchExporter.ExportFormat format) {
        if (currentMesh == null) {
            showAlert("No model loaded", "Load a model before exporting.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Model as " + format.name());
        chooser.setInitialFileName("model_" + currentMesh.modelId + "." + format.extension);
        chooser.getExtensionFilters().add(extensionFilter(format));

        File file = chooser.showSaveDialog(primaryStage);
        if (file == null) return;

        ModelMesh meshToExport = currentMesh;
        ioExecutor.submit(() -> {
            try {
                switch (format) {
                    case RS2 -> RS2Exporter.export(meshToExport, file);
                    case MQO -> MQOExporter.export(meshToExport, file);
                    case OBJ -> ObjExporter.export(meshToExport, file);
                }
                Platform.runLater(() ->
                        controlPanel.setModelInfo("Exported → " + file.getName()));
            } catch (Exception ex) {
                log.error("Export ({}) failed for model {}", format, meshToExport.modelId, ex);
                Platform.runLater(() -> showAlert("Export failed", ex.getMessage()));
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Selected-models export
    // ──────────────────────────────────────────────────────────────────────────

    private void exportSelectedModels(BatchExporter.ExportFormat format) {
        if (cacheLibrary == null) {
            showAlert("No cache loaded", "Open a cache directory first.");
            return;
        }
        List<Integer> selected = modelListPanel.getSelectedIds();
        if (selected.isEmpty()) {
            showAlert("No models selected", "Select one or more models in the list.");
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Export " + selected.size() + " Selected Model(s) as " + format.name());
        File outputDir = dirChooser.showDialog(primaryStage);
        if (outputDir == null) return;

        CacheLibrary cacheSnapshot = cacheLibrary;
        int total = selected.size();
        PrimitiveIterator.OfInt iter = selected.stream().mapToInt(Integer::intValue).iterator();

        Task<BatchExporter.BatchResult> task =
                BatchExporter.createTask(cacheSnapshot, iter, total, outputDir, format);

        ExportProgressDialog dialog = new ExportProgressDialog(primaryStage, task, total);
        dialog.show();

        task.setOnSucceeded(e -> controlPanel.setModelInfo(task.getValue().summary()));
        task.setOnFailed(e -> log.error("Selected export failed", task.getException()));

        Thread exportThread = new Thread(task, "sel-export-" + format.extension);
        exportThread.setDaemon(true);
        exportThread.start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Batch export  (Export All)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Asks the user for an output directory, then runs a batch export of every
     * model in the currently loaded cache.
     *
     * The export runs on a dedicated daemon thread so it doesn't block the I/O
     * executor (which the model-loading workflow still needs).  The progress
     * dialog is modal, preventing the user from triggering concurrent cache
     * operations that could conflict with the export thread's file reads.
     */
    private void startBatchExport(BatchExporter.ExportFormat format) {
        if (cacheLibrary == null) {
            showAlert("No cache loaded", "Open a cache directory first.");
            return;
        }

        int total = cacheLibrary.getModelCount();
        if (total == 0) {
            showAlert("No models", "The loaded cache contains no models.");
            return;
        }

        // Ask where to save files
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Output Directory for " + format.name() + " Export");
        File outputDir = dirChooser.showDialog(primaryStage);
        if (outputDir == null) return;

        // Snapshot cache reference — the dialog is modal so cacheLibrary won't
        // be replaced while the task runs
        CacheLibrary cacheSnapshot = cacheLibrary;

        Task<BatchExporter.BatchResult> task =
                BatchExporter.createTask(cacheSnapshot, cacheSnapshot.modelIdIterator(),
                        total, outputDir, format);

        // Build and show the progress dialog before starting the task
        ExportProgressDialog dialog =
                new ExportProgressDialog(primaryStage, task, total);
        dialog.show();

        // React to task completion on the FX thread
        task.setOnSucceeded(e -> {
            BatchExporter.BatchResult result = task.getValue();
            controlPanel.setModelInfo(result.summary());
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            log.error("Batch export failed", ex);
        });

        // Start the export on a dedicated background thread (not the ioExecutor)
        Thread exportThread = new Thread(task, "batch-export-" + format.extension);
        exportThread.setDaemon(true);
        exportThread.start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Menu bar
    // ──────────────────────────────────────────────────────────────────────────

    private MenuBar buildMenuBar(Stage stage) {
        // File menu
        Menu fileMenu = new Menu("File");

        MenuItem detectItem = new MenuItem("Detect Cache Automatically…");
        detectItem.setOnAction(e -> autoDetectCache(stage));

        MenuItem openItem = new MenuItem("Open Cache Directory…");
        openItem.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select OSRS Cache Directory");
            File initial = CacheLibrary.getDefaultCacheDir();
            if (initial.exists()) chooser.setInitialDirectory(initial);
            File dir = chooser.showDialog(stage);
            if (dir != null) openCache(dir);
        });

        MenuItem quitItem = new MenuItem("Quit");
        quitItem.setOnAction(e -> stage.close());

        MenuItem buildIndexItem   = new MenuItem("Build Asset Index…");
        MenuItem rebuildIndexItem = new MenuItem("Rebuild Asset Index…");

        buildIndexItem.setOnAction(e -> {
            if (cacheLibrary == null) {
                showAlert("No Cache Loaded", "Open a cache directory before building the index.");
                return;
            }
            startIndexing(false);
        });
        rebuildIndexItem.setOnAction(e -> {
            if (cacheLibrary == null) {
                showAlert("No Cache Loaded", "Open a cache directory before rebuilding the index.");
                return;
            }
            startIndexing(true);
        });

        fileMenu.getItems().addAll(detectItem, openItem,
                new SeparatorMenuItem(),
                buildIndexItem, rebuildIndexItem,
                new SeparatorMenuItem(), quitItem);

        // Export menu (mirrors the control panel for keyboard-shortcut users)
        Menu exportMenu = new Menu("Export");

        MenuItem expRS2    = new MenuItem("Export Current as RS2…");
        MenuItem expMQO    = new MenuItem("Export Current as MQO…");
        MenuItem expOBJ    = new MenuItem("Export Current as OBJ…");
        MenuItem sep1      = new SeparatorMenuItem();
        MenuItem expSelRS2 = new MenuItem("Export Selected as RS2…");
        MenuItem expSelMQO = new MenuItem("Export Selected as MQO…");
        MenuItem expSelOBJ = new MenuItem("Export Selected as OBJ…");
        MenuItem sep2      = new SeparatorMenuItem();
        MenuItem expAllRS2 = new MenuItem("Export All as RS2…");
        MenuItem expAllMQO = new MenuItem("Export All as MQO…");

        expRS2    .setOnAction(e -> exportCurrentModel(BatchExporter.ExportFormat.RS2));
        expMQO    .setOnAction(e -> exportCurrentModel(BatchExporter.ExportFormat.MQO));
        expOBJ    .setOnAction(e -> exportCurrentModel(BatchExporter.ExportFormat.OBJ));
        expSelRS2 .setOnAction(e -> exportSelectedModels(BatchExporter.ExportFormat.RS2));
        expSelMQO .setOnAction(e -> exportSelectedModels(BatchExporter.ExportFormat.MQO));
        expSelOBJ .setOnAction(e -> exportSelectedModels(BatchExporter.ExportFormat.OBJ));
        expAllRS2 .setOnAction(e -> startBatchExport(BatchExporter.ExportFormat.RS2));
        expAllMQO .setOnAction(e -> startBatchExport(BatchExporter.ExportFormat.MQO));

        exportMenu.getItems().addAll(expRS2, expMQO, expOBJ, sep1,
                expSelRS2, expSelMQO, expSelOBJ, sep2, expAllRS2, expAllMQO);

        // Help menu
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAlert("OSRS Model Viewer",
                "OSRS Model Viewer — reads the OSRS cache and renders models in 3D.\n\n"
              + "Camera controls:\n"
              + "  Left drag    → rotate\n"
              + "  Middle drag  → pan\n"
              + "  Scroll       → zoom\n"
              + "  Double-click model list → load\n\n"
              + "Export formats:\n"
              + "  RS2 — original cache binary format (RSPS-compatible)\n"
              + "  MQO — Metasequoia polygon modeller format\n"
              + "  OBJ — Wavefront OBJ with MTL colour file"));
        helpMenu.getItems().add(aboutItem);

        MenuBar bar = new MenuBar(fileMenu, exportMenu, helpMenu);
        bar.setStyle("-fx-background-color: #252525;");
        return bar;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────────────────

    private static FileChooser.ExtensionFilter extensionFilter(BatchExporter.ExportFormat fmt) {
        return switch (fmt) {
            case RS2 -> new FileChooser.ExtensionFilter("RS2 Model", "*.rs2");
            case MQO -> new FileChooser.ExtensionFilter("Metasequoia Model", "*.mqo");
            case OBJ -> new FileChooser.ExtensionFilter("Wavefront OBJ", "*.obj");
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Database / search
    // ──────────────────────────────────────────────────────────────────────────

    private void initDatabase() {
        try {
            dbManager = new DatabaseManager();
            if (dbManager.isIndexed()) {
                searchService = new SearchService(dbManager);
                searchPanel.setSearchService(searchService);
                log.info("Asset index loaded from database");
            } else {
                searchPanel.setSearchService(null);
                log.info("Asset index not yet built");
            }
        } catch (Exception e) {
            log.error("Failed to open asset database: {}", e.getMessage(), e);
            searchPanel.setSearchService(null);
        }
    }

    private void startIndexing(boolean forceRebuild) {
        if (dbManager == null) {
            showAlert("Database Error", "Asset database is not available.");
            return;
        }
        if (!forceRebuild && dbManager.isIndexed()) {
            showAlert("Index Already Built",
                    "The asset index is already built.\n\nUse 'Rebuild Asset Index' to rescan.");
            return;
        }

        AssetIndexer task = new AssetIndexer(cacheLibrary, dbManager);
        IndexProgressDialog dialog = new IndexProgressDialog(primaryStage, task);
        dialog.show();

        // addEventHandler preserves the dialog's own setOnSucceeded/setOnFailed handlers
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED, e -> {
            searchService = new SearchService(dbManager);
            searchPanel.setSearchService(searchService);
            controlPanel.setModelInfo(task.getValue().summary());
        });
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED, e ->
                log.error("Asset indexing failed", task.getException()));

        Thread t = new Thread(task, "asset-indexer");
        t.setDaemon(true);
        t.start();
    }

    private void handleSearchResult(SearchResult result) {
        switch (result.type()) {
            case NPC       -> loadNpc(result.id());
            case ITEM      -> loadItem(result.id());
            case OBJECT    -> loadObject(result.id());
            case MODEL     -> loadModel(result.id());
            case ANIMATION -> loadSeq(result.id());
        }
    }

    private void shutdown() {
        stopAnimation();
        viewportPanel.stop();
        ioExecutor.shutdownNow();
        animExecutor.shutdownNow();
        if (cacheLibrary != null) {
            try { cacheLibrary.close(); } catch (Exception ignored) {}
        }
        if (dbManager != null) {
            dbManager.close();
        }
        Platform.exit();
    }

    private static void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
