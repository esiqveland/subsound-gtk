package io.github.jwharm.javagi.examples.playsound.integration;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ServerClient {
    ListArtists getArtists();
    ArtistInfo getArtistInfo(String artistId);
    AlbumInfo getAlbumInfo(String albumId);
    ServerType getServerType();

    record SongInfo(
            String id,
            String title,
            Optional<Integer> trackNumber,
            Optional<Integer> discNumber,
            Optional<Integer> bitRate,
            long size,
            Optional<Integer> year,
            String genre,
            Long playCount,
            Optional<Integer> userRating,
            String artistId,
            String artist,
            String albumId,
            String album,
            Duration duration,
            Optional<Instant> instant,
            Optional<CoverArt> coverArt,
            URI downloadUri,
            URI streamUri
    ) {
    }

    record AlbumInfo(
            String id,
            String name,
            int songCount,
            String artistId,
            String artistName,
            Duration duration,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt,
            List<SongInfo> songs
    ) {
        public boolean isStarred() {
            return starredAt.isPresent();
        }
    }

    record ArtistInfo(
            String id,
            String name,
            int albumCount,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt,
            List<ArtistAlbumInfo> albums
    ) {
        public Duration totalPlayTime() {
            return Duration.ofSeconds(this.albums.stream().mapToLong(a -> a.duration.toSeconds()).sum());
        }

        public int songCount() {
            return this.albums.stream().mapToInt(a -> a.songCount).sum();
        }
    }

    record ArtistAlbumInfo(
            String id,
            String name,
            int songCount,
            String artistId,
            String artistName,
            Duration duration,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt
    ) {
        public boolean isStarred() {
            return starredAt.isPresent();
        }
    }

    record CoverArt(
            String coverArtId,
            URI coverArtLink
    ) {
    }

    record ArtistEntry(
            String id,
            String name,
            int albumCount,
            Optional<CoverArt> coverArt
    ) {
    }

    record ListArtists(
            List<ArtistEntry> list
    ) {
    }

    enum ServerType {
        SUBSONIC,
    }
}
