package com.modelviewer.model;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModernModelDecoder {

    private static final Logger log = LoggerFactory.getLogger(ModernModelDecoder.class);

    public ModelMesh decode(int modelId, byte[] data) {
        try {
            return decodeNewFormat(modelId, data);
        } catch (Exception e) {
            log.warn("Decode error for model {}: {}", modelId, e.getMessage());
            return null;
        }
    }

    private static ModelMesh decodeNewFormat(int modelId, byte[] data) {
        Buffer header = new Buffer(data);
        header.offset = data.length - 23;

        int vertexCount      = header.readUnsignedShort();
        int faceCount        = header.readUnsignedShort();
        int texFaceCount     = header.readUnsignedByte();
        int flags            = header.readUnsignedByte();
        int globalPriority   = header.readUnsignedByte();
        int hasFaceAlpha     = header.readUnsignedByte();
        int hasFaceSkins     = header.readUnsignedByte();
        int hasFaceTextures  = header.readUnsignedByte();
        int hasVertexSkins   = header.readUnsignedByte();
        int xLen             = header.readUnsignedShort();
        int yLen             = header.readUnsignedShort();
        int zLen             = header.readUnsignedShort();
        int faceIndexLen     = header.readUnsignedShort();
        int texCoordLen      = header.readUnsignedShort();

        int pos = 0;

        // ── CORRECT ORDER (THIS WAS YOUR BUG) ───────────────────

        int vertexFlagsOff = pos; pos += vertexCount;

        int vertexXOff = pos; pos += xLen;
        int vertexYOff = pos; pos += yLen;
        int vertexZOff = pos; pos += zLen;

        int faceCompressOff = pos; pos += faceCount;

        int facePriorityOff = pos; pos += (globalPriority == 255) ? faceCount : 0;
        int faceSkinOff     = pos; pos += (hasFaceSkins != 0) ? faceCount : 0;
        int faceTypeOff     = pos; pos += (flags & 1) != 0 ? faceCount : 0;
        int vertexSkinOff   = pos; pos += (hasVertexSkins != 0) ? vertexCount : 0;
        int faceAlphaOff    = pos; pos += (hasFaceAlpha != 0) ? faceCount : 0;

        int faceIndexOff = pos; pos += faceIndexLen;

        int faceTextureOff = pos; pos += (hasFaceTextures != 0) ? faceCount : 0;
        int texCoordOff    = pos; pos += texCoordLen;

        int faceColorOff = pos; pos += faceCount * 2;
        int texFaceOff   = pos; pos += texFaceCount * 6;

        // ── VERTICES ────────────────────────────────────────────
        int[] vx = new int[vertexCount];
        int[] vy = new int[vertexCount];
        int[] vz = new int[vertexCount];

        ModelDecoder.readVertices(
                data,
                vertexFlagsOff,
                vertexXOff,
                vertexYOff,
                vertexZOff,
                vertexCount,
                vx, vy, vz
        );

        // ── FACE COLORS ─────────────────────────────────────────
        short[] faceColors = new short[faceCount];
        Buffer colorBuf = new Buffer(data);
        colorBuf.offset = faceColorOff;

        for (int i = 0; i < faceCount; i++) {
            faceColors[i] = (short) colorBuf.readUnsignedShort();
        }

        // ── FACE INDICES ────────────────────────────────────────
        int[] fa = new int[faceCount];
        int[] fb = new int[faceCount];
        int[] fc = new int[faceCount];

        readFaceIndicesMinusOne(
                data,
                faceCompressOff,
                faceIndexOff,
                faceCount,
                fa, fb, fc
        );

        return new ModelMesh(
                modelId,
                vx, vy, vz,
                fa, fb, fc,
                faceColors,
                null, null, null, null,
                null, null, null
        );
    }

    private static void readFaceIndicesMinusOne(byte[] data,
                                                int compressOff, int indexOff,
                                                int faceCount,
                                                int[] fa, int[] fb, int[] fc) {

        Buffer compBuf = new Buffer(data);
        compBuf.offset = compressOff;

        Buffer idxBuf = new Buffer(data);
        idxBuf.offset = indexOff;

        int a = 0, b = 0, c = 0;
        int last = 0;

        for (int i = 0; i < faceCount; i++) {
            int type = compBuf.readUnsignedByte();

            if (type == 1) {
                a = last + idxBuf.readUnsignedSmart() - 1;
                b = a + idxBuf.readUnsignedSmart() - 1;
                c = b + idxBuf.readUnsignedSmart() - 1;
                last = c;
            }
            else if (type == 2) {
                b = c;
                c = last + idxBuf.readUnsignedSmart() - 1;
                last = c;
            }
            else if (type == 3) {
                a = c;
                c = last + idxBuf.readUnsignedSmart() - 1;
                last = c;
            }
            else if (type == 4) {
                int tmp = a;
                a = b;
                b = tmp;
                c = last + idxBuf.readUnsignedSmart() - 1;
                last = c;
            }

            fa[i] = a;
            fb[i] = b;
            fc[i] = c;
        }
    }

}
