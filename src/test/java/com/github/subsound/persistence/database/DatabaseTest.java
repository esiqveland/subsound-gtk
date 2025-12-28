package com.github.subsound.persistence.database;

import com.github.subsound.integration.ServerClient.ServerType;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

public class DatabaseTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testDatabaseInitializationAndMigration() throws Exception {
        File dbFile = folder.newFile("test.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        Database db = new Database(url);

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement()) {

            // Check if schema_version table exists
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
                Assertions.assertThat(rs.next()).isTrue();
            }

            // Check if servers table exists
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='servers'")) {
                Assertions.assertThat(rs.next()).isTrue();
            }

            // Check version
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                Assertions.assertThat(rs.next()).isTrue();
                Assertions.assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    public void testInsertAndRetrieveServer() throws Exception {
        File dbFile = folder.newFile("test_insert.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        String id = UUID.randomUUID().toString();
        String type = ServerType.SUBSONIC.value();
        String serverUrl = "http://localhost:4040";
        String username = "user";

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(String.format(
                "INSERT INTO servers (id, server_type, server_url, username) VALUES ('%s', '%s', '%s', '%s')",
                id, type, serverUrl, username
            ));
        }

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM servers WHERE id = '" + id + "'")) {
            Assertions.assertThat(rs.next()).isTrue();
            Assertions.assertThat(rs.getString("server_type")).isEqualTo(type);
            Assertions.assertThat(rs.getString("server_url")).isEqualTo(serverUrl);
            Assertions.assertThat(rs.getString("username")).isEqualTo(username);
        }
    }
}
