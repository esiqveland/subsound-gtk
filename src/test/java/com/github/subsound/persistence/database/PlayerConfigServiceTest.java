package com.github.subsound.persistence.database;

import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Optional;

public class PlayerConfigServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testSaveAndLoadDefaultConfig() throws Exception {
        Database db = createDb("test_default.db");
        PlayerConfigService service = new PlayerConfigService(db);

        service.savePlayerConfig(PlayerConfig.defaultConfig("server-1"));

        Optional<PlayerConfig> loaded = service.loadPlayerConfig();
        Assertions.assertThat(loaded).isPresent();
        Assertions.assertThat(loaded.get().serverId()).isEqualTo("server-1");
        Assertions.assertThat(loaded.get().playerState().volume()).isEqualTo(1.0);
        Assertions.assertThat(loaded.get().playerState().muted()).isFalse();
        Assertions.assertThat(loaded.get().playerState().currentPlayback()).isNull();
    }

    @Test
    public void testSaveAndLoadWithPlayback() throws Exception {
        Database db = createDb("test_playback.db");
        PlayerConfigService service = new PlayerConfigService(db);

        PlayerConfig config = PlayerConfig.withPlayback(
            "test-server",
            0.8,
            true,
            "song-123",
            60000,
            180000
        );

        service.savePlayerConfig(config);
        Optional<PlayerConfig> loaded = service.loadPlayerConfig();

        Assertions.assertThat(loaded).isPresent();
        Assertions.assertThat(loaded.get().serverId()).isEqualTo("test-server");

        PlayerStateJson state = loaded.get().playerState();
        Assertions.assertThat(state.volume()).isEqualTo(0.8);
        Assertions.assertThat(state.muted()).isTrue();
        Assertions.assertThat(state.currentPlayback()).isNotNull();
        Assertions.assertThat(state.currentPlayback().songId()).isEqualTo("song-123");
        Assertions.assertThat(state.currentPlayback().positionMillis()).isEqualTo(60000);
        Assertions.assertThat(state.currentPlayback().durationMillis()).isEqualTo(180000);
    }

    @Test
    public void testUpdateOverwritesPrevious() throws Exception {
        Database db = createDb("test_update.db");
        PlayerConfigService service = new PlayerConfigService(db);

        service.savePlayerConfig(PlayerConfig.defaultConfig("server-1"));
        service.savePlayerConfig(PlayerConfig.withPlayback("server-2", 0.5, false, "s1", 1000, 2000));

        Optional<PlayerConfig> loaded = service.loadPlayerConfig();
        Assertions.assertThat(loaded).isPresent();
        Assertions.assertThat(loaded.get().serverId()).isEqualTo("server-2");
        Assertions.assertThat(loaded.get().playerState().volume()).isEqualTo(0.5);
    }

    @Test
    public void testDelete() throws Exception {
        Database db = createDb("test_delete.db");
        PlayerConfigService service = new PlayerConfigService(db);

        service.savePlayerConfig(PlayerConfig.defaultConfig("server-1"));
        Assertions.assertThat(service.loadPlayerConfig()).isPresent();

        service.deletePlayerConfig();
        Assertions.assertThat(service.loadPlayerConfig()).isEmpty();
    }

    @Test
    public void testLoadEmptyReturnsEmpty() throws Exception {
        Database db = createDb("test_empty.db");
        PlayerConfigService service = new PlayerConfigService(db);

        Assertions.assertThat(service.loadPlayerConfig()).isEmpty();
    }

    private Database createDb(String name) throws Exception {
        File dbFile = folder.newFile(name);
        return new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }
}
