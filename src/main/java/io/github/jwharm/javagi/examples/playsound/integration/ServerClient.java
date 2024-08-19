package io.github.jwharm.javagi.examples.playsound.integration;

import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderFull;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ServerClient {
    // serverId is a client generated or server generated globally unique id for this specific server integration
    //String serverId();

    ListArtists getArtists();
    ArtistInfo getArtistInfo(String artistId);
    AlbumInfo getAlbumInfo(String albumId);
    ListStarred getStarred();
    void starId(String id);
    void unStarId(String id);
    ServerType getServerType();

    @RecordBuilderFull
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
            Optional<Instant> starred,
            Optional<CoverArt> coverArt,
            String suffix,
            String streamSuffix,
            URI downloadUri,
            URI streamUri
    ) implements ServerClientSongInfoBuilder.With {
        public boolean isStarred() {
            return starred.isPresent();
        }
    }

    @RecordBuilder
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

        public Duration totalPlayTime() {
            return Duration.ofSeconds(this.songs.stream().mapToLong(a -> a.duration.toSeconds()).sum());
        }
    }

    @RecordBuilder
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

    @RecordBuilder
    record ArtistAlbumInfo(
            String id,
            String name,
            int songCount,
            String artistId,
            String artistName,
            Duration duration,
            Optional<String> genre,
            Optional<Integer> year,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt
    ) {
        public boolean isStarred() {
            return starredAt.isPresent();
        }
    }

    @RecordBuilder
    record CoverArt(
            String serverId,
            String coverArtId,
            URI coverArtLink
    ) {
    }

    @RecordBuilder
    record ArtistEntry(
            String id,
            String name,
            int albumCount,
            Optional<CoverArt> coverArt
    ) {
    }

    @RecordBuilder
    record ListArtists(
            List<ArtistEntry> list
    ) {
    }

    @RecordBuilder
    record ListStarred(
            List<SongInfo> songs
    ) {
    }

    enum ServerType {
        SUBSONIC,
    }
}
