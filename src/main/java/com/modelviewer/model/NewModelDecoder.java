package com.modelviewer.model;

import com.modelviewer.util.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes OSRS "new" model files — the {@code [0xFF, 0xFD]} sentinel variant.
 */
final class NewModelDecoder {

    private static final Logger log = LoggerFactory.getLogger(NewModelDecoder.class);

    public ModelMesh decode(int modelId, byte[] data) {
        try {
            return decodeNewFormat(modelId, data);
        } catch (Exception e) {
            log.debug("NewModelDecoder failed for model {}: {}", modelId, e.getMessage());
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
        int vertexFlagsOff  = pos;  pos += vertexCount;
        int faceCompressOff = pos;  pos += faceCount;
        int facePriorityOff = pos;  pos += (globalPriority == 0xFF) ? faceCount : 0;
        int faceSkinOff     = pos;  pos += (hasFaceSkins != 0) ? faceCount : 0;
        int faceTypeOff     = pos;  pos += (flags & 1) != 0 ? faceCount : 0;
        int vertexSkinOff   = pos;  pos += (hasVertexSkins != 0) ? vertexCount : 0;
        int faceAlphaOff    = pos;  pos += (hasFaceAlpha != 0) ? faceCount : 0;
        int faceIndexOff    = pos;  pos += faceIndexLen;
        int faceTextureOff  = pos;  pos += (hasFaceTextures != 0) ? faceCount : 0;
        int texCoordOff     = pos;  pos += texCoordLen;
        int faceColorOff    = pos;  pos += faceCount * 2;
        int texFaceOff      = pos;  pos += texFaceCount * 6;
        int vertexXOff      = pos;  pos += xLen;
        int vertexYOff      = pos;  pos += yLen;
        int vertexZOff      = pos;  pos += zLen;

        if (faceIndexOff >= data.length) {
            throw new IllegalStateException("faceIndexOff out of bounds: " + faceIndexOff);
        }
        if (faceIndexOff + faceIndexLen > data.length) {
            throw new IllegalStateException("faceIndexOff+len out of bounds: " + faceIndexOff + "+" + faceIndexLen);
        }
        if (vertexZOff > data.length - 23) {
            log.warn("Model {} new-format FD: section layout ({} bytes) overflows usable data ({} bytes) "
                   + "— vertexCount={} faceCount={} xLen={} yLen={} faceIndexLen={}",
                    modelId, vertexZOff, data.length - 23,
                    vertexCount, faceCount, xLen, yLen, faceIndexLen);
            return null;
        }

        // ── Vertex positions (delta-encoded signed smarts) ────────────────────
        int[] vx = new int[vertexCount];
        int[] vy = new int[vertexCount];
        int[] vz = new int[vertexCount];
        ModelDecoder.readVertices(data, vertexFlagsOff, vertexXOff, vertexYOff, vertexZOff,
                vertexCount, vx, vy, vz);

        // ── Vertex skins ──────────────────────────────────────────────────────
        int[] vertexSkins = null;
        if (hasVertexSkins != 0) {
            vertexSkins = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                vertexSkins[i] = data[vertexSkinOff + i] & 0xFF;
            }
        }

        // ── Face colours ──────────────────────────────────────────────────────
        short[] faceColors = new short[faceCount];
        Buffer colorBuf = new Buffer(data);
        colorBuf.offset = faceColorOff;
        for (int i = 0; i < faceCount; i++) {
            faceColors[i] = (short) colorBuf.readUnsignedShort();
        }

        // ── Per-face render types (optional) ──────────────────────────────────
        int[] faceTypes = null;
        if ((flags & 1) != 0) {
            faceTypes = new int[faceCount];
            Buffer typeBuf = new Buffer(data);
            typeBuf.offset = faceTypeOff;
            for (int i = 0; i < faceCount; i++) {
                faceTypes[i] = typeBuf.readUnsignedByte();
            }
        }

        // ── Per-face alphas (optional) ────────────────────────────────────────
        int[] faceAlphas = null;
        if (hasFaceAlpha != 0) {
            faceAlphas = new int[faceCount];
            Buffer alphaBuf = new Buffer(data);
            alphaBuf.offset = faceAlphaOff;
            for (int i = 0; i < faceCount; i++) {
                faceAlphas[i] = alphaBuf.readUnsignedByte();
            }
        }

        // ── Texture triangles (defines UV reference frames) ───────────────────
        int[] faceTextureIds = null;
        if (hasFaceTextures != 0) {
            faceTextureIds = new int[faceCount];
            Buffer faceTextureBuf = new Buffer(data);
            faceTextureBuf.offset = faceTextureOff;
            for (int i = 0; i < faceCount; i++) {
                int textureId = faceTextureBuf.readUnsignedByte();
                faceTextureIds[i] = textureId == 255 ? -1 : textureId;
            }
        }

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

        // Present in the header for FF FD models; reserved here so later sections align.
        int ignoredTexCoordOff = texCoordOff;
        if (ignoredTexCoordOff < 0) {
            throw new IllegalStateException("Invalid texture coordinate offset");
        }

        int[] fa = new int[faceCount];
        int[] fb = new int[faceCount];
        int[] fc = new int[faceCount];
        ModelDecoder.readFaceIndices(data, faceCompressOff, faceIndexOff, faceCount, fa, fb, fc);
        ModelDecoder.validateFaceIndices(vertexCount, fa, fb, fc);

        return new ModelMesh(modelId, vx, vy, vz, fa, fb, fc,
                faceColors, faceTypes, faceAlphas, faceTextureIds, vertexSkins,
                texFaceP, texFaceQ, texFaceR);
    }
}
