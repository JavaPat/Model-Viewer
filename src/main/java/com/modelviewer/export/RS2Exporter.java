package com.modelviewer.export;

import com.modelviewer.model.ModelMesh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * Exports a {@link ModelMesh} back to the original OSRS / RS2 old-format binary.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Output format: RS2 "old format" (18-byte footer)
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * The output file is a valid model binary that can be inserted directly into
 * a cache archive (index 7) or loaded by RSPS tools that read the old-format.
 *
 * Data sections written in order:
 *   1.  vertexFlags          (vertexCount bytes)
 *   2.  faceCompressTypes    (faceCount bytes)  — all type-1 (new triangle each face)
 *   3.  faceAlphas           (faceCount bytes, only if model has per-face alpha)
 *   4.  faceRenderTypes      (faceCount bytes, only if model has per-face types)
 *   5.  faceIndices          (variable — delta-encoded with signed smarts)
 *   6.  faceColors           (faceCount × 2 bytes — packed HSL shorts, big-endian)
 *   7.  vertexX              (variable — delta-encoded with signed smarts)
 *   8.  vertexY              (variable — delta-encoded with signed smarts)
 *   9.  vertexZ              (variable — delta-encoded with signed smarts)
 *  10.  18-byte footer       (counts, flags, section lengths)
 *
 * ── Signed smart encoding ─────────────────────────────────────────────────────
 *   Values in [-64,  63] : 1 byte  → write (value + 64)              → first byte < 128
 *   Values in [-16384, 16383] \ [-64, 63] : 2 bytes → write (value + 49152) as uint16
 *   Values outside that range are clamped (extremely rare in practice).
 *
 * ── Face index encoding (type 1) ─────────────────────────────────────────────
 *   Each face is encoded as compress-type byte = 1, then three signed smarts:
 *     delta_a = a - last;  last = a
 *     delta_b = b - last;  last = b
 *     delta_c = c - last;  last = c
 *   This matches the decoder in ModelDecoder.readFaceIndices().
 *
 * ── Vertex encoding ───────────────────────────────────────────────────────────
 *   For each vertex i, deltas dx/dy/dz from the previous vertex are computed.
 *   A flags byte records which axes have a non-zero delta (bits 0/1/2 → X/Y/Z).
 *   Only non-zero deltas are written to their respective axis streams.
 */
public final class RS2Exporter {

    private static final Logger log = LoggerFactory.getLogger(RS2Exporter.class);

    private RS2Exporter() {}

