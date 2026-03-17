package com.modelviewer.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read access to the {@code animations} table.
 */
public final class AnimationRepository {

    private static final Logger log = LoggerFactory.getLogger(AnimationRepository.class);

    private final Connection connection;

    public AnimationRepository(DatabaseManager db) {
        this.connection = db.getConnection();
    }

    // ── Record ────────────────────────────────────────────────────────────────

    public record AnimationRecord(int id, int frameCount, int skeletonId) {
        @Override public String toString() {
            return "Anim #" + id + " (" + frameCount + " frames"
                    + (skeletonId >= 0 ? " / skel " + skeletonId : "") + ")";
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public AnimationRecord findById(int id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, frame_count, skeleton_id FROM animations WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.warn("findById({}) failed: {}", id, e.getMessage());
        }
        return null;
    }

    /** Animations have no name field; search by skeleton ID or ID prefix. */
    public List<AnimationRecord> searchBySkeletonId(int skeletonId, int limit) {
        List<AnimationRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, frame_count, skeleton_id FROM animations" +
                " WHERE skeleton_id = ? LIMIT ?")) {
            ps.setInt(1, skeletonId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("searchBySkeletonId failed: {}", e.getMessage());
        }
        return results;
    }

    public List<AnimationRecord> searchById(String idPrefix, int limit) {
        List<AnimationRecord> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, frame_count, skeleton_id FROM animations" +
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
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM animations")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("count() failed: {}", e.getMessage());
        }
        return 0;
    }

    private static AnimationRecord mapRow(ResultSet rs) throws SQLException {
        return new AnimationRecord(
                rs.getInt("id"),
                rs.getInt("frame_count"),
                rs.getInt("skeleton_id"));
    }
}
