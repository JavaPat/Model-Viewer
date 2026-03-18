package com.modelviewer.model;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LegacyModelDecoder {

    private static final Logger log = LoggerFactory.getLogger(LegacyModelDecoder.class);

    public ModelMesh decode(int modelId, byte[] data) {
        try {
            return decodeOldFormat(modelId, data);
        } catch (Exception e) {
            log.warn("Decode error for model {}: {}", modelId, e.getMessage());
            return buildPlaceholder(modelId, data);
        }
    }

    private static ModelMesh decodeOldFormat(int modelId, byte[] data) {
        Buffer header = new Buffer(data);
        header.offset = data.length - 18;

        int vertexCount    = header.readUnsignedShort();
        int faceCount      = header.readUnsignedShort();
        int texFaceCount   = header.readUnsignedByte();
        int flags          = header.readUnsignedByte();
        int globalPriority = header.readUnsignedByte();
        int hasFaceAlpha   = header.readUnsignedByte();
        int hasFaceSkins   = header.readUnsignedByte();
        int hasVertexSkins = header.readUnsignedByte();
        int xLen           = header.readUnsignedShort();
        int yLen           = header.readUnsignedShort();
        int zLen           = header.readUnsignedShort();
        int faceIndexLen   = header.readUnsignedShort();

        System.out.println("Model " + modelId +
                " v=" + vertexCount +
                " f=" + faceCount +
                " idxLen=" + faceIndexLen);

        if (vertexCount <= 0 || vertexCount > 100000 || faceCount <= 0 || faceCount > 100000) {
            throw new IllegalStateException("Invalid header counts v=" + vertexCount + " f=" + faceCount);
        }

        int pos = 0;

        int vertexFlagsOff  = pos; pos += vertexCount;
        int faceCompressOff = pos; pos += faceCount;

        int facePriorityOff = pos;
        if (globalPriority == 0xFF) pos += faceCount;

        int faceAlphaOff = pos;
        if (hasFaceAlpha != 0) pos += faceCount;

        int faceSkinOff = pos;
        if (hasFaceSkins != 0) pos += faceCount;

        int faceTypeOff = pos;
        if ((flags & 1) != 0) pos += faceCount;

        int vertexSkinOff = pos;
        if (hasVertexSkins != 0) pos += vertexCount;

        int faceIndexOff = pos; pos += faceIndexLen;
        int faceColorOff = pos; pos += faceCount * 2;
        int texFaceOff   = pos; pos += texFaceCount * 6;

        int vertexXOff = pos; pos += xLen;
        int vertexYOff = pos; pos += yLen;
        int vertexZOff = pos;

        System.out.println(
                "Model " + modelId +
                " layout: v=" + vertexCount +
                " f=" + faceCount +
                " idxOff=" + faceIndexOff +
                " colOff=" + faceColorOff +
                " xOff=" + vertexXOff
        );

        int dataLimit = data.length - 18;
        if (faceIndexOff > dataLimit || faceColorOff > dataLimit || vertexXOff > dataLimit) {
            return null;
        }

        // ── Vertices ─────────────────────────────
        int[] vx = new int[vertexCount];
        int[] vy = new int[vertexCount];
        int[] vz = new int[vertexCount];

        ModelDecoder.readVertices(data, vertexFlagsOff, vertexXOff, vertexYOff, vertexZOff,
                vertexCount, vx, vy, vz);

        // ── Vertex skins ─────────────────────────
        int[] vertexSkins = null;
        if (hasVertexSkins != 0) {
            vertexSkins = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                vertexSkins[i] = data[vertexSkinOff + i] & 0xFF;
            }
        }

        // ── Face colors ──────────────────────────
        short[] faceColors = new short[faceCount];
        Buffer colorBuf = new Buffer(data);
        colorBuf.offset = faceColorOff;
        for (int i = 0; i < faceCount; i++) {
            faceColors[i] = (short) colorBuf.readUnsignedShort();
        }

        // ── Face types (optional) ────────────────
        int[] faceTypes = null;
        if ((flags & 1) != 0) {
            faceTypes = new int[faceCount];
            Buffer typeBuf = new Buffer(data);
            typeBuf.offset = faceTypeOff;
            for (int i = 0; i < faceCount; i++) {
                faceTypes[i] = typeBuf.readUnsignedByte();
            }
        }

        // ── Face alphas ──────────────────────────
        int[] faceAlphas = null;
        if (hasFaceAlpha != 0) {
            faceAlphas = new int[faceCount];
            Buffer alphaBuf = new Buffer(data);
            alphaBuf.offset = faceAlphaOff;
            for (int i = 0; i < faceCount; i++) {
                faceAlphas[i] = alphaBuf.readUnsignedByte();
            }
        }

        // ── Texture triangles ────────────────────
        int[] texFaceP = null;
        int[] texFaceQ = null;
        int[] texFaceR = null;

        if (texFaceCount > 0) {
            texFaceP = new int[texFaceCount];
            texFaceQ = new int[texFaceCount];
            texFaceR = new int[texFaceCount];

            Buffer texBuf = new Buffer(data);
            texBuf.offset = texFaceOff;

            for (int i = 0; i < texFaceCount; i++) {
                texFaceP[i] = texBuf.readUnsignedShort();
                texFaceQ[i] = texBuf.readUnsignedShort();
                texFaceR[i] = texBuf.readUnsignedShort();
            }
        }

        // ── Face indices ─────────────────────────
        int[] fa = new int[faceCount];
        int[] fb = new int[faceCount];
        int[] fc = new int[faceCount];

        ModelDecoder.readFaceIndices(data, faceCompressOff, faceIndexOff, faceCount, fa, fb, fc);

        return new ModelMesh(modelId, vx, vy, vz,
                fa, fb, fc,
                faceColors, faceTypes, faceAlphas,
                null, vertexSkins,
                texFaceP, texFaceQ, texFaceR);
    }

    private static ModelMesh buildPlaceholder(int modelId, byte[] data) {
        try {
            Buffer header = new Buffer(data);
            header.offset = data.length - 18;
            int vertexCount = header.readUnsignedShort();
            int faceCount = header.readUnsignedShort();
            header.readUnsignedByte(); // texFaceCount
            header.readUnsignedByte(); // flags
            header.readUnsignedByte(); // globalPriority
            header.readUnsignedByte(); // hasFaceAlpha
            header.readUnsignedByte(); // hasFaceSkins
            header.readUnsignedByte(); // hasVertexSkins
            header.readUnsignedShort(); // xLen
            header.readUnsignedShort(); // yLen
            header.readUnsignedShort(); // zLen
            header.readUnsignedShort(); // faceIndexLen

            if (vertexCount <= 0 || faceCount <= 0) {
                return null;
            }

            int[] vx = new int[vertexCount];
            int[] vy = new int[vertexCount];
            int[] vz = new int[vertexCount];
            int[] fa = new int[faceCount];
            int[] fb = new int[faceCount];
            int[] fc = new int[faceCount];
            short[] faceColors = new short[faceCount];
            return new ModelMesh(modelId, vx, vy, vz, fa, fb, fc,
                    faceColors, null, null, null, null,
                    null, null, null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
