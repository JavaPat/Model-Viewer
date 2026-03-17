package com.modelviewer.search;

/**
 * A single hit returned by {@link SearchService}.
 *
 * Each result carries the asset type, its numeric ID (used to load it in
 * the viewer), and a human-readable label for display.
 */
public record SearchResult(AssetType type, int id, String label) {

    /** Ordered for display grouping in the search results popup. */
    public enum AssetType {
        NPC("NPCs"),
        ITEM("Items"),
        OBJECT("Objects"),
        MODEL("Models"),
        ANIMATION("Animations");

        public final String displayName;
        AssetType(String displayName) { this.displayName = displayName; }
    }
}