    /**
     * Exports the mesh to a binary RS2 file.
     *
     * @param mesh decoded model data
     * @param file destination file (will be overwritten if it exists)
     * @throws IOException on write failure
     */
    public static void export(ModelMesh mesh, File file) throws IOException {
        // ── Encode sections into byte arrays ──────────────────────────────────
        byte[] vertexFlags;
        byte[] faceCompressTypes;
        byte[] faceAlphas    = null;
        byte[] faceTypes     = null;
        byte[] faceIndices;
        byte[] faceColors;
        byte[] vertexX, vertexY, vertexZ;

        try (
            ByteArrayOutputStream vfBuf  = new ByteArrayOutputStream();
            ByteArrayOutputStream vxBuf  = new ByteArrayOutputStream();
            ByteArrayOutputStream vyBuf  = new ByteArrayOutputStream();
            ByteArrayOutputStream vzBuf  = new ByteArrayOutputStream();
            ByteArrayOutputStream fcBuf  = new ByteArrayOutputStream();   // compress types
            ByteArrayOutputStream fiBuf  = new ByteArrayOutputStream();   // face indices
            ByteArrayOutputStream fcoBuf = new ByteArrayOutputStream();   // face colors
        ) {
            // ── Vertex positions (delta-encoded, three separate axis streams) ──
            int prevX = 0, prevY = 0, prevZ = 0;
            for (int i = 0; i < mesh.vertexCount; i++) {
                int dx = mesh.vertexX[i] - prevX;
                int dy = mesh.vertexY[i] - prevY;
                int dz = mesh.vertexZ[i] - prevZ;

                int flags = ((dx != 0) ? 1 : 0)
                          | ((dy != 0) ? 2 : 0)
                          | ((dz != 0) ? 4 : 0);
                vfBuf.write(flags);

                if (dx != 0) writeSignedSmart(vxBuf, dx);
                if (dy != 0) writeSignedSmart(vyBuf, dy);
                if (dz != 0) writeSignedSmart(vzBuf, dz);

                prevX = mesh.vertexX[i];
                prevY = mesh.vertexY[i];
                prevZ = mesh.vertexZ[i];
            }
            vertexFlags = vfBuf.toByteArray();
            vertexX     = vxBuf.toByteArray();
            vertexY     = vyBuf.toByteArray();
            vertexZ     = vzBuf.toByteArray();

            // ── Face indices (all type-1: 3 signed smarts per face) ───────────
            int last = 0;
            for (int i = 0; i < mesh.faceCount; i++) {
                fcBuf.write(1);  // compress type 1 = new triangle

                int a = clampIdx(mesh.faceVertexA[i], mesh.vertexCount);
                int b = clampIdx(mesh.faceVertexB[i], mesh.vertexCount);
                int c = clampIdx(mesh.faceVertexC[i], mesh.vertexCount);

                writeSignedSmart(fiBuf, a - last); last = a;
                writeSignedSmart(fiBuf, b - last); last = b;
                writeSignedSmart(fiBuf, c - last); last = c;
            }
            faceCompressTypes = fcBuf.toByteArray();
            faceIndices       = fiBuf.toByteArray();

            // ── Face colours (big-endian unsigned shorts, HSL format) ─────────
            DataOutputStream fcos = new DataOutputStream(fcoBuf);
            for (int i = 0; i < mesh.faceCount; i++) {
                fcos.writeShort(mesh.faceColors[i] & 0xFFFF);
            }
            faceColors = fcoBuf.toByteArray();
        }

        // ── Optional sections ─────────────────────────────────────────────────
        if (mesh.faceAlphas != null) {
            ByteArrayOutputStream faBuf = new ByteArrayOutputStream(mesh.faceCount);
            for (int i = 0; i < mesh.faceCount; i++) {
                faBuf.write(mesh.faceAlphas[i] & 0xFF);
            }
            faceAlphas = faBuf.toByteArray();
        }

        if (mesh.faceRenderTypes != null) {
            ByteArrayOutputStream ftBuf = new ByteArrayOutputStream(mesh.faceCount);
            for (int i = 0; i < mesh.faceCount; i++) {
                // Strip the texture bit (bit 0) since we have no UV data to export
                ftBuf.write(mesh.faceRenderTypes[i] & 0xFE);
            }
            faceTypes = ftBuf.toByteArray();
        }

        // ── Footer flags ──────────────────────────────────────────────────────
        int flags          = (faceTypes  != null) ? 1 : 0;  // bit 0 = faceTypes section present
        int hasFaceAlpha   = (faceAlphas != null) ? 1 : 0;

        // ── Write to file ─────────────────────────────────────────────────────
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {

            // Sections in exact order expected by ModelDecoder.decodeOldFormat()
            dos.write(vertexFlags);                         // 1. vertex flags
            dos.write(faceCompressTypes);                   // 2. face compress types
            // 3. face priorities — skipped (globalPriority = 0, not 0xFF)
            if (faceAlphas != null) dos.write(faceAlphas); // 4. face alphas (optional)
            // 5. face skins — not supported
            if (faceTypes  != null) dos.write(faceTypes);  // 6. face render types (optional)
            // 7. vertex skins — not supported
            dos.write(faceIndices);                         // 8. face indices
            dos.write(faceColors);                          // 9. face colours
            // 10. texture triangles — texFaceCount = 0, nothing written
            dos.write(vertexX);                             // 11. vertex X deltas
            dos.write(vertexY);                             // 12. vertex Y deltas
            dos.write(vertexZ);                             // 13. vertex Z deltas

            // ── 18-byte footer ────────────────────────────────────────────────
            writeUShort(dos, mesh.vertexCount);             // 2 bytes: vertex count
            writeUShort(dos, mesh.faceCount);               // 2 bytes: face count
            dos.writeByte(0);                               // 1 byte:  texFaceCount = 0
            dos.writeByte(flags);                           // 1 byte:  flags
            dos.writeByte(0);                               // 1 byte:  globalPriority = 0
            dos.writeByte(hasFaceAlpha);                    // 1 byte:  hasFaceAlpha
            dos.writeByte(0);                               // 1 byte:  hasFaceSkins = 0
            dos.writeByte(0);                               // 1 byte:  hasVertexSkins = 0
            writeUShort(dos, vertexX.length);               // 2 bytes: xDataLen
            writeUShort(dos, vertexY.length);               // 2 bytes: yDataLen
            writeUShort(dos, vertexZ.length);               // 2 bytes: zDataLen
            writeUShort(dos, faceIndices.length);           // 2 bytes: faceIndexDataLen
            // Total footer: 2+2+1+1+1+1+1+1+2+2+2+2 = 18 bytes ✓
        }

        log.debug("RS2 exported model {} ({} verts, {} faces) → {}",
                mesh.modelId, mesh.vertexCount, mesh.faceCount, file.getName());
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    /**
     * Writes a signed smart integer (1 or 2 bytes, big-endian).
     *
     * Values in [-64, 63]         → 1 byte  (value + 64),            first byte ∈ [0, 127]
     * Values in [-16384, 16383]   → 2 bytes (value + 49152) as uint16, first byte ≥ 128
     *
     * Values outside [-16384, 16383] are clamped — they are vanishingly rare in
     * practice since OSRS model coordinates are bounded by the signed-smart range.
     */
    private static void writeSignedSmart(OutputStream out, int value) throws IOException {
        // Clamp to the 2-byte signed smart range
        value = Math.max(-16384, Math.min(16383, value));
        if (value >= -64 && value <= 63) {
            out.write((value + 64) & 0xFF);
        } else {
            int v = (value + 49152) & 0xFFFF;
            out.write((v >> 8) & 0xFF);
            out.write( v       & 0xFF);
        }
    }

    /** Writes a big-endian unsigned 16-bit integer. */
    private static void writeUShort(DataOutputStream dos, int value) throws IOException {
        dos.writeByte((value >> 8) & 0xFF);
        dos.writeByte( value       & 0xFF);
    }

    /** Clamps a face vertex index to the valid vertex range, preventing corrupt models. */
    private static int clampIdx(int idx, int vertexCount) {
        return Math.max(0, Math.min(vertexCount - 1, idx));
    }
}
