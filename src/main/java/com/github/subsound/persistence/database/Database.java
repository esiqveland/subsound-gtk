package com.github.subsound.persistence.database;

import com.github.subsound.integration.platform.PortalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Database {
    private static final Logger logger = LoggerFactory.getLogger(Database.class);
    private static final String DB_NAME = "subsound.db";
    private final String url;

    public Database() {
        String dataDir = PortalUtils.getUserDataDir();
        File subsoundDir = new File(dataDir, "subsound");
        if (!subsoundDir.exists()) {
            subsoundDir.mkdirs();
        }
        File dbFile = new File(subsoundDir, DB_NAME);
        this.url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        logger.info("Database URL: {}", url);
        initialize();
    }

    // Constructor for testing
    public Database(String url) {
        this.url = url;
        initialize();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void initialize() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int currentVersion = getCurrentVersion(conn);
                logger.info("Current database version: {}", currentVersion);
                List<Migration> migrations = getMigrations();
                for (Migration migration : migrations) {
                    if (migration.version() > currentVersion) {
                        logger.info("Applying migration to version {}", migration.version());
                        migration.apply(conn);
                        updateVersion(conn, migration.version());
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Failed to initialize database", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to database", e);
        }
    }

    private int getCurrentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY)");
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private void updateVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO schema_version (version) VALUES (" + version + ")");
        }
    }

    private List<Migration> getMigrations() {
        List<Migration> migrations = new ArrayList<>();
        migrations.add(new MigrationV1());
        migrations.add(new MigrationV2());
        migrations.add(new MigrationV3());
        migrations.add(new MigrationV4());
        return migrations;
    }

    public interface Migration {
        int version();
        void apply(Connection conn) throws SQLException;
    }

    private static class MigrationV1 implements Migration {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS servers (
                        id TEXT PRIMARY KEY,
                        is_primary BOOL NOT NULL,
                        server_type TEXT NOT NULL,
                        server_url TEXT NOT NULL,
                        username TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """);
            }
        }
    }

    private static class MigrationV2 implements Migration {
        @Override
        public int version() {
            return 2;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS artists (
                        id TEXT PRIMARY KEY,
                        server_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        album_count INTEGER NOT NULL,
                        starred_at INTEGER,
                        cover_art_id TEXT,
                        biography BLOB,
                        created_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """);
            }
        }
    }

    private static class MigrationV3 implements Migration {
        @Override
        public int version() {
            return 3;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS albums (
                        id TEXT,
                        server_id TEXT NOT NULL,
                        artist_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        song_count INTEGER,
                        year INTEGER,
                        artist_name TEXT NOT NULL,
                        duration_ms INTEGER,
                        starred_at_ms INTEGER,
                        cover_art_id TEXT,
                        added_at_ms INTEGER NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        PRIMARY KEY (id, server_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV4 implements Migration {
        @Override
        public int version() {
            return 4;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS songs (
                        id TEXT,
                        server_id TEXT NOT NULL,
                        album_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        year INTEGER,
                        artist_id TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        duration_ms INTEGER,
                        starred_at_ms INTEGER,
                        cover_art_id TEXT,
                        created_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (id, server_id)
                    )
                """);
            }
        }
    }

    public Connection openConnection() throws SQLException {
        return getConnection();
    }
}
