package com.modelviewer.definitions;

/**
 * Decoded object/location definition from OSRS cache index 2, archive 5.
 *
 * Only the fields relevant to 3-D model display are populated.
 * All other opcodes are parsed but discarded during decoding.
 */
public final class ObjectDefinition {

    /** Definition ID — corresponds to the file ID within config archive 5. */
    public final int id;

    /** Display name of the object, default "null" when absent. */
    public String name = "null";

    /**
     * Model IDs that compose this object's in-world model.
     * Parallel to objectTypes when loaded via opcode 1.
     * May be null.
     */
    public int[] objectModels;

    /**
     * Model type values parallel to objectModels.
     * Only present when loaded via opcode 1 (opcode 5 leaves this null).
     * May be null.
     */
    public int[] objectTypes;

    /** HSL colour values to search for when recolouring. May be null. */
    public short[] recolorFind;

    /** HSL replacement colour values, parallel array to recolorFind. May be null. */
    public short[] recolorReplace;

    /** Texture IDs to search for when retexturing. May be null. */
    public short[] retextureFind;

    /** Replacement texture IDs, parallel array to retextureFind. May be null. */
    public short[] retextureReplace;

    /**
     * X-axis scale factor (128 = 1.0×, i.e. no scaling).
     */
    public int scaleX = 128;

    /**
     * Y-axis (vertical) scale factor (128 = 1.0×, i.e. no scaling).
     */
    public int scaleY = 128;

    /**
     * Z-axis scale factor (128 = 1.0×, i.e. no scaling).
     */
    public int scaleZ = 128;

    public ObjectDefinition(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "obj_" + id + (!"null".equals(name) ? " (" + name + ")" : "");
    }
}
