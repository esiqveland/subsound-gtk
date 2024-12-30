package com.github.subsound.integration;

import com.github.subsound.configuration.Config.ServerConfig;
import com.github.subsound.integration.servers.subsonic.SubsonicClient;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.subsonic.restapi.AlbumID3;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public interface ServerClient {
    // serverId is a client generated or server generated globally unique id for this specific server integration
    //String serverId();

    ListArtists getArtists();
    ArtistInfo getArtistInfo(String artistId);
    AlbumInfo getAlbumInfo(String albumId);
    ListPlaylists getPlaylists();
    Playlist getPlaylist(String playlistId);
    ListStarred getStarred();
    HomeOverview getHomeOverview();
    void starId(String id);
    void unStarId(String id);
    ServerType getServerType();
    boolean testConnection();

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
            int year,
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

    sealed interface ObjectIdentifier {
        record ArtistIdentifier(String artistId) implements ObjectIdentifier {}
        record AlbumIdentifier(String albumId) implements ObjectIdentifier {}
        record PlaylistIdentifier(String playlistId) implements ObjectIdentifier {}
    }


    @RecordBuilder
    record ArtistInfo(
            String id,
            String name,
            int albumCount,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt,
            List<ArtistAlbumInfo> albums,
            Biography biography
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
        public static ArtistAlbumInfo create(AlbumID3 album, Optional<CoverArt> coverArt) {
            return new ArtistAlbumInfo(
                    album.getId(),
                    album.getName(),
                    album.getSongCount(),
                    album.getArtistId(),
                    album.getArtist(),
                    Duration.ofSeconds(album.getDuration()),
                    ofNullable(album.getGenre()).filter(s -> !s.isBlank()),
                    ofNullable(album.getYear()),
                    ofNullable(album.getStarred()).map(ts -> ts.toInstant(ZoneOffset.UTC)),
                    coverArt
            );
        }
    }

    @RecordBuilder
    record CoverArt(
            String serverId,
            String coverArtId,
            URI coverArtLink,
            // The absolute filepath of where we expect to find our local copy of the artwork
            Path coverArtFilePath,
            // identifier is used to possibly connect this CoverArt with some entity, such as a artist or album
            Optional<ObjectIdentifier> identifier
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

    enum PlaylistKind {
        NORMAL, STARRED;
    }
    record PlaylistSimple(
            String id,
            String name,
            PlaylistKind kind,
            Optional<CoverArt> coverArtId,
            int songCount,
            Instant created
    ) {}

    record Playlist(
            String id,
            String name,
            PlaylistKind kind,
            Optional<CoverArt> coverArtId,
            int songCount,
            Instant created,
            List<SongInfo> songs
    ) {}

    @RecordBuilder
    record ListPlaylists(
            List<PlaylistSimple> playlists
    ) {}

    record Biography(
            String original,
            String cleaned,
            String link
    ) {}

    public record HomeOverview(
            List<ArtistAlbumInfo> recent,
            List<ArtistAlbumInfo> newest,
            List<ArtistAlbumInfo> frequent,
            List<ArtistAlbumInfo> highest
    ) {}

    enum ServerType {
        SUBSONIC,
    }

    static ServerClient create(ServerConfig cfg) {
        return switch (cfg.type()) {
            case SUBSONIC -> SubsonicClient.create(cfg);
        };
    }
}
