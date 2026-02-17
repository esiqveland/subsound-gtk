package com.github.subsound.integration;

import com.github.subsound.integration.ServerClient.CoverArt;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.ServerClient.TranscodeInfo;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class SongInfoFactory {
    public final ConcurrentHashMap<String, SongInfo> songs = new ConcurrentHashMap<>();

    public SongInfoFactory() {}

    public SongInfo getSongById(String key) {
        return songs.get(key);
    }

    public SongInfo newRandomSongInfo() {
        var songInfo = createRandomSongInfo();
        songs.put(songInfo.id(), songInfo);
        return songInfo;
    }

    public static SongInfo createRandomSongInfo() {
        String id = UUID.randomUUID().toString();
        String title = "Song " + id.substring(0, 8);
        String artistId = UUID.randomUUID().toString();
        String artist = "Artist " + artistId.substring(0, 8);
        String albumId = UUID.randomUUID().toString();
        String album = "Album " + albumId.substring(0, 8);
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        return ServerClientSongInfoBuilder.builder()
                .id(id)
                .title(title)
                .trackNumber(Optional.of(random.nextInt(1, 15)))
                .discNumber(Optional.of(1))
                .bitRate(Optional.of(320))
                .size(random.nextLong(1000000, 10000000))
                .year(Optional.of(random.nextInt(1950, 2024)))
                .genre("Genre")
                .playCount(random.nextLong(0, 1000))
                .userRating(Optional.of(random.nextInt(1, 6)))
                .artistId(artistId)
                .artist(artist)
                .albumId(albumId)
                .album(album)
                .duration(Duration.ofSeconds(random.nextInt(120, 600)))
                .starred(random.nextBoolean() ? Optional.of(Instant.now()) : Optional.empty())
                .coverArt(Optional.empty())
                .suffix("mp3")
                .transcodeInfo(new TranscodeInfo(
                        Optional.of(320),
                        128,
                        Duration.ofSeconds(random.nextInt(120, 600)),
                        "mp3",
                        URI.create("https://example.com/stream/" + id)
                ))
                .downloadUri(URI.create("https://example.com/download/" + id))
                .build();
    }
}
