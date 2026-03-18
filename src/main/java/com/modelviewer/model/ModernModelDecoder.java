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
            return buildPlaceholder(modelId, data);
        }
    }

    private static ModelMesh decodeNewFormat(int modelId, byte[] data) {
        RuntimeException last = null;
        ModelMesh bestMesh = null;
        int bestPrefix = -1;
        for (boolean reservePriority : new boolean[]{true, false}) {
            for (boolean reserveFaceTextureIds : new boolean[]{true, false}) {
                for (boolean subtractOneFromFaceDeltas : new boolean[]{false, true}) {
                    try {
                        DecodeAttempt attempt = decodeNewFormat(modelId, data, reservePriority, reserveFaceTextureIds,
                                subtractOneFromFaceDeltas);
                        if (attempt.validPrefix == attempt.mesh.faceCount) {
                            return attempt.mesh;
                        }
                        if (attempt.validPrefix > bestPrefix) {
                            bestPrefix = attempt.validPrefix;
                            bestMesh = attempt.mesh;
                        }
                    } catch (RuntimeException ex) {
                        last = ex;
                    }
                }
            }
        }

        if (bestMesh != null && bestPrefix > 0) {
            clampFaceIndices(bestMesh);
            log.warn("Model {} modern decode used best-effort layout ({} / {} faces valid before clamping)",
                    modelId, bestPrefix, bestMesh.faceCount);
            return bestMesh;
        }

        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("Modern decode failed");
    }

    private static DecodeAttempt decodeNewFormat(int modelId, byte[] data, boolean reservePriority,
                                                 boolean reserveFaceTextureIds,
                                                 boolean subtractOneFromFaceDeltas) {
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

        if (vertexCount <= 0 || faceCount <= 0) {
            throw new IllegalStateException("Invalid counts");
        }

        int pos = 0;
        int vertexFlagsOff  = pos;  pos += vertexCount;
        int faceCompressOff = pos;  pos += faceCount;
        int facePriorityOff = pos;  pos += (reservePriority && globalPriority == 0xFF) ? faceCount : 0;
        int faceSkinOff     = pos;  pos += (hasFaceSkins != 0) ? faceCount : 0;
        int faceTypeOff     = pos;  pos += (flags & 1) != 0 ? faceCount : 0;
        int vertexSkinOff   = pos;  pos += (hasVertexSkins != 0) ? vertexCount : 0;
        int faceAlphaOff    = pos;  pos += (hasFaceAlpha != 0) ? faceCount : 0;
        int faceIndexOff    = pos;  pos += faceIndexLen;
        int faceTextureOff  = pos;  pos += (reserveFaceTextureIds && hasFaceTextures != 0) ? faceCount : 0;
        int texCoordOff     = pos;  pos += texCoordLen;
        int faceColorOff    = pos;  pos += faceCount * 2;
        int texFaceOff      = pos;  pos += texFaceCount * 6;
        int vertexXOff      = pos;  pos += xLen;
        int vertexYOff      = pos;  pos += yLen;
        int vertexZOff      = pos;  pos += zLen;

        int dataLimit = data.length - 23;
        if (faceIndexOff + faceIndexLen > dataLimit) {
            throw new RuntimeException("Index section overflow");
        }

        if (pos > dataLimit) {
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

        int[] faceTypes = null;
        if ((flags & 1) != 0) {
            faceTypes = new int[faceCount];
            Buffer typeBuf = new Buffer(data);
            typeBuf.offset = faceTypeOff;
            for (int i = 0; i < faceCount; i++) {
                faceTypes[i] = typeBuf.readUnsignedByte();
            }
        }

        int[] faceAlphas = null;
        if (hasFaceAlpha != 0) {
            faceAlphas = new int[faceCount];
            Buffer alphaBuf = new Buffer(data);
            alphaBuf.offset = faceAlphaOff;
            for (int i = 0; i < faceCount; i++) {
                faceAlphas[i] = alphaBuf.readUnsignedByte();
            }
        }

        int[] vertexSkins = null;
        if (hasVertexSkins != 0) {
            vertexSkins = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                vertexSkins[i] = data[vertexSkinOff + i] & 0xFF;
            }
        }

        int[] faceTextureIds = null;
        if (reserveFaceTextureIds && hasFaceTextures != 0) {
            faceTextureIds = new int[faceCount];
            Buffer textureBuf = new Buffer(data);
            textureBuf.offset = faceTextureOff;
            for (int i = 0; i < faceCount; i++) {
                int textureId = textureBuf.readUnsignedByte();
                faceTextureIds[i] = textureId == 255 ? -1 : textureId;
            }
        }

        // Present in the header for FF FE models; reserved here so later sections align.
        int ignoredTexCoordOff = texCoordOff;
        if (ignoredTexCoordOff < 0) {
            throw new IllegalStateException("Invalid texture coordinate offset");
        }

        // ── Face indices ─────────────────────────
        int[] fa = new int[faceCount];
        int[] fb = new int[faceCount];
        int[] fc = new int[faceCount];
        if (subtractOneFromFaceDeltas) {
            readFaceIndicesMinusOne(data, faceCompressOff, faceIndexOff, faceCount, fa, fb, fc);
        } else {
            ModelDecoder.readFaceIndices(data, faceCompressOff, faceIndexOff, faceCount, fa, fb, fc);
        }
        int validPrefix = validFacePrefix(vertexCount, fa, fb, fc);

        ModelMesh mesh = new ModelMesh(modelId,
                vx, vy, vz,
                fa, fb, fc,
                faceColors,
                faceTypes, faceAlphas, faceTextureIds, vertexSkins,
                null, null, null);
        return new DecodeAttempt(mesh, validPrefix);
    }

    private static ModelMesh buildPlaceholder(int modelId, byte[] data) {
        return null;
    }

    private static int validFacePrefix(int vertexCount, int[] fa, int[] fb, int[] fc) {
        for (int i = 0; i < fa.length; i++) {
            int a = fa[i];
            int b = fb[i];
            int c = fc[i];
            if (a < 0 || a >= vertexCount || b < 0 || b >= vertexCount || c < 0 || c >= vertexCount) {
                return i;
            }
        }
        return fa.length;
    }

    private static void clampFaceIndices(ModelMesh mesh) {
        int maxIndex = mesh.vertexCount - 1;
        for (int i = 0; i < mesh.faceCount; i++) {
            mesh.faceVertexA[i] = clamp(mesh.faceVertexA[i], maxIndex);
            mesh.faceVertexB[i] = clamp(mesh.faceVertexB[i], maxIndex);
            mesh.faceVertexC[i] = clamp(mesh.faceVertexC[i], maxIndex);
        }
    }

    private static int clamp(int value, int maxIndex) {
        if (value < 0) {
            return 0;
        }
        if (value > maxIndex) {
            return maxIndex;
        }
        return value;
    }

    private static void readFaceIndicesMinusOne(byte[] data,
                                                int compressOff, int indexOff,
                                                int faceCount,
                                                int[] fa, int[] fb, int[] fc) {
        Buffer compBuf = new Buffer(data);
        compBuf.offset = compressOff;
        Buffer idxBuf = new Buffer(data);
        idxBuf.offset = indexOff;

        int a = 0;
        int b = 0;
        int c = 0;
        int last = 0;
        for (int i = 0; i < faceCount; i++) {
            int type = compBuf.readUnsignedByte() & 7;
            switch (type) {
                case 1 -> {
                    a = last + idxBuf.readSmart() - 1;
                    b = a + idxBuf.readSmart() - 1;
                    c = b + idxBuf.readSmart() - 1;
                    last = c;
                }
                case 2 -> {
                    b = c;
                    c = last + idxBuf.readSmart() - 1;
                    last = c;
                }
                case 3 -> {
                    a = c;
                    c = last + idxBuf.readSmart() - 1;
                    last = c;
                }
                case 4 -> {
                    int tmp = a;
                    a = b;
                    b = tmp;
                    c = last + idxBuf.readSmart() - 1;
                    last = c;
                }
                default -> throw new IllegalStateException("Invalid face type: " + type);
            }
            fa[i] = a;
            fb[i] = b;
            fc[i] = c;
        }
    }

    private record DecodeAttempt(ModelMesh mesh, int validPrefix) {}

}
