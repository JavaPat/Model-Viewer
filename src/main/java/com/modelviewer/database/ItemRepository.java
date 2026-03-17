package com.modelviewer.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read access to the {@code items} table.
 */
public final class ItemRepository {

    private static final Logger log = LoggerFactory.getLogger(ItemRepository.class);

    private final Connection connection;

    public ItemRepository(DatabaseManager db) {
        this.connection = db.getConnection();
    }

    // ── Record ────────────────────────────────────────────────────────────────

    public record ItemRecord(int id, String name, int inventoryModel) {
        @Override public String toString() {
            return "Item #" + id + (!"null".equals(name) ? " – " + name : "");
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public ItemRecord findById(int id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, inventory_model FROM items WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.warn("findById({}) failed: {}", id, e.getMessage());
        }
        return null;
    }

    public List<ItemRecord> searchByName(String query, int limit) {
        List<ItemRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, inventory_model FROM items" +
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
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM items")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("count() failed: {}", e.getMessage());
        }
        return 0;
    }

    private static ItemRecord mapRow(ResultSet rs) throws SQLException {
        return new ItemRecord(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("inventory_model"));
    }
}
