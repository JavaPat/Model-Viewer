package com.modelviewer.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read access to the {@code npcs} table.
 */
public final class NpcRepository {

    private static final Logger log = LoggerFactory.getLogger(NpcRepository.class);

    private final Connection connection;

    public NpcRepository(DatabaseManager db) {
        this.connection = db.getConnection();
    }

    // ── Record ────────────────────────────────────────────────────────────────

    public record NpcRecord(int id, String name, int combatLevel, int[] modelIds) {
        @Override public String toString() {
            return "NPC #" + id + (!"null".equals(name) ? " – " + name : "")
                    + (combatLevel >= 0 ? " (lvl " + combatLevel + ")" : "");
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public NpcRecord findById(int id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, combat_level, model_ids FROM npcs WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.warn("findById({}) failed: {}", id, e.getMessage());
        }
        return null;
    }

    public List<NpcRecord> searchByName(String query, int limit) {
        List<NpcRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, combat_level, model_ids FROM npcs" +
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
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM npcs")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("count() failed: {}", e.getMessage());
        }
        return 0;
    }

    private static NpcRecord mapRow(ResultSet rs) throws SQLException {
        return new NpcRecord(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("combat_level"),
                parseIds(rs.getString("model_ids")));
    }

    static int[] parseIds(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] parts = s.split(",");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { ids[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException ignored) {}
        }
        return ids;
    }
}
