package org.subsound.integration;

import org.subsound.configuration.Config.ServerConfig;
import org.subsound.integration.lyrics.LyricsResult;
import org.subsound.integration.servers.subsonic.SubsonicClientV2;
import org.subsound.utils.Utils;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.subsonic.restapi.AlbumID3;

import java.io.InputStream;
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
    ServerType getServerType();

    ListArtists getArtists();
    ArtistInfo getArtistInfo(String artistId);
    ArtistInfo getArtistWithAlbums(String artistId);
    AlbumInfo getAlbumInfo(String albumId);
    ListPlaylists getPlaylists();
    Playlist getPlaylist(String playlistId);
    AddSongToPlaylist addToPlaylist(AddSongToPlaylist req);
    ListStarred getStarred();
    SongInfo getSong(String songId);
    HomeOverview getHomeOverview();
    void starId(String id);
    void unStarId(String id);
    boolean testConnection();
    ServerInfo getServerInfo();
    SearchResult search(String query);
    /**
     * search3 with explicit per-type paging. OpenSubsonic servers must support an empty
     * query and return the whole library, which is how {@code SyncService} walks the
     * entire collection in a few paged requests instead of per-artist/per-album calls.
     */
    SearchResult search3(String query, SearchPage page);
    void scrobble(ScrobbleRequest req);
    void nowPlaying(ReportNowPlaying req);
    URI getStreamUri(String songId);
    StreamResponse openStream(TranscodeInfo transcodeInfo);

    /**
     * Returns the {@link TranscodeInfo} that would currently be used to stream this
     * song, based on the live transcode/bitrate settings of this client. Used by
     * {@code DownloadManager} to resolve the cache key for songs that have never
     * been queued — keeps format-resolution out of {@link SongInfo}, which can be
     * stale if a user opened a view before changing transcode settings.
     */
    TranscodeInfo currentTranscodeInfo(SongInfo songInfo);
    // Same as {@link #currentTranscodeInfo(SongInfo)} but for callers that don't have a full SongInfo record.
    TranscodeInfo currentTranscodeInfo(String songId, Optional<Integer> originalBitRate, Duration duration, String suffix);

    CoverArtResponse downloadCoverArt(CoverArt coverArt, int maxSize);
    ScanStatus scanStatus();
    ScanStatus startScan(boolean quickScan);

    /**
     * Fetch the raw getLyricsBySongId response body from the server (OpenSubsonic songLyrics
     * extension). Returns empty when the server does not support lyrics or has none for the song.
     */
    default Optional<String> getSongLyricsRaw(String songId) {
        return Optional.empty();
    }

    /**
     * Display-facing convenience: parsed lyrics only, derived from the raw response body.
     */
    default Optional<LyricsResult> getSongLyrics(String songId) {
        return getSongLyricsRaw(songId).flatMap(ServerClient::parseSongLyrics);
    }

    /**
     * Parse a raw getLyricsBySongId response body (fresh or loaded from the lyrics table).
     * Never throws.
     */
    static Optional<LyricsResult> parseSongLyrics(String rawBody) {
        return SubsonicClientV2.parseLyricsBody(rawBody);
    }

    sealed interface ScanStatus {
        record Scanning(long count) implements ScanStatus {}
        record NotScanning() implements ScanStatus {}
    }

    record ReportNowPlaying(
            Optional<String> songId,
            Optional<Instant> startedAt,
            Optional<Instant> positionAnchorAt,
            long positionMs,
            PlayerState playerState
    ) {
        public enum PlayerState{ PLAYING, PAUSED, STOPPED }
    }
    record ScrobbleRequest(String songId, Instant playedAt) {}
    record SearchResult(
            List<ArtistEntry> artists,
            List<ArtistAlbumInfo> albums,
            List<SongInfo> songs
    ) {}
    record SearchPage(
            int artistCount,
            int artistOffset,
            int albumCount,
            int albumOffset,
            int songCount,
            int songOffset
    ) {
        public static SearchPage artists(int count, int offset) {
            return new SearchPage(count, offset, 0, 0, 0, 0);
        }
        public static SearchPage albums(int count, int offset) {
            return new SearchPage(0, 0, count, offset, 0, 0);
        }
        public static SearchPage songs(int count, int offset) {
            return new SearchPage(0, 0, 0, 0, count, offset);
        }
    }

    record ServerInfo(
            Optional<String> serverType,
            String apiVersion,
            long songCount,
            Optional<Integer> folderCount,
            Optional<Instant> lastScan,
            Optional<String> serverVersion,
            boolean isOpenSubsonic,
            Optional<SubsonicClientV2.OpenSubsonicExtensions> openSubsonicExtensions
    ) {}

    record TranscodedStream(
            String songId,
            URI streamUri
            // Map<String, String> headers
    ){}
    record TranscodeInfo(
            String songId,
            Optional<Integer> originalBitRate,
            int estimatedBitRate,
            Duration duration,
            // the streamFormat is the format we will receive when loading streamUri
            // "mp3" | "ogg"
            String streamFormat
    ) {
        public long estimateContentSize() {
            return Utils.estimateContentLength(duration.getSeconds(), estimatedBitRate);
        }
    }

    record StreamResponse(
            String songId,
            InputStream inputStream,
            long contentLength,   // -1 when unknown
            String contentType
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            inputStream.close();
        }
    }

    record CoverArtResponse(byte[] data, String contentType) {}

    record ArtistId(String id, String name) {}
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
            ArtistId mainArtist,
            Optional<List<ArtistId>> artists,
            Optional<List<ArtistId>> albumArtists,
            String albumId,
            String album,
            Duration duration,
            List<String> moods,
            Optional<Instant> starred,
            Optional<Instant> created,
            Optional<CoverArt> coverArt,
            String suffix,
            TranscodeInfo transcodeInfo,
            URI downloadUri
    ) implements ServerClientSongInfoBuilder.With {
        public String artistName() {
            return mainArtist.name();
        }
        public String artistId() {
            return mainArtist.id();
        }
        public boolean isStarred() {
            return starred.isPresent();
        }

        @Override
        public String toString() {
            return "SongInfo{" +
                    "id='" + id + '\'' +
                    ", artist='" + artists + '\'' +
                    ", title='" + title + '\'' +
                    '}';
        }
    }

    @RecordBuilder
    record AlbumInfo(
            String id,
            String name,
            int songCount,
            Optional<Integer> year,
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
        String getId();
        record ArtistIdentifier(String artistId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return artistId;
            }
        }
        record AlbumIdentifier(String albumId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return albumId;
            }
        }
        record PlaylistIdentifier(String playlistId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return playlistId;
            }
        }
        record SongIdentifier(String songId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return songId;
            }
        }
        static ArtistIdentifier ofArtist(String artistId) {
            return new ArtistIdentifier(artistId);
        }
        static AlbumIdentifier ofAlbum(String albumId) {
            return new AlbumIdentifier(albumId);
        }
        static PlaylistIdentifier ofPlaylist(String playlistId) {
            return new PlaylistIdentifier(playlistId);
        }
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
            Optional<CoverArt> coverArt,
            Optional<Instant> starredAt
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
        NORMAL, STARRED, DOWNLOADED;
    }
    record PlaylistSimple(
            String id,
            String name,
            PlaylistKind kind,
            Optional<CoverArt> coverArtId,
            int songCount,
            Instant changedAt,
            Instant created
    ) {}

    record Playlist(
            String id,
            String name,
            PlaylistKind kind,
            Optional<CoverArt> coverArtId,
            int songCount,
            Instant changedAt,
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
            List<ArtistAlbumInfo> highest,
            List<ArtistAlbumInfo> byYear
    ) {}

    enum ServerType {
        SUBSONIC,
        ;

        public String value() {
            return switch (this) {
                case SUBSONIC -> "SUBSONIC";
            };
        }
    }

    static ServerClient create(ServerConfig cfg) {
        return switch (cfg.type()) {
            case SUBSONIC -> SubsonicClientV2.create(cfg);
        };
    }

    record AddSongToPlaylist(
            String playlistId,
            List<String> songIds
    ){}

    PlaylistSimple playlistCreate(PlaylistCreateRequest req);
    record PlaylistCreateRequest(String name, List<String> songIds){}
    void playlistRename(PlaylistRenameRequest req);
    record PlaylistRenameRequest(String id, String newName) {}
    void playlistDelete(PlaylistDeleteRequest req);
    record PlaylistDeleteRequest(String id) {}
    void playlistRemove(PlaylistRemoveSongRequest req);
    record SongRemoval(int indexInPlaylist, String songId) {}
    record PlaylistRemoveSongRequest(String playlistId, List<SongRemoval> songIds) {}


    enum TranscodeFormat {
        source,
        mp3,
        opus,
    }
    record TranscodeSettings(
            TranscodeFormat format,
            TranscodeBitrate bitrate
    ) {}
    /** A custom HTTP header attached to every request to the server (e.g. Cloudflare Access headers). */
    record HttpHeader(
            String name,
            String value
    ) {}
    sealed interface TranscodeBitrate {
        record SourceQuality() implements TranscodeBitrate {}
        record MaximumBitrate(int maxBitrate) implements TranscodeBitrate {
            public int maxBitrate() {
                return maxBitrate;
            }
            public static MaximumBitrate of(int maxBitrate) {
                return new MaximumBitrate(maxBitrate);
            }
        }
    }
}
