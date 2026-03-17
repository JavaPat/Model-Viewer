package com.modelviewer.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.Instant;

/**
 * Manages the SQLite connection and schema for the asset index database.
 *
 * Database location: ~/.osrs-model-viewer/osrs_assets.db
 *
 * The schema is created automatically on first open.  The {@code index_meta}
 * table stores a flag that indicates whether a full index run has completed,
 * so the application can skip re-indexing on subsequent launches.
 */
public final class DatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    public static final String DB_DIR_NAME  = ".osrs-model-viewer";
    public static final String DB_FILE_NAME = "osrs_assets.db";

    private final Connection connection;

    // ── Construction / schema ─────────────────────────────────────────────────

    public DatabaseManager() throws SQLException {
        File dbFile = getDbFile();
        File dbDir  = dbFile.getParentFile();
        if (!dbDir.exists() && !dbDir.mkdirs()) {
            throw new SQLException("Cannot create database directory: " + dbDir);
        }
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/');

        connection = DriverManager.getConnection(url);
        applyPragmas();
        createSchema();
        log.info("Database opened: {}", dbFile.getAbsolutePath());
    }

    private void applyPragmas() throws SQLException {
        try (Statement st = connection.createStatement()) {
            // WAL journal for better concurrent read performance
            st.execute("PRAGMA journal_mode=WAL");
            // NORMAL sync is safe with WAL and significantly faster than FULL
            st.execute("PRAGMA synchronous=NORMAL");
            // 8 MB page cache
            st.execute("PRAGMA cache_size=-8192");
            // Store temp tables in memory
            st.execute("PRAGMA temp_store=MEMORY");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS models (" +
                "    id           INTEGER PRIMARY KEY," +
                "    vertex_count INTEGER NOT NULL," +
                "    face_count   INTEGER NOT NULL," +
                "    has_texture  INTEGER NOT NULL DEFAULT 0" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS npcs (" +
                "    id           INTEGER PRIMARY KEY," +
                "    name         TEXT    NOT NULL DEFAULT 'null'," +
                "    combat_level INTEGER NOT NULL DEFAULT -1," +
                "    model_ids    TEXT    NOT NULL DEFAULT ''" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS items (" +
                "    id              INTEGER PRIMARY KEY," +
                "    name            TEXT    NOT NULL DEFAULT 'null'," +
                "    inventory_model INTEGER NOT NULL DEFAULT -1" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS objects (" +
                "    id        INTEGER PRIMARY KEY," +
                "    name      TEXT NOT NULL DEFAULT 'null'," +
                "    model_ids TEXT NOT NULL DEFAULT ''" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS animations (" +
                "    id          INTEGER PRIMARY KEY," +
                "    frame_count INTEGER NOT NULL DEFAULT 0," +
                "    skeleton_id INTEGER NOT NULL DEFAULT -1" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS index_meta (" +
                "    key   TEXT PRIMARY KEY," +
                "    value TEXT NOT NULL" +
                ")");

            // Indexes for fast LIKE name searches
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_npcs_name    ON npcs(name COLLATE NOCASE)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_items_name   ON items(name COLLATE NOCASE)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_objects_name ON objects(name COLLATE NOCASE)");
        }
    }

    // ── Index state ───────────────────────────────────────────────────────────

    /**
     * Returns true if a completed index run has been recorded in index_meta.
     */
    public boolean isIndexed() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM index_meta WHERE key = 'indexed_at'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Records that indexing has completed for the given cache directory path.
     */
    public void markIndexed(String cacheDirPath) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO index_meta(key, value) VALUES(?, ?)")) {
            ps.setString(1, "indexed_at");
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();

            ps.setString(1, "cache_dir");
            ps.setString(2, cacheDirPath);
            ps.executeUpdate();
        }
    }

    /**
     * Wipes all indexed data (all five asset tables and index_meta).
     * Used before a full re-index.
     */
    public void clearAll() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM models");
            st.executeUpdate("DELETE FROM npcs");
            st.executeUpdate("DELETE FROM items");
            st.executeUpdate("DELETE FROM objects");
            st.executeUpdate("DELETE FROM animations");
            st.executeUpdate("DELETE FROM index_meta");
            // Reclaim disk space
            st.executeUpdate("VACUUM");
        }
        log.info("Asset database cleared");
    }

    // ── Connection access ─────────────────────────────────────────────────────

    /** Returns the raw JDBC connection.  All access must happen on one thread. */
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("Database closed");
            }
        } catch (SQLException e) {
            log.warn("Error closing database: {}", e.getMessage());
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    public static File getDbDirectory() {
        return new File(System.getProperty("user.home"), DB_DIR_NAME);
    }

    public static File getDbFile() {
        return new File(getDbDirectory(), DB_FILE_NAME);
    }
}
