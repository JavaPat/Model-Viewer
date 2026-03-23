package com.modelviewer.model;

import com.modelviewer.util.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelDecoderTest {

    @Test
    void readSmartMatchesOsrsEncoding() {
        Buffer oneByte = new Buffer(new byte[] { 127 });
        assertEquals(127, oneByte.readUnsignedSmart());

        Buffer twoByte = new Buffer(new byte[] { (byte) 0x80, (byte) 0xC8 });
        assertEquals(200, twoByte.readUnsignedSmart());
    }

    @Test
    void readFaceIndicesReconstructsAllStripTypes() {
        byte[] data = {
                1, 2, 3, 4, // face strip/compression types
                1, 2, 3,    // type 1 smart deltas: 1, 2, 3
                4,          // type 2 delta: 4
                5,          // type 3 delta: 5
                6           // type 4 delta: 6
        };

        int[] fa = new int[4];
        int[] fb = new int[4];
        int[] fc = new int[4];

        ModelDecoder.readFaceIndices(data, 0, 4, 4, fa, fb, fc);

        assertArrayEquals(new int[] {1, 1, 10, 6}, fa);
        assertArrayEquals(new int[] {3, 6, 6, 10}, fb);
        assertArrayEquals(new int[] {6, 10, 15, 21}, fc);
    }
}
