package com.modelviewer.model;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModernModelDecoder implements IModelDecoder {

    private static final Logger log = LoggerFactory.getLogger(ModernModelDecoder.class);

    @Override
    public boolean supports(byte[] data) {
        if (data == null || data.length < 2) return false;
        return (data[data.length - 2] & 0xFF) == 0xFF
                && (data[data.length - 1] & 0xFF) == 0xFE;
    }

    @Override
    public ModelMesh decode(int modelId, byte[] data) {
        try {
            return decodeNewFormat(modelId, data);
        } catch (Exception e) {
            log.warn("Decode error for model {}: {}", modelId, e.getMessage());
            return buildPlaceholder(modelId, data);
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

        System.out.println(
                "flags=" + flags +
                " priority=" + globalPriority +
                " hasAlpha=" + hasFaceAlpha +
                " hasFaceSkins=" + hasFaceSkins +
                " hasVertexSkins=" + hasVertexSkins
        );
        System.out.println(
                "Model " + modelId +
                " v=" + vertexCount +
                " f=" + faceCount +
                " idxLen=" + faceIndexLen +
                " dataLen=" + data.length
        );

        if (vertexCount <= 0 || faceCount <= 0) {
            throw new IllegalStateException("Invalid counts");
        }

        int pos = 0;
        int vertexFlagsOff  = pos;  pos += vertexCount;

        int faceTypeOff     = pos;  pos += faceCount;
        int facePriorityOff = pos;  pos += (globalPriority == 0xFF) ? faceCount : 0;
        int faceAlphaOff    = pos;  pos += (hasFaceAlpha   != 0)   ? faceCount : 0;
        int faceSkinOff     = pos;  pos += (hasFaceSkins   != 0)   ? faceCount : 0;
        int vertexSkinOff   = pos;  pos += (hasVertexSkins != 0)   ? vertexCount : 0;

        int faceIndexOff = pos;  pos += faceIndexLen;
        int faceColorOff = pos;  pos += faceCount * 2;
        int texFaceOff   = pos;  pos += texFaceCount * 6;
        int vertexXOff   = pos;  pos += xLen;
        int vertexYOff   = pos;  pos += yLen;
        int vertexZOff   = pos;  pos += zLen;

        if (faceIndexOff + faceIndexLen > data.length) {
            throw new RuntimeException("Index section overflow");
        }

        if (pos > data.length) {
            throw new RuntimeException("Layout overflow");
        }

        // ── Vertices ─────────────────
        int[] vx = new int[vertexCount];
        int[] vy = new int[vertexCount];
        int[] vz = new int[vertexCount];

        ModelDecoder.readVertices(data,
                vertexFlagsOff,
                vertexXOff,
                vertexYOff,
                vertexZOff,
                vertexCount,
                vx, vy, vz);

        // ── Face colors ──────────────
        short[] faceColors = new short[faceCount];
        Buffer colorBuf = new Buffer(data);
        colorBuf.offset = faceColorOff;

        for (int i = 0; i < faceCount; i++) {
            faceColors[i] = (short) colorBuf.readUnsignedShort();
        }

        // ── Face indices ─────────────────────────
        int[] fa = new int[faceCount];
        int[] fb = new int[faceCount];
        int[] fc = new int[faceCount];

        Buffer compBuf = new Buffer(data);
        compBuf.offset = faceTypeOff;

        Buffer idxBuf = new Buffer(data);
        idxBuf.offset = faceIndexOff;

        int a = 0, b = 0, c = 0, last = 0;
        for (int i = 0; i < faceCount; i++) {
            int type = compBuf.readUnsignedByte() & 7;
            switch (type) {
                case 1 -> {
                    a = idxBuf.readUnsignedSmart() + last; last = a;
                    b = idxBuf.readUnsignedSmart() + last; last = b;
                    c = idxBuf.readUnsignedSmart() + last; last = c;
                }
                case 2 -> {
                    a = b;
                    b = c;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                case 3 -> {
                    b = a;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                case 4 -> {
                    int t = a;
                    a = b;
                    b = t;
                    c = idxBuf.readUnsignedSmart() + last;
                    last = c;
                }
                default -> throw new IllegalStateException("Invalid face type: " + type);
            }

            if (a < 0 || a >= vertexCount ||
                    b < 0 || b >= vertexCount ||
                    c < 0 || c >= vertexCount) {
                throw new IllegalStateException(
                        "Invalid indices (" + a + "," + b + "," + c + ")");
            }

            fa[i] = a;
            fb[i] = b;
            fc[i] = c;
        }

        return new ModelMesh(modelId,
                vx, vy, vz,
                fa, fb, fc,
                faceColors,
                null, null, null, null,
                null, null, null);
    }

    private static ModelMesh buildPlaceholder(int modelId, byte[] data) {
        return null;
    }

}
