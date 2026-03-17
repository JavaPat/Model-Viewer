package com.modelviewer.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read access to the {@code objects} table.
 */
public final class ObjectRepository {

    private static final Logger log = LoggerFactory.getLogger(ObjectRepository.class);

    private final Connection connection;

    public ObjectRepository(DatabaseManager db) {
        this.connection = db.getConnection();
    }

    // ── Record ────────────────────────────────────────────────────────────────

    public record ObjectRecord(int id, String name, int[] modelIds) {
        @Override public String toString() {
            return "Object #" + id + (!"null".equals(name) ? " – " + name : "");
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public ObjectRecord findById(int id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, model_ids FROM objects WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.warn("findById({}) failed: {}", id, e.getMessage());
        }
        return null;
    }

    public List<ObjectRecord> searchByName(String query, int limit) {
        List<ObjectRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, model_ids FROM objects" +
                " WHERE name LIKE ? COLLATE NOCASE LIMIT ?")) {
            ps.setString(1, "%" + query + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("searchByName failed: {}", e.getMessage());
        }
        return results;
    }

    public int count() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM objects")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("count() failed: {}", e.getMessage());
        }
        return 0;
    }

    private static ObjectRecord mapRow(ResultSet rs) throws SQLException {
        return new ObjectRecord(
                rs.getInt("id"),
                rs.getString("name"),
                NpcRepository.parseIds(rs.getString("model_ids")));
    }
}
