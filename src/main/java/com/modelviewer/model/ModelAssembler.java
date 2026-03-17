package com.modelviewer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines multiple {@link ModelMesh} parts into a single mesh suitable for
 * GPU upload.
 *
 * Used by the NPC, item, and object viewers to merge the individual component
 * models that compose a definition into one renderable mesh.
 */
public final class ModelAssembler {

    private ModelAssembler() {}

    /**
     * Combines multiple ModelMesh parts into a single ModelMesh.
     *
     * <ul>
     *   <li>Concatenates vertex and face arrays.</li>
     *   <li>Remaps face vertex indices with per-part vertex offsets.</li>
     *   <li>Handles optional arrays: if ANY part has faceRenderTypes / faceAlphas /
     *       faceTextureIds, those arrays are allocated for the combined mesh (filling
     *       with 0 / 0 / -1 respectively for parts that don't have them).</li>
     *   <li>If ANY part has vertexSkins, a combined vertexSkins array is built
     *       (parts without skins get group 0 for their vertices).</li>
     *   <li>Applies recolor on the combined faceColors.</li>
     *   <li>Applies scale on the combined vertex arrays (only when needed).</li>
     * </ul>
     *
     * @param id        synthetic model ID for the assembled mesh (use negative to
     *                  avoid GPU cache collisions with real model IDs)
     * @param parts     list of ModelMesh parts (null entries are skipped)
     * @param rcFind    recolor-find array (may be null)
     * @param rcReplace recolor-replace array (may be null, same length as rcFind)
     * @param scaleX    X-axis scale (128 = 1.0×)
     * @param scaleY    Y-axis scale (128 = 1.0×)
     * @param scaleZ    Z-axis scale (128 = 1.0×)
     * @return assembled ModelMesh, or null if all parts were null or empty
     */
    public static ModelMesh assemble(int id, List<ModelMesh> parts,
                                     short[] rcFind, short[] rcReplace,
                                     int scaleX, int scaleY, int scaleZ) {
        // Filter out null parts
        List<ModelMesh> valid = new ArrayList<>();
        for (ModelMesh part : parts) {
            if (part != null) {
                valid.add(part);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }

        // Sum total vertex and face counts
        int totalVerts = 0;
        int totalFaces = 0;
        for (ModelMesh part : valid) {
            totalVerts += part.vertexCount;
            totalFaces += part.faceCount;
        }
        if (totalVerts == 0 || totalFaces == 0) {
            return null;
        }

        // Determine whether optional per-face arrays are needed across all parts
        boolean needRenderTypes  = false;
        boolean needAlphas       = false;
        boolean needTextureIds   = false;
        for (ModelMesh part : valid) {
            if (part.faceRenderTypes != null) needRenderTypes = true;
            if (part.faceAlphas      != null) needAlphas      = true;
            if (part.faceTextureIds  != null) needTextureIds  = true;
        }

        // Determine whether we need to build a combined vertexSkins array
        boolean hasAnySkins = false;
        for (ModelMesh part : valid) {
            if (part.vertexSkins != null) { hasAnySkins = true; break; }
        }

        // Allocate combined arrays
        int[]   vx             = new int[totalVerts];
        int[]   vy             = new int[totalVerts];
        int[]   vz             = new int[totalVerts];
        int[]   fa             = new int[totalFaces];
        int[]   fb             = new int[totalFaces];
        int[]   fc             = new int[totalFaces];
        short[] faceColors     = new short[totalFaces];
        int[]   faceRenderTypes = needRenderTypes ? new int[totalFaces]  : null;
        int[]   faceAlphas      = needAlphas      ? new int[totalFaces]  : null;
        int[]   faceTextureIds  = needTextureIds  ? new int[totalFaces]  : null;
        int[]   combinedSkins   = hasAnySkins     ? new int[totalVerts]  : null;

        // Pre-fill faceTextureIds with -1 (means "no texture")
        if (faceTextureIds != null) {
            for (int i = 0; i < totalFaces; i++) {
                faceTextureIds[i] = -1;
            }
        }

        // Copy data from each part with the appropriate vertex offset applied
        int vertexBase = 0;
        int faceBase   = 0;
        for (ModelMesh part : valid) {
            int vc = part.vertexCount;
            int fc2 = part.faceCount;

            // Copy vertices
            System.arraycopy(part.vertexX, 0, vx, vertexBase, vc);
            System.arraycopy(part.vertexY, 0, vy, vertexBase, vc);
            System.arraycopy(part.vertexZ, 0, vz, vertexBase, vc);

            // Copy face indices, remapping with the vertex offset for this part
            for (int i = 0; i < fc2; i++) {
                fa[faceBase + i] = part.faceVertexA[i] + vertexBase;
                fb[faceBase + i] = part.faceVertexB[i] + vertexBase;
                fc[faceBase + i] = part.faceVertexC[i] + vertexBase;
            }

            // Copy face colors
            System.arraycopy(part.faceColors, 0, faceColors, faceBase, fc2);

            // Copy optional per-face render types (or leave as 0 for this part)
            if (faceRenderTypes != null && part.faceRenderTypes != null) {
                System.arraycopy(part.faceRenderTypes, 0, faceRenderTypes, faceBase, fc2);
            }

            // Copy optional per-face alphas (or leave as 0 = opaque for this part)
            if (faceAlphas != null && part.faceAlphas != null) {
                System.arraycopy(part.faceAlphas, 0, faceAlphas, faceBase, fc2);
            }

            // Copy optional per-face texture IDs (or leave as -1 for this part)
            if (faceTextureIds != null && part.faceTextureIds != null) {
                System.arraycopy(part.faceTextureIds, 0, faceTextureIds, faceBase, fc2);
            }

            // Copy vertex skins (if part has them; otherwise slots remain 0 = group 0)
            if (combinedSkins != null) {
                if (part.vertexSkins != null) {
                    System.arraycopy(part.vertexSkins, 0, combinedSkins, vertexBase, vc);
                }
                // if part.vertexSkins is null, the slots stay 0 (group 0)
            }

            vertexBase += vc;
            faceBase   += fc2;
        }

        // Apply recolor on the combined faceColors
        if (rcFind != null && rcReplace != null && rcFind.length > 0) {
            faceColors = applyRecolor(faceColors, rcFind, rcReplace);
        }

        // Apply scale on the combined vertex arrays (skip if all are 1.0×)
        if (scaleX != 128 || scaleY != 128 || scaleZ != 128) {
            for (int i = 0; i < totalVerts; i++) {
                vx[i] = vx[i] * scaleX / 128;
                vy[i] = vy[i] * scaleY / 128;
                vz[i] = vz[i] * scaleZ / 128;
            }
        }

        return new ModelMesh(id, vx, vy, vz, fa, fb, fc,
                             faceColors, faceRenderTypes, faceAlphas, faceTextureIds,
                             combinedSkins, null, null, null);
    }

    /**
     * Returns a new copy of {@code colors} with recolor substitutions applied.
     *
     * For each color value in the array: if it matches {@code find[i]}, it is
     * replaced with {@code replace[i]}.
     *
     * @param colors  original per-face color array
     * @param find    colors to search for
     * @param replace replacement colors, parallel to find
     * @return a new array with all substitutions applied
     */
    private static short[] applyRecolor(short[] colors, short[] find, short[] replace) {
        short[] result = colors.clone();
        for (int f = 0; f < result.length; f++) {
            for (int r = 0; r < find.length; r++) {
                if (result[f] == find[r]) {
                    result[f] = replace[r];
                    break;
                }
            }
        }
        return result;
    }
}
