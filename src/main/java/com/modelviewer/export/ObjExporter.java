package com.modelviewer.export;

import com.modelviewer.model.ModelDecoder;
import com.modelviewer.model.ModelMesh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Exports a {@link ModelMesh} to the Wavefront OBJ format.
 *
 * Output format:
 *   # comment header
 *   v  x y z         — vertex positions (one per OSRS vertex)
 *   vc r g b         — vertex colours as a comment (not standard OBJ)
 *   f  a b c         — face indices (1-based)
 *
 * The MTL material file is generated alongside the OBJ.  Each unique face
 * colour is written as a separate material, so any OBJ viewer that supports
 * MTL can display the model with approximate per-face colours.
 *
 * Scale: OSRS integer units are divided by 128 to give approximate game-unit
 * metres.  This makes exported models a sane size in most 3D tools.
 */
public final class ObjExporter {

    private static final Logger log = LoggerFactory.getLogger(ObjExporter.class);

    private static final float SCALE = 1.0f / 128.0f;

    private ObjExporter() {}

    /**
     * Exports the mesh to an OBJ file.  The matching MTL file is written to
     * the same directory with the same base name.
     *
     * @param mesh     the model to export
     * @param objFile  destination .obj file
     * @throws IOException on write failure
     */
    public static void export(ModelMesh mesh, File objFile) throws IOException {
        String baseName = objFile.getName().replaceAll("\\.obj$", "");
        File   mtlFile  = new File(objFile.getParent(), baseName + ".mtl");

        // Collect unique HSL colours to generate MTL materials
        java.util.LinkedHashMap<Integer, String> colorMaterials = new java.util.LinkedHashMap<>();
        String[] faceMaterials = new String[mesh.faceCount];
        for (int i = 0; i < mesh.faceCount; i++) {
            int hsl = mesh.faceColors[i] & 0xFFFF;
            String matName = colorMaterials.computeIfAbsent(hsl, h -> "mat_" + h);
            faceMaterials[i] = matName;
        }

        // ── Write MTL ────────────────────────────────────────────────────────
        try (PrintWriter mtl = new PrintWriter(new BufferedWriter(new FileWriter(mtlFile)))) {
            mtl.println("# OSRS Model Viewer MTL export");
            for (var entry : colorMaterials.entrySet()) {
                int rgb = ModelDecoder.hslToRgb(entry.getKey());
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >>  8) & 0xFF) / 255f;
                float b = ( rgb        & 0xFF) / 255f;

                mtl.println("newmtl " + entry.getValue());
                mtl.printf("Kd %.4f %.4f %.4f%n", r, g, b);
                mtl.printf("Ka %.4f %.4f %.4f%n", r * 0.3f, g * 0.3f, b * 0.3f);
                mtl.println("Ks 0.1 0.1 0.1");
                mtl.println("Ns 32");
                mtl.println("d 1.0");
                mtl.println();
            }
        }

        // ── Write OBJ ────────────────────────────────────────────────────────
        try (PrintWriter obj = new PrintWriter(new BufferedWriter(new FileWriter(objFile)))) {
            obj.println("# Exported by OSRS Model Viewer");
            obj.println("# Model ID: " + mesh.modelId);
            obj.println("# Vertices: " + mesh.vertexCount);
            obj.println("# Faces:    " + mesh.faceCount);
            obj.println("mtllib " + baseName + ".mtl");
            obj.println("o Model_" + mesh.modelId);
            obj.println();

            // Vertices  (Y is negated to match the GL coordinate flip)
            for (int i = 0; i < mesh.vertexCount; i++) {
                float x =  mesh.vertexX[i] * SCALE;
                float y = -mesh.vertexY[i] * SCALE;  // OSRS Y is screen-down
                float z =  mesh.vertexZ[i] * SCALE;
                obj.printf("v %.6f %.6f %.6f%n", x, y, z);
            }
            obj.println();

            // Faces with material groups
            String lastMat = null;
            for (int i = 0; i < mesh.faceCount; i++) {
                String mat = faceMaterials[i];
                if (!mat.equals(lastMat)) {
                    obj.println("usemtl " + mat);
                    lastMat = mat;
                }
                // OBJ indices are 1-based
                obj.println("f " + (mesh.faceVertexA[i] + 1)
                              + " " + (mesh.faceVertexB[i] + 1)
                              + " " + (mesh.faceVertexC[i] + 1));
            }
        }

        log.info("Exported model {} to {}", mesh.modelId, objFile);
    }
}
