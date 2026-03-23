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

        if (vertexCount <= 0 || vertexCount > 100000 || faceCount <= 0 || faceCount > 100000) {
            throw new IllegalStateException("Invalid header counts v=" + vertexCount + " f=" + faceCount);
        }

        int pos = 0;

        // FF FE (v254) authoritative section order from RuneLite ModelLoader.decodeType2:
        // vertexFlags → faceCompress → [facePriority] → [packedTranspGroups/faceSkin]
        // → [faceTextureFlags/isTextured] → texCoord → [faceAlpha] → faceIndex → faceColors
        // → texFace → vertexX → vertexY → vertexZ
        int vertexFlagsOff  = pos; pos += vertexCount;
        int faceCompressOff = pos; pos += faceCount;
        int facePriorityOff = pos; pos += (globalPriority == 255) ? faceCount : 0;
        int faceSkinOff     = pos; pos += (hasFaceSkins != 0) ? faceCount : 0;
        int faceTypeOff     = pos; pos += (flags & 1) != 0 ? faceCount : 0;
        int texCoordOff     = pos; pos += texCoordLen;
        int faceAlphaOff    = pos; pos += (hasFaceAlpha != 0) ? faceCount : 0;
        int faceIndexOff    = pos; pos += faceIndexLen;
        int faceColorOff    = pos; pos += faceCount * 2;
        int texFaceOff      = pos; pos += texFaceCount * 6;

        int vertexXOff      = pos; pos += xLen;
        int vertexYOff      = pos; pos += yLen;
        int vertexZOff      = pos; pos += zLen;

        if (pos > data.length - 23) {
            log.warn("Model {} bounds overflow: len={} v={} f={} tf={} flags={} pri={} alpha={} faceSkin={} tex={} vSkin={} x={} y={} z={} idx={} coord={} => sectionTotal={} limit={}",
                modelId, data.length, vertexCount, faceCount, texFaceCount, flags, globalPriority,
                hasFaceAlpha, hasFaceSkins, hasFaceTextures, hasVertexSkins,
                xLen, yLen, zLen, faceIndexLen, texCoordLen, pos, data.length - 23);
            return null;
        }

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

        // ── FACE TYPES ──────────────────────────────────────────
        int[] faceTypes = null;
        if ((flags & 1) != 0) {
            faceTypes = new int[faceCount];
            Buffer typeBuf = new Buffer(data);
            typeBuf.offset = faceTypeOff;
            for (int i = 0; i < faceCount; i++) {
                faceTypes[i] = typeBuf.readUnsignedByte();
            }
        }

        // ── FACE ALPHAS ─────────────────────────────────────────
        int[] faceAlphas = null;
        if (hasFaceAlpha != 0) {
            faceAlphas = new int[faceCount];
            Buffer alphaBuf = new Buffer(data);
            alphaBuf.offset = faceAlphaOff;
            for (int i = 0; i < faceCount; i++) {
                faceAlphas[i] = alphaBuf.readUnsignedByte();
            }
        }

        // ── FACE TEXTURES ────────────────────────────────────────
        // FF FE encodes texture refs within texCoordLen — not a simple faceCount-byte section.
        // Leave as null; the viewer uses HSL face colors for rendering.
        int[] faceTextureIds = null;

        // ── VERTEX SKINS ─────────────────────────────────────────
        // hasVertexSkins (var17) = animaya groups — not needed for static rendering.
        int[] vertexSkins = null;

        // ── TEXTURE TRIANGLES ────────────────────────────────────
        int[] texFaceP = null;
        int[] texFaceQ = null;
        int[] texFaceR = null;

        if (texFaceCount > 0) {
            texFaceP = new int[texFaceCount];
            texFaceQ = new int[texFaceCount];
            texFaceR = new int[texFaceCount];

            Buffer texFaceBuf = new Buffer(data);
            texFaceBuf.offset = texFaceOff;

            for (int i = 0; i < texFaceCount; i++) {
                texFaceP[i] = texFaceBuf.readUnsignedShort();
                texFaceQ[i] = texFaceBuf.readUnsignedShort();
                texFaceR[i] = texFaceBuf.readUnsignedShort();
            }
        }

        // ── FACE INDICES ─────────────────────────────────────────
        int[] fa = new int[faceCount];
        int[] fb = new int[faceCount];
        int[] fc = new int[faceCount];

        readFaceIndices(data, faceCompressOff, faceIndexOff, faceCount, fa, fb, fc);

        return new ModelMesh(
                modelId,
                vx, vy, vz,
                fa, fb, fc,
                faceColors,
                faceTypes,
                faceAlphas,
                faceTextureIds,
                vertexSkins,
                texFaceP, texFaceQ, texFaceR
        );
    }

    /**
     * Face index decoder for the FF FE modern format.
     * Deltas are signed smarts (readSignedSmart): delta=1 is stored as 0x41 (64+1).
     */
    private static void readFaceIndices(byte[] data,
                                        int compressOff, int indexOff,
                                        int faceCount,
                                        int[] fa, int[] fb, int[] fc) {

        Buffer comp = new Buffer(data); comp.offset = compressOff;
        Buffer idx  = new Buffer(data); idx.offset  = indexOff;

        int a = 0, b = 0, c = 0;
        int last = 0;

        for (int i = 0; i < faceCount; i++) {
            int type = comp.readUnsignedByte() & 7;

            if (type == 1) {
                a = idx.readSignedSmart() + last;
                b = idx.readSignedSmart() + a;
                c = idx.readSignedSmart() + b;
                last = c;
            } else if (type == 2) {
                b = c;
                c = idx.readSignedSmart() + last;
                last = c;
            } else if (type == 3) {
                a = c;
                c = idx.readSignedSmart() + last;
                last = c;
            } else if (type == 4) {
                int tmp = a; a = b; b = tmp;
                c = idx.readSignedSmart() + last;
                last = c;
            }

            fa[i] = a;
            fb[i] = b;
            fc[i] = c;
        }

        log.info("readFaceIndices sample: ({},{},{}) ({},{},{}) ({},{},{})",
                fa[0], fb[0], fc[0],
                faceCount > 1 ? fa[1] : -1, faceCount > 1 ? fb[1] : -1, faceCount > 1 ? fc[1] : -1,
                faceCount > 2 ? fa[2] : -1, faceCount > 2 ? fb[2] : -1, faceCount > 2 ? fc[2] : -1);
    }
}
