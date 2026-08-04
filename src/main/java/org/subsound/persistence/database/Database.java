package org.subsound.persistence.database;

import org.subsound.configuration.constants.Constants;
import org.subsound.integration.platform.PortalUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sqlite.SQLiteDataSource;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Database {
    private static final Logger logger = LoggerFactory.getLogger(Database.class);
    private static final String DB_NAME = "subsound.db";
    private final HikariDataSource dataSource;

    public Database() {
        String dataDir = PortalUtils.getUserDataDir();
        File subsoundDir = new File(dataDir, Constants.APP_ID);
        if (!subsoundDir.exists()) {
            subsoundDir.mkdirs();
        }
        File dbFile = new File(subsoundDir, DB_NAME);
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        logger.info("Database URL: {}", url);
        logger.warn("opening database file path={}", dbFile.getAbsolutePath());
        this.dataSource = createDataSource(url);
        initialize();
    }

    // Constructor for testing
    public Database(String url) {
        this.dataSource = createDataSource(url);
        initialize();
    }

    private HikariDataSource createDataSource(String url) {
        org.sqlite.SQLiteConfig sqliteConfig = new org.sqlite.SQLiteConfig();
        sqliteConfig.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        // Writers still serialize at the SQLite level; busy_timeout makes a blocked
        // writer wait instead of failing immediately with SQLITE_BUSY.
        sqliteConfig.setBusyTimeout(5000);
        // BEGIN IMMEDIATE for explicit transactions: take the write lock up front so
        // read-then-write transactions can't fail with SQLITE_BUSY_SNAPSHOT mid-way.
        sqliteConfig.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        // The *_fts external-content indexes are maintained by triggers. INSERT OR REPLACE
        // only fires the DELETE trigger for the replaced row when recursive_triggers is on;
        // without it the FTS index silently accumulates stale duplicate entries.
        sqliteConfig.enableRecursiveTriggers(true);
        SQLiteDataSource ds = new SQLiteDataSource(sqliteConfig);
        ds.setUrl(url);
        var cfg = new HikariConfig();
        //cfg.setJdbcUrl(url);
        cfg.setDataSource(ds);
        // WAL mode supports many concurrent readers plus one writer, so a small pool
        // keeps reads (e.g. playback lookups) from queueing behind large sync writes:
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setAutoCommit(true);
        //cfg.setTransactionIsolation();
        cfg.setConnectionTimeout(10000);
        return new HikariDataSource(cfg);
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
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
        migrations.add(new MigrationV5());
        migrations.add(new MigrationV6());
        migrations.add(new MigrationV7());
        migrations.add(new MigrationV8());
        migrations.add(new MigrationV9());
        migrations.add(new MigrationV10());
        migrations.add(new MigrationV11());
        migrations.add(new MigrationV12());
        migrations.add(new MigrationV13());
        migrations.add(new MigrationV14());
        migrations.add(new MigrationV15());
        migrations.add(new MigrationV16());
        migrations.add(new MigrationV17());
        migrations.add(new MigrationV18());
        migrations.add(new MigrationV19());
        migrations.add(new MigrationV20());
        migrations.add(new MigrationV21());
        migrations.add(new MigrationV22());
        migrations.add(new MigrationV23());
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

    private static class MigrationV5 implements Migration {
        @Override
        public int version() {
            return 5;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS download_queue (
                        song_id TEXT,
                        server_id TEXT NOT NULL,
                        status TEXT NOT NULL, -- PENDING, DOWNLOADING, COMPLETED, FAILED
                        progress REAL DEFAULT 0.0,
                        added_at INTEGER DEFAULT (strftime('%s', 'now')),
                        error_message TEXT,
                        stream_uri TEXT,
                        stream_format TEXT,
                        original_size INTEGER,
                        original_bitrate INTEGER,
                        estimated_bitrate INTEGER,
                        duration_seconds INTEGER,
                        PRIMARY KEY (song_id, server_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV6 implements Migration {
        @Override
        public int version() {
            return 6;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE download_queue ADD COLUMN checksum TEXT");
            }
        }
    }

    private static class MigrationV7 implements Migration {
        @Override
        public int version() {
            return 7;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_config (
                        config_key INTEGER PRIMARY KEY,
                        config_json TEXT NOT NULL,
                        updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """);
            }
        }
    }

    private static class MigrationV8 implements Migration {
        @Override
        public int version() {
            return 8;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                // Add missing song fields
                stmt.execute("ALTER TABLE songs ADD COLUMN track_number INTEGER");
                stmt.execute("ALTER TABLE songs ADD COLUMN disc_number INTEGER");
                stmt.execute("ALTER TABLE songs ADD COLUMN bit_rate INTEGER");
                stmt.execute("ALTER TABLE songs ADD COLUMN size INTEGER DEFAULT 0");
                stmt.execute("ALTER TABLE songs ADD COLUMN genre TEXT DEFAULT ''");
                stmt.execute("ALTER TABLE songs ADD COLUMN suffix TEXT DEFAULT ''");

                // Add missing album fields
                stmt.execute("ALTER TABLE albums ADD COLUMN genre TEXT");

                // Playlist tables
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS playlists (
                        id TEXT,
                        server_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        song_count INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        cover_art_id TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (id, server_id)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        playlist_id TEXT NOT NULL,
                        server_id TEXT NOT NULL,
                        song_id TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        PRIMARY KEY (playlist_id, server_id, song_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV9 implements Migration {
        @Override
        public int version() {
            return 9;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS scrobbles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        server_id TEXT NOT NULL,
                        song_id TEXT NOT NULL,
                        played_at_ms INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
                        status TEXT NOT NULL DEFAULT 'PENDING'
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_scrobbles_server_status ON scrobbles (server_id, status)");
            }
        }
    }

    private static class MigrationV10 implements Migration {
        @Override
        public int version() {
            return 10;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE songs ADD COLUMN album_name TEXT NOT NULL DEFAULT ''");
            }
        }
    }

    static class MigrationV11 implements Migration {
        @Override
        public int version() { return 11; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS playlist_songs_new (
                            playlist_id TEXT NOT NULL,
                            server_id   TEXT NOT NULL,
                            song_id     TEXT NOT NULL,
                            sort_order  INTEGER NOT NULL,
                            PRIMARY KEY (playlist_id, server_id, sort_order)
                        )
                        """);
                stmt.executeUpdate("INSERT INTO playlist_songs_new SELECT * FROM playlist_songs");
                stmt.executeUpdate("DROP TABLE playlist_songs");
                stmt.executeUpdate("ALTER TABLE playlist_songs_new RENAME TO playlist_songs");
            }
        }
    }

    static class MigrationV12 implements Migration {
        @Override
        public int version() { return 12; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE servers ADD COLUMN tls_skip_verify BOOL NOT NULL DEFAULT 0");
            }
        }
    }

    static class MigrationV13 implements Migration {
        @Override
        public int version() { return 13; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE servers ADD COLUMN audio_format TEXT DEFAULT NULL");
                stmt.execute("ALTER TABLE servers ADD COLUMN audio_bitrate INTEGER DEFAULT NULL");
            }
        }
    }

    static class MigrationV14 implements Migration {
        @Override
        public int version() { return 14; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS play_queue_items (
                        server_id TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        song_id TEXT NOT NULL,
                        queue_item_id TEXT NOT NULL,
                        queue_kind TEXT NOT NULL DEFAULT 'AUTOMATIC',
                        original_order INTEGER NOT NULL DEFAULT 0,
                        shuffle_order INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (server_id, sort_order)
                    )
                """);
            }
        }
    }

    static class MigrationV15 implements Migration {
        @Override
        public int version() { return 15; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE songs ADD COLUMN artists_json TEXT");
                stmt.execute("ALTER TABLE songs ADD COLUMN album_artists_json TEXT");
                stmt.execute("ALTER TABLE songs ADD COLUMN moods_json TEXT");
            }
        }
    }

    static class MigrationV16 implements Migration {
        @Override
        public int version() { return 16; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                // Covering the hot query shapes; download_queue's PK starts with song_id,
                // so (server_id, status) lookups need their own index.
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_songs_server_album ON songs (server_id, album_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_songs_server_starred ON songs (server_id, starred_at_ms) WHERE starred_at_ms IS NOT NULL");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_albums_server_artist ON albums (server_id, artist_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_download_queue_server_status ON download_queue (server_id, status)");
            }
        }
    }

    static class MigrationV17 implements Migration {
        @Override
        public int version() { return 17; }

        @Override
        public void apply(Connection conn) throws SQLException {
            // servers.created_at is read/written as epoch millis from Java, but the old
            // schema default was strftime('%s','now') (seconds). Rebuild with a millis
            // default and normalize any rows that were created via the old default.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE servers_new (
                        id TEXT PRIMARY KEY,
                        is_primary BOOL NOT NULL,
                        server_type TEXT NOT NULL,
                        server_url TEXT NOT NULL,
                        username TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                        tls_skip_verify BOOL NOT NULL DEFAULT 0,
                        audio_format TEXT DEFAULT NULL,
                        audio_bitrate INTEGER DEFAULT NULL
                    )
                """);
                stmt.execute("""
                    INSERT INTO servers_new (id, is_primary, server_type, server_url, username, created_at, tls_skip_verify, audio_format, audio_bitrate)
                    SELECT id, is_primary, server_type, server_url, username, created_at, tls_skip_verify, audio_format, audio_bitrate FROM servers
                """);
                stmt.execute("DROP TABLE servers");
                stmt.execute("ALTER TABLE servers_new RENAME TO servers");
                // Values below ~1973 in millis can only be second-resolution timestamps:
                stmt.execute("UPDATE servers SET created_at = created_at * 1000 WHERE created_at IS NOT NULL AND created_at < 100000000000");
            }
        }
    }

    static class MigrationV18 implements Migration {
        @Override
        public int version() { return 18; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                // Stores the full raw getLyricsBySongId response body so it can be
                // re-parsed later (new features, offline mode). JSON declared type has
                // TEXT affinity; the json_valid CHECK keeps stored rows re-parseable.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lyrics (
                        server_id TEXT NOT NULL,
                        song_id TEXT NOT NULL,
                        raw_json JSON NOT NULL CHECK (json_valid(raw_json)),
                        fetched_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (server_id, song_id)
                    )
                """);
            }
        }
    }

    static class MigrationV19 implements Migration {
        @Override
        public int version() { return 19; }

        @Override
        public void apply(Connection conn) throws SQLException {
            // Offline search: pre-normalized search_text (see SearchNormalizer) indexed by
            // FTS5 external-content tables kept in sync with triggers. Normalization happens
            // in Java, so the column is backfilled here row by row before the index is built.
            addSearchIndex(conn, "artists", "name");
            addSearchIndex(conn, "albums", "name", "artist_name");
            addSearchIndex(conn, "songs", "name", "artist_name", "album_name");
        }

        private void addSearchIndex(Connection conn, String table, String... sourceColumns) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + table + " ADD COLUMN search_text TEXT");
            }
            backfillSearchText(conn, table, sourceColumns);
            String fts = table + "_fts";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE VIRTUAL TABLE %s USING fts5(search_text, content='%s', content_rowid='rowid')"
                        .formatted(fts, table));
                stmt.execute("INSERT INTO %1$s(%1$s) VALUES('rebuild')".formatted(fts));
                stmt.execute("""
                        CREATE TRIGGER %1$s_ai AFTER INSERT ON %2$s BEGIN
                          INSERT INTO %1$s(rowid, search_text) VALUES (new.rowid, new.search_text);
                        END
                        """.formatted(fts, table));
                stmt.execute("""
                        CREATE TRIGGER %1$s_ad AFTER DELETE ON %2$s BEGIN
                          INSERT INTO %1$s(%1$s, rowid, search_text) VALUES ('delete', old.rowid, old.search_text);
                        END
                        """.formatted(fts, table));
                stmt.execute("""
                        CREATE TRIGGER %1$s_au AFTER UPDATE ON %2$s BEGIN
                          INSERT INTO %1$s(%1$s, rowid, search_text) VALUES ('delete', old.rowid, old.search_text);
                          INSERT INTO %1$s(rowid, search_text) VALUES (new.rowid, new.search_text);
                        END
                        """.formatted(fts, table));
            }
        }

        private void backfillSearchText(Connection conn, String table, String[] sourceColumns) throws SQLException {
            String select = "SELECT rowid, " + String.join(", ", sourceColumns) + " FROM " + table;
            String update = "UPDATE " + table + " SET search_text = ? WHERE rowid = ?";
            try (Statement sel = conn.createStatement();
                 ResultSet rs = sel.executeQuery(select);
                 PreparedStatement upd = conn.prepareStatement(update)) {
                int batched = 0;
                while (rs.next()) {
                    long rowid = rs.getLong(1);
                    String[] fields = new String[sourceColumns.length];
                    for (int i = 0; i < sourceColumns.length; i++) {
                        fields[i] = rs.getString(i + 2);
                    }
                    upd.setString(1, SearchNormalizer.normalizeIndexText(fields));
                    upd.setLong(2, rowid);
                    upd.addBatch();
                    batched++;
                    if (batched % 500 == 0) {
                        upd.executeBatch();
                    }
                }
                upd.executeBatch();
            }
        }
    }

    static class MigrationV20 implements Migration {
        @Override
        public int version() { return 20; }

        @Override
        public void apply(Connection conn) throws SQLException {
            // Custom HTTP headers attached to all requests (e.g. Cloudflare Access).
            // Stored as a JSON array of {name, value}; null means no custom headers.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE servers ADD COLUMN custom_headers TEXT DEFAULT NULL");
            }
        }
    }

    static class MigrationV21 implements Migration {
        @Override
        public int version() { return 21; }

        @Override
        public void apply(Connection conn) throws SQLException {
            // General, ordered, persistent queue of server-side operations (e.g. star/unstar)
            // recorded while offline and replayed once connectivity returns. The auto-increment
            // id defines replay order. executed_at_ms is the last time we ran the op against the
            // server (any attempt); completed_at_ms is when it succeeded. Invariant: completed_at_ms
            // is non-null iff status is COMPLETED.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS server_operations_queue (
                            id              INTEGER PRIMARY KEY AUTOINCREMENT,
                            server_id       TEXT    NOT NULL,
                            operation_type  TEXT    NOT NULL,
                            payload         TEXT    NOT NULL,
                            status          TEXT    NOT NULL DEFAULT 'PENDING',
                            created_at_ms   INTEGER NOT NULL,
                            executed_at_ms  INTEGER DEFAULT NULL,
                            completed_at_ms INTEGER DEFAULT NULL,
                            CHECK ((completed_at_ms IS NULL) = (status != 'COMPLETED'))
                        )
                        """);
                stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_server_operations_queue_pending
                            ON server_operations_queue (server_id, status, id)
                        """);
            }
        }
    }

    static class MigrationV22 implements Migration {
        @Override
        public int version() { return 22; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                // (1) Why a song is in the download set: 'ADDED_BY_USER' (explicit user download)
                // vs 'PLAYLIST_SYNC' (pulled in by an offline-marked playlist). NULL for rows that
                // predate this migration. A PLAYLIST_SYNC enqueue never downgrades an ADDED_BY_USER
                // row; a manual add escalates to ADDED_BY_USER. Groundwork for a future reverse-sync
                // that demotes playlist-only songs back to CACHED when they leave the playlist.
                stmt.execute("ALTER TABLE download_queue ADD COLUMN source TEXT");

                // (2) Playlists (and the synthetic Starred playlist, under the sentinel id
                // '__starred__') the user has marked "available offline". Presence of a row means
                // enabled; toggling off deletes the row (downloads are left intact). watermark_ms is
                // the last-synced high-water mark (a NORMAL playlist's changedAt, or the max starred
                // timestamp for Starred); NULL forces a full resync on the next sync pass.
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS offline_playlists (
                            playlist_id   TEXT    NOT NULL,
                            server_id     TEXT    NOT NULL,
                            kind          TEXT    NOT NULL,
                            watermark_ms  INTEGER,
                            created_at_ms INTEGER NOT NULL,
                            updated_at_ms INTEGER NOT NULL,
                            PRIMARY KEY (playlist_id, server_id)
                        )
                        """);
            }
        }
    }

    static class MigrationV23 implements Migration {
        @Override
        public int version() { return 23; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                // ReplayGain loudness-normalization metadata (OpenSubsonic). Gains in dB, peaks
                // linear. NULL for rows synced before this migration; such songs simply play with
                // the configured fallback gain until re-synced.
                stmt.execute("ALTER TABLE songs ADD COLUMN rg_track_gain REAL");
                stmt.execute("ALTER TABLE songs ADD COLUMN rg_album_gain REAL");
                stmt.execute("ALTER TABLE songs ADD COLUMN rg_track_peak REAL");
                stmt.execute("ALTER TABLE songs ADD COLUMN rg_album_peak REAL");

                // Per-server ReplayGain settings (enabled/mode/pre-amp/fallback), stored as one
                // JSON blob. NULL for existing servers -> treated as defaults.
                stmt.execute("ALTER TABLE servers ADD COLUMN replaygain_config_json TEXT");
            }
        }
    }

    public Connection openConnection() throws SQLException {
        return getConnection();
    }

    public void close() {
        dataSource.close();
    }

    public Path getDbFilePath() {
        String dataDir = PortalUtils.getUserDataDir();
        File subsoundDir = new File(dataDir, Constants.APP_ID);
        return new File(subsoundDir, DB_NAME).toPath();
    }
}
