package com.modelviewer.definitions;

/**
 * Decoded NPC definition from OSRS cache index 2, archive 7.
 *
 * Only the fields relevant to 3-D model display are populated.
 * All other opcodes are parsed but discarded during decoding.
 */
public final class NpcDefinition {

    /** Definition ID — corresponds to the file ID within config archive 7. */
    public final int id;

    /** Display name of the NPC, default "null" when absent. */
    public String name = "null";

    /** Model IDs that compose this NPC's in-world model. May be null. */
    public int[] models;

    /** HSL colour values to search for when recolouring. May be null. */
    public short[] recolorFind;

    /** HSL replacement colour values, parallel array to recolorFind. May be null. */
    public short[] recolorReplace;

    /** Texture IDs to search for when retexturing. May be null. */
    public short[] retextureFind;

    /** Replacement texture IDs, parallel array to retextureFind. May be null. */
    public short[] retextureReplace;

    /**
     * Horizontal scale factor (128 = 1.0×, i.e. no scaling).
     * Applies to both the X and Z axes.
     */
    public int scaleXZ = 128;

    /**
     * Vertical scale factor (128 = 1.0×, i.e. no scaling).
     * Applies to the Y axis.
     */
    public int scaleY = 128;

    /** Tile footprint size of the NPC (1 = single tile). */
    public int size = 1;

    /** Combat level of the NPC, or -1 if non-combat. */
    public int combatLevel = -1;

    public NpcDefinition(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "npc_" + id + (!"null".equals(name) ? " (" + name + ")" : "");
    }
}
