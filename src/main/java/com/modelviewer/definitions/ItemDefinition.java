package com.modelviewer.definitions;

/**
 * Decoded item definition from OSRS cache index 2, archive 8.
 *
 * Only the fields relevant to 3-D model display are populated.
 * All other opcodes are parsed but discarded during decoding.
 */
public final class ItemDefinition {

    /** Definition ID — corresponds to the file ID within config archive 8. */
    public final int id;

    /** Display name of the item, default "null" when absent. */
    public String name = "null";

    /**
     * Model ID for the item's inventory (2-D) view.
     * -1 means not present.
     */
    public int inventoryModel = -1;

    /** Primary male worn model ID, or -1 if absent. */
    public int maleModel0 = -1;

    /** Secondary male worn model ID, or -1 if absent. */
    public int maleModel1 = -1;

    /** Tertiary male worn model ID, or -1 if absent. */
    public int maleModel2 = -1;

    /** Primary female worn model ID, or -1 if absent. */
    public int femaleModel0 = -1;

    /** Secondary female worn model ID, or -1 if absent. */
    public int femaleModel1 = -1;

    /** Tertiary female worn model ID, or -1 if absent. */
    public int femaleModel2 = -1;

    /** HSL colour values to search for when recolouring. May be null. */
    public short[] recolorFind;

    /** HSL replacement colour values, parallel array to recolorFind. May be null. */
    public short[] recolorReplace;

    /** Texture IDs to search for when retexturing. May be null. */
    public short[] retextureFind;

    /** Replacement texture IDs, parallel array to retextureFind. May be null. */
    public short[] retextureReplace;

    public ItemDefinition(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "item_" + id + (!"null".equals(name) ? " (" + name + ")" : "");
    }
}
