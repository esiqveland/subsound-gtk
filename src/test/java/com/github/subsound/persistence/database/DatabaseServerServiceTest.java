package com.github.subsound.persistence.database;

import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseServerServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testArtistOperations() throws Exception {
        File dbFile = folder.newFile("test_artist_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);
        DatabaseServerService service = new DatabaseServerService(db);

        UUID serverId1 = UUID.randomUUID();
        UUID serverId2 = UUID.randomUUID();

        Artist artist1 = new Artist(
                "artist-1",
                serverId1,
                "Artist One",
                5,
                Optional.of(Instant.now().truncatedTo(ChronoUnit.MILLIS)),
                Optional.of("cover-1"),
                Optional.of("Bio 1".getBytes())
        );

        Artist artist2 = new Artist(
                "artist-2",
                serverId1,
                "Artist Two",
                10,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );

        Artist artist3 = new Artist(
                "artist-3",
                serverId2,
                "Artist Three",
                2,
                Optional.empty(),
                Optional.of("cover-3"),
                Optional.empty()
        );

        // Test insert
        service.insert(artist1);
        service.insert(artist2);
        service.insert(artist3);

        // Test listArtists for serverId1
        List<Artist> artistsServer1 = service.listArtists(serverId1);
        Assertions.assertThat(artistsServer1).hasSize(2);
        Assertions.assertThat(artistsServer1)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(artist1, artist2);

        // Test listArtists for serverId2
        List<Artist> artistsServer2 = service.listArtists(serverId2);
        Assertions.assertThat(artistsServer2).hasSize(1);
        Assertions.assertThat(artistsServer2)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(artist3);

        // Test getArtistById
        Optional<Artist> foundArtist = service.getArtistById("artist-1");
        Assertions.assertThat(foundArtist).isPresent();
        Assertions.assertThat(foundArtist.get())
                .usingRecursiveComparison()
                .isEqualTo(artist1);

        // Test getArtistById with non-existent id
        Optional<Artist> notFoundArtist = service.getArtistById("non-existent");
        Assertions.assertThat(notFoundArtist).isEmpty();
    }
}
