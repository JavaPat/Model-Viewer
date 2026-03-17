package com.modelviewer.animation;

/**
 * An OSRS animation sequence (SeqType), decoded from index 2 archive 9.
 *
 * A sequence lists ordered frame references with per-frame delays.
 * Each frameId is packed: upper 16 bits = framemap archive ID,
 * lower 16 bits = frame file ID within that archive.
 */
public final class SeqDefinition {
    public final int id;
    public int[] frameIds;       // packed (framemapId << 16 | frameIndex)
    public int[] frameDurations; // delay per frame in game ticks (1 tick = 600 ms)
    public int   loopOffset = -1; // frame index to loop back to, -1 = no loop (play once then freeze)
    public int   frameCount = 0;

    public SeqDefinition(int id) { this.id = id; }
}
