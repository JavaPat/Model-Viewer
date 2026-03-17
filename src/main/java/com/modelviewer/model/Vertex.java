package com.modelviewer.model;

/**
 * A single 3-D vertex in model space.
 *
 * OSRS stores vertex coordinates as signed integers in units of 1/128th of a
 * game tile (1 tile = 128 units).  We keep them as ints internally and only
 * convert to floats when uploading to the GPU.
 */
public final class Vertex {

    /** Coordinates in OSRS integer units. */
    public final int x;
    public final int y;
    public final int z;

    public Vertex(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String toString() {
        return "Vertex(" + x + ", " + y + ", " + z + ")";
    }
}
