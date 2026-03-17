package com.modelviewer.export;

import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Exports a {@link ModelMesh} to the Metasequoia (.mqo) text format.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * MQO format overview
 * ──────────────────────────────────────────────────────────────────────────────
 * Metasequoia is a Japanese polygon modelling tool widely used for character
 * modelling.  Its ".mqo" format is a simple plain-text scene description:
 *
 *   Metasequoia DocumentFormat Text Ver 1.0
 *   Scene { … }
 *   Material N { … }
 *   Object "name" {
 *       vertex N { … }
 *       face N { … }
 *   }
 *   Eof
 *
 * Face format: 3 V(a b c) M(matIndex)
 *
 * ── Colour strategy ───────────────────────────────────────────────────────────
 * OSRS face colours are stored in 16-bit HSL format.  We collect every unique
 * HSL value and generate one MQO material per unique colour.  Each face
 * references its material by index.  This is more portable than per-vertex
 * vertex-colour (COL) attributes and works with all MQO-compatible importers.
 *
 * ── Coordinate system ────────────────────────────────────────────────────────
 * OSRS uses a left-handed coordinate system where Y increases downward (screen
 * space convention).  MQO uses a right-handed coordinate system with Y-up.
 * Conversion: negate Y ( y_mqo = -y_osrs ).
 * When Y is negated, face winding order reverses — faces are written as
 * V(a c b) instead of V(a b c) to maintain outward-facing normals.
 *
 * ── Scale ─────────────────────────────────────────────────────────────────────
 * Raw OSRS integer units are used directly (1 unit ≈ 1/128 of a game tile).
 * This produces models with vertex coordinates typically in the range ±512,
 * which is a comfortable working scale in Metasequoia.
 */
public final class MQOExporter {

    private static final Logger log = LoggerFactory.getLogger(MQOExporter.class);

    private MQOExporter() {}

    /**
     * Exports the mesh to an MQO file.
     *
     * @param mesh decoded model data
     * @param file destination .mqo file (will be overwritten if it exists)
     * @throws IOException on write failure
     */
    public static void export(ModelMesh mesh, File file) throws IOException {
        // Collect unique HSL colours and assign material indices
        LinkedHashMap<Integer, Integer> hslToMatIdx = new LinkedHashMap<>();
        int[] faceMaterialIndex = new int[mesh.faceCount];
        for (int i = 0; i < mesh.faceCount; i++) {
            int hsl = mesh.faceColors[i] & 0xFFFF;
            faceMaterialIndex[i] = hslToMatIdx.computeIfAbsent(hsl, h -> hslToMatIdx.size());
        }

        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(file), "UTF-8")))) {

            writeMqo(pw, mesh, hslToMatIdx, faceMaterialIndex);
        }

        log.debug("MQO exported model {} ({} verts, {} faces, {} materials) → {}",
                mesh.modelId, mesh.vertexCount, mesh.faceCount,
                hslToMatIdx.size(), file.getName());
    }

    // ── MQO document construction ─────────────────────────────────────────────

    private static void writeMqo(PrintWriter pw,
                                  ModelMesh mesh,
                                  LinkedHashMap<Integer, Integer> hslToMatIdx,
                                  int[] faceMaterialIndex) {
        // File header
        pw.println("Metasequoia DocumentFormat Text Ver 1.0");
        pw.println();

        // Scene block — sets up a default camera so the model is visible on open
        pw.println("Scene {");
        pw.println("\tpos 0.000 0.000 1500.000");
        pw.println("\tlookat 0.000 0.000 0.000");
        pw.println("\thead -0.500");
        pw.println("\tpich 0.150");
        pw.println("\tbank 0.000");
        pw.println("\tortho 0");
        pw.println("\tzoom2 1.500");
        pw.println("\tamb 0.250 0.250 0.250");
        pw.println("}");
        pw.println();

        // Material block
        pw.println("Material " + hslToMatIdx.size() + " {");
        for (var entry : hslToMatIdx.entrySet()) {
            int hsl = entry.getKey();
            int rgb = ModelDecoder.hslToRgb(hsl);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >>  8) & 0xFF) / 255f;
            float b = ( rgb        & 0xFF) / 255f;

            // MQO material: name, colour (r g b a), diffuse, ambient, emissive, specular, power
            pw.printf(Locale.US,
                "\t\"hsl_%04X\" col(%.4f %.4f %.4f 1.0000)"
              + " dif(0.8000) amb(0.6000) emi(0.0000) spc(0.0000) power(5.00)%n",
                hsl, r, g, b);
        }
        pw.println("}");
        pw.println();

        // Object block
        pw.println("Object \"Model_" + mesh.modelId + "\" {");
        pw.println("\tdepth 0");
        pw.println("\tfolding 0");
        pw.println("\tscale 1.000 1.000 1.000");
        pw.println("\trotation 0.000 0.000 0.000");
        pw.println("\ttranslation 0.000 0.000 0.000");
        pw.println("\tvisible 15");
        pw.println("\tlocking 0");
        pw.println("\tshading 1");
        pw.println("\tfacet 59.500");
        pw.println("\tcolor 0.898 0.498 0.698");
        pw.println("\tcolor_type 0");

        // Vertex list — Y is negated to flip from OSRS (Y-down) to MQO (Y-up)
        pw.println("\tvertex " + mesh.vertexCount + " {");
        for (int i = 0; i < mesh.vertexCount; i++) {
            pw.printf(Locale.US, "\t\t%d %d %d%n",
                    mesh.vertexX[i],
                    -mesh.vertexY[i],   // negate Y
                    mesh.vertexZ[i]);
        }
        pw.println("\t}");

        // Face list
        // Because Y was negated, the face winding reverses.
        // Write V(a c b) instead of V(a b c) to keep outward-facing normals.
        pw.println("\tface " + mesh.faceCount + " {");
        for (int i = 0; i < mesh.faceCount; i++) {
            int a = clampIdx(mesh.faceVertexA[i], mesh.vertexCount);
            int b = clampIdx(mesh.faceVertexB[i], mesh.vertexCount);
            int c = clampIdx(mesh.faceVertexC[i], mesh.vertexCount);
            pw.printf(Locale.US, "\t\t3 V(%d %d %d) M(%d)%n",
                    a, c, b,                    // reversed winding
                    faceMaterialIndex[i]);
        }
        pw.println("\t}");

        pw.println("}");
        pw.println();
        pw.println("Eof");
    }

    /** Clamps a face vertex index to the valid vertex range. */
    private static int clampIdx(int idx, int vertexCount) {
        return Math.max(0, Math.min(vertexCount - 1, idx));
    }
}
