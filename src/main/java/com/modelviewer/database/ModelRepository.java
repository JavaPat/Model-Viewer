package com.modelviewer.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read access to the {@code models} table.
 */
public final class ModelRepository {

    private static final Logger log = LoggerFactory.getLogger(ModelRepository.class);

    private final Connection connection;

    public ModelRepository(DatabaseManager db) {
        this.connection = db.getConnection();
    }

    // ── Record ────────────────────────────────────────────────────────────────

    public record ModelRecord(int id, int vertexCount, int faceCount, boolean hasTexture) {
        @Override public String toString() {
            return "Model #" + id + " (" + vertexCount + "v / " + faceCount + "f"
                    + (hasTexture ? " / textured" : "") + ")";
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public ModelRecord findById(int id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, vertex_count, face_count, has_texture FROM models WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.warn("findById({}) failed: {}", id, e.getMessage());
        }
        return null;
    }

    /**
     * Searches models by ID string prefix (for the global search bar).
     * Since models have no name, only ID-based search is supported.
     */
    public List<ModelRecord> searchById(String idPrefix, int limit) {
        List<ModelRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, vertex_count, face_count, has_texture FROM models" +
                " WHERE CAST(id AS TEXT) LIKE ? LIMIT ?")) {
            ps.setString(1, idPrefix + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("searchById failed: {}", e.getMessage());
        }
        return results;
    }

    public int count() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM models")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("count() failed: {}", e.getMessage());
        }
        return 0;
    }

    private static ModelRecord mapRow(ResultSet rs) throws SQLException {
        return new ModelRecord(
                rs.getInt("id"),
                rs.getInt("vertex_count"),
                rs.getInt("face_count"),
                rs.getInt("has_texture") != 0);
    }
}
