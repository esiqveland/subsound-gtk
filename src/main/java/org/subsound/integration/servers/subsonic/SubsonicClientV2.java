package org.subsound.integration.servers.subsonic;

import com.google.gson.annotations.SerializedName;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.configuration.Config.ServerConfig;
import org.subsound.configuration.constants.Constants;
import org.subsound.integration.ServerClient;
import org.subsound.integration.lyrics.LrclibClient.LyricLine;
import org.subsound.integration.lyrics.LyricsResult;
import org.subsound.integration.ServerClient.ObjectIdentifier.AlbumIdentifier;
import org.subsound.integration.ServerClient.ObjectIdentifier.ArtistIdentifier;
import org.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import org.subsound.utils.Lazy;
import org.subsound.utils.Utils;
import org.subsound.utils.javahttp.TextUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Optional.ofNullable;
import static org.subsound.app.state.AppManager.SERVER_ID;
import static org.subsound.integration.servers.subsonic.SubsonicClientV2.OpenSubsonicExtensions.OpenSubsonicFeature.FormPost;
import static org.subsound.integration.servers.subsonic.SubsonicClientV2.OpenSubsonicExtensions.OpenSubsonicFeature.PlaybackReport;
import static org.subsound.integration.servers.subsonic.SubsonicClientV2.OpenSubsonicExtensions.OpenSubsonicFeature.SongLyrics;
import static org.subsound.persistence.ThumbnailCache.toCachePath;
import static org.subsound.utils.LogUtils.loggingInterceptor;
import static org.subsound.utils.LogUtils.userAgentInterceptor;

/**
 * JSON-based Subsonic API client using OkHttpClient.
 * Replaces the feign-based SubsonicClient with direct HTTP + Gson parsing.
 * Uses OkHttp's callTimeout for proper end-to-end request timeouts.
 */
public class SubsonicClientV2 implements ServerClient {
    private final Logger log = LoggerFactory.getLogger(SubsonicClientV2.class);
    private static final String API_VERSION = "1.16.1";
    private static final HexFormat HEX = HexFormat.of().withLowerCase();
    private static final Random RANDOM = new Random();

    private final String serverId;
    private final Path dataDir;
    private final URI serverUri;
    private final String username;
    private final String password;
    private final String streamFormat;  // "" = source
    private final int streamBitRate;    // 0 = source
    private final OkHttpClient httpClient;
    private final OkHttpClient streamingHttpClient;
    private final Lazy<Optional<OpenSubsonicExtensions>> supportedExtensions;

    public SubsonicClientV2(ServerConfig cfg) {
        this.serverId = cfg.id();
        this.dataDir = cfg.dataDir();
        this.serverUri = URI.create(cfg.url());
        this.username = cfg.username();
        this.password = cfg.password();
        this.supportedExtensions = Lazy.of(() -> {
            // TODO: how to handle offline mode
            try {
                return Optional.of(getOpenSubsonicExtensions());
            } catch (Exception e) {
                log.warn("Failed to load supported OpenSubsonic extensions", e);
                return Optional.empty();
            }
        });

        var httpBuilder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(userAgentInterceptor(Constants.USER_AGENT))
                .addInterceptor(loggingInterceptor(log));

        if (cfg.tlsSkipVerify()) {
            configureTrustAllCerts(httpBuilder);
        }

        this.httpClient = httpBuilder.build();
        this.streamingHttpClient = this.httpClient.newBuilder()
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build();

        // Derive transcode settings from config (same logic as SubsonicClient.createSettings)
        TranscodeFormat fmt = cfg.audioFormat() != null ? cfg.audioFormat() : TranscodeFormat.source;
        this.streamFormat = switch (fmt) {
            case source -> "";
            case mp3 -> "mp3";
            case opus -> "opus";
        };
        this.streamBitRate = switch (cfg.audioBitrate()) {
            case null -> 0;
            case TranscodeBitrate.SourceQuality _ -> 0;
            case TranscodeBitrate.MaximumBitrate(var kbps) -> kbps;
        };
    }

    public boolean isPostFormSupported() {
        var extOpt = this.supportedExtensions.get();
        if (extOpt.isEmpty()) {
            return false;
        } else {
            return extOpt.get().supports(FormPost);
        }
    }

    public boolean isLyricsSupported() {
        return this.supportedExtensions.get()
                .map(exts -> exts.supports(SongLyrics))
                .orElse(false);
    }

    public boolean isFeatureSupported(OpenSubsonicExtensions.OpenSubsonicFeature feature) {
        return this.supportedExtensions.get()
                .map(exts -> exts.supports(feature))
                .orElse(false);
    }

    private static void configureTrustAllCerts(OkHttpClient.Builder builder) {
        try {
            X509TrustManager trustAllManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAllManager);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to configure TLS skip verify", e);
        }
    }

    private String generateSalt() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    static String md5(String input) {
        try {
            var md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpUrl buildUrl(String path, Map<String, String> extraParams) {
        var salt = generateSalt();
        var token = md5(password + salt);
        var pathPrefix = Utils.removeTrailingSlash(this.serverUri.getPath());
        var requestPath = pathPrefix + path;
        var builder = HttpUrl.get(this.serverUri).newBuilder(requestPath)
                .setQueryParameter("u", username)
                .setQueryParameter("s", salt)
                .setQueryParameter("t", token)
                .setQueryParameter("c", Constants.APP_ID)
                .setQueryParameter("f", "json")
                .setQueryParameter("v", API_VERSION);
        for (var param : extraParams.entrySet()) {
            builder.setQueryParameter(param.getKey(), param.getValue());
        }
        return builder.build();
    }

    private URI buildUri(String path) {
        return buildUrl(path, Map.of()).uri();
    }

    private URI buildUri(String path, Map<String, String> extraParams) {
        return buildUrl(path, extraParams).uri();
    }

    private HttpUrl buildUrlMulti(String path, Map<String, String> singleParams, Map<String, List<String>> multiParams) {
        var salt = generateSalt();
        var token = md5(password + salt);
        var pathPrefix = Utils.removeTrailingSlash(this.serverUri.getPath());
        var requestPath = pathPrefix + path;
        var builder = HttpUrl.get(this.serverUri).newBuilder(requestPath)
                .setQueryParameter("u", username)
                .setQueryParameter("s", salt)
                .setQueryParameter("t", token)
                .setQueryParameter("c", Constants.APP_ID)
                .setQueryParameter("f", "json")
                .setQueryParameter("v", API_VERSION);
        for (var param : singleParams.entrySet()) {
            builder.setQueryParameter(param.getKey(), param.getValue());
        }
        for (var param : multiParams.entrySet()) {
            for (var value : param.getValue()) {
                builder.addQueryParameter(param.getKey(), value);
            }
        }
        return builder.build();
    }

    public static class HttpException extends RuntimeException {
        public final int statusCode;
        public final String body;
        public HttpException(int statusCode, String body) {
            super("HTTP status=" + statusCode + " body=" + body);
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private <T> T fetchJson(String path, Map<String, String> params, Class<T> responseClass) {
        var url = buildUrl(path, params);

        Request request;
        if (isPostFormSupported()) {
            var formBuilder = new FormBody.Builder();
            for (int i = 0; i < url.querySize(); i++) {
                String name = url.queryParameterName(i);
                String value = url.queryParameterValue(i);
                formBuilder.add(name, value != null ? value : "");
            }
            url = url.newBuilder().query("").build();
            request = new Request.Builder().url(url).post(formBuilder.build()).build();
        } else {
            request = new Request.Builder().url(url).get().build();
        }
        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new HttpException(response.code(), body);
            }
            return Utils.fromJson(body, responseClass);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void fetchVoid(String path, Map<String, String> params) {
        fetchJson(path, params, PingResponseJson.class);
    }

    private void fetchVoidMulti(
            String path,
            Map<String, String> singleParams,
            Map<String, List<String>> multiParams
    ) {
        var url = buildUrlMulti(path, singleParams, multiParams);
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new HttpException(response.code(), body);
            }
            var parsed = Utils.fromJson(body, PingResponseJson.class);
            if (!"ok".equalsIgnoreCase(parsed.subsonicResponse.status)) {
                throw new RuntimeException("Subsonic error from " + path + ": " + body);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T extends HasStatus> T fetchAndCheck(String path, Map<String, String> params, Class<T> responseClass) {
        var parsed = fetchJson(path, params, responseClass);
        parsed.checkOk(path);
        return parsed;
    }

    interface HasStatus {
        String getStatus();
        default void checkOk(String path) {
            if (!"ok".equalsIgnoreCase(getStatus())) {
                throw new RuntimeException("Subsonic error from " + path + ": status=" + getStatus());
            }
        }
    }

    static class PingResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        PingInner subsonicResponse;
        static class PingInner {
            String status;
            String version;
            String type;
            String serverVersion;
            boolean openSubsonic;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    // -- Child (song) --
    record OSReplayGain(
            double trackGain,
            double albumGain,
            double trackPeak,
            double albumPeak,
            double baseGain
    ){}
    record OSArtistJson(
            String id,
            String name
    ){}
    record ChildJson(
            String id,
            String parent,
            Boolean isDir,
            String title,
            String album,
            String artist,
            Integer track,
            Integer year,
            String genre,
            String coverArt,
            Long size,
            String suffix,
            Integer duration,
            Integer bitRate,
            Long playCount,
            Integer userRating,
            Integer discNumber,
            Instant starred,
            Instant created,
            String albumId,
            String artistId,
            Boolean isVideo,
            @OpenSubsonicV1 @Nullable List<OSArtistJson> artists,
            @OpenSubsonicV1 @Nullable List<OSArtistJson> albumArtists,
            @OpenSubsonicV1 @Nullable String displayArtist,
            @OpenSubsonicV1 @Nullable String displayAlbumArtist,
            @OpenSubsonicV1 @Nullable List<String> moods,
            @OpenSubsonicV1 @Nullable String explicitStatus,
            @OpenSubsonicV1 @Nullable OSReplayGain replayGain
    ) {}

    @Target(TYPE_USE)
    @Retention(RUNTIME)
    /** not sure about the purpose of this annotation yet,
     * but I will keep marking OS fields with @OpenSubsonicV1 for now */
    public @interface OpenSubsonicV1 {}


    // -- ArtistID3 --
    record ArtistID3Json(
            String id,
            String name,
            Integer albumCount,
            String coverArt,
            Instant starred
    ) {}

    // -- Index (for getArtists) --
    record IndexJson(
            String name,
            List<ArtistID3Json> artist
    ) {}

    // -- AlbumID3 --
    record AlbumID3Json(
            String id,
            String name,
            String artist,
            String artistId,
            String coverArt,
            Integer songCount,
            Integer duration,
            Integer year,
            String genre,
            Instant starred
    ) {}

    // -- AlbumWithSongs (for getAlbum) --
    record AlbumWithSongsJson(
            String id,
            String name,
            String artist,
            String artistId,
            String coverArt,
            Integer songCount,
            Integer duration,
            Integer year,
            String genre,
            Instant starred,
            List<ChildJson> song
    ) {}

    // -- ArtistWithAlbums (for getArtist) --
    record ArtistWithAlbumsJson(
            String id,
            String name,
            Integer albumCount,
            String coverArt,
            Instant starred,
            List<AlbumID3Json> album
    ) {}

    // -- ArtistInfo2 --
    record ArtistInfo2Json(
            String biography,
            String musicBrainzId,
            String lastFmUrl,
            String smallImageUrl,
            String mediumImageUrl,
            String largeImageUrl
    ) {}

    // -- PlaylistJson --
    static class PlaylistJson {
        String id;
        String name;
        String owner;
        @SerializedName("public")
        Boolean isPublic;
        int songCount;
        @SerializedName("duration")
        Integer durationSeconds;
        String coverArt;
        Instant created;
        Instant changed;
        List<ChildJson> entry = List.of();
    }

    // -- ScanStatus --
    record ScanStatusJson(
            Boolean scanning,
            Integer count,
            Integer folderCount,
            Instant lastScan,
            String scanType,
            Long elapsedTime
    ) {}

    // -- Starred2 --
    record Starred2Json(
            List<ChildJson> song,
            List<AlbumID3Json> album,
            List<ArtistID3Json> artist
    ) {}

    // -- SearchResult3 --
    record SearchResult3Json(
            List<ArtistID3Json> artist,
            List<AlbumID3Json> album,
            List<ChildJson> song
    ) {}

    // -- AlbumList2 --
    record AlbumList2Json(
            List<AlbumID3Json> album
    ) {}

    static class GetArtistsResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetArtistsInner subsonicResponse;
        static class GetArtistsInner {
            String status;
            ArtistsJson artists;
        }
        static class ArtistsJson {
            List<IndexJson> index;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetArtistResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetArtistInner subsonicResponse;
        static class GetArtistInner {
            String status;
            ArtistWithAlbumsJson artist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetArtistInfo2ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetArtistInfo2Inner subsonicResponse;
        static class GetArtistInfo2Inner {
            String status;
            ArtistInfo2Json artistInfo2;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetAlbumResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetAlbumInner subsonicResponse;
        static class GetAlbumInner {
            String status;
            AlbumWithSongsJson album;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetSongResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetSongInner subsonicResponse;
        static class GetSongInner {
            String status;
            ChildJson song;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetPlaylistsResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetPlaylistsInner subsonicResponse;
        static class GetPlaylistsInner {
            String status;
            PlaylistsListJson playlists;
        }
        static class PlaylistsListJson {
            List<PlaylistJson> playlist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetPlaylistResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetPlaylistInner subsonicResponse;
        static class GetPlaylistInner {
            String status;
            PlaylistJson playlist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetStarred2ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetStarred2Inner subsonicResponse;
        static class GetStarred2Inner {
            String status;
            Starred2Json starred2;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetAlbumList2ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetAlbumList2Inner subsonicResponse;
        static class GetAlbumList2Inner {
            String status;
            AlbumList2Json albumList2;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class SearchResult3ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        SearchResult3Inner subsonicResponse;
        static class SearchResult3Inner {
            String status;
            SearchResult3Json searchResult3;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class ScanStatusResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        ScanStatusInner subsonicResponse;
        static class ScanStatusInner {
            String type;
            String status;
            String version;
            String serverVersion;
            boolean openSubsonic;
            ScanStatusJson scanStatus;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class CreatePlaylistResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        CreatePlaylistInner subsonicResponse;
        static class CreatePlaylistInner {
            String status;
            PlaylistJson playlist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    private TranscodeInfo buildTranscodeInfo(String songId, Optional<Integer> originalBitRate, Duration duration, String songSuffix) {
        var defaultSuffix = songSuffix != null && !songSuffix.isEmpty() ? songSuffix : "mp3";
        return new TranscodeInfo(
                songId,
                originalBitRate,
                streamBitRate,
                duration,
                streamFormat.isEmpty() ? defaultSuffix : streamFormat
        );
    }

    @Override
    public TranscodeInfo currentTranscodeInfo(SongInfo songInfo) {
        return currentTranscodeInfo(
                songInfo.id(),
                songInfo.bitRate(),
                songInfo.duration(),
                songInfo.suffix()
        );
    }

    @Override
    public TranscodeInfo currentTranscodeInfo(String songId, Optional<Integer> originalBitRate, Duration duration, String suffix) {
        return buildTranscodeInfo(songId, originalBitRate, duration, suffix);
    }

    private SongInfo toSongInfo(ChildJson song) {
        var duration = Duration.ofSeconds(song.duration() != null ? song.duration() : 0);
        var downloadUri = buildUri("/rest/download", Map.of("id", song.id()));

        var transcodeInfo = buildTranscodeInfo(
                song.id(),
                ofNullable(song.bitRate()),
                duration,
                song.suffix()
        );

        var artists = Utils.ofMaybeList(song.artists)
                .map(this::toArtistId);
                //.orElseGet(() -> List.of(new ArtistId(song.artistId(), song.artist())));

        return new SongInfo(
                song.id(),
                song.title(),
                ofNullable(song.track()),
                ofNullable(song.discNumber()),
                ofNullable(song.bitRate()),
                song.size() != null ? song.size() : 0,
                ofNullable(song.year()),
                ofNullable(song.genre()).orElse(""),
                song.playCount() != null ? song.playCount() : 0L,
                ofNullable(song.userRating()),
                new ArtistId(song.artistId(), song.artist()),
                artists,
                Utils.ofMaybeList(song.albumArtists).map(this::toArtistId),
                song.albumId(),
                song.album(),
                duration,
                song.moods == null ? List.of() : song.moods,
                ofNullable(song.starred()),
                ofNullable(song.created()),
                toCoverArt(song.albumId(), new AlbumIdentifier(song.albumId())),
                song.suffix() != null ? song.suffix() : "",
                transcodeInfo,
                downloadUri
        );
    }

    private List<ArtistId> toArtistId(List<OSArtistJson> osArtistJsons) {
        return osArtistJsons.stream().map(a -> new ArtistId(a.id, a.name)).toList();
    }

    private List<SongInfo> toSongInfoList(List<ChildJson> songs) {
        if (songs == null) {
            return List.of();
        }
        return songs.stream()
                .filter(c -> c.isDir() == null || !c.isDir())
                .filter(c -> c.isVideo() == null || !c.isVideo())
                .map(this::toSongInfo)
                .toList();
    }

    private ArtistEntry toArtistEntry(ArtistID3Json artist) {
        return new ArtistEntry(
                artist.id(),
                artist.name(),
                artist.albumCount() != null ? artist.albumCount() : 0,
                toCoverArt(artist.coverArt(), new ArtistIdentifier(artist.id())),
                ofNullable(artist.starred())
        );
    }

    private ArtistAlbumInfo toArtistAlbumInfo(AlbumID3Json album) {
        return new ArtistAlbumInfo(
                album.id(),
                album.name(),
                album.songCount() != null ? album.songCount() : 0,
                album.artistId(),
                album.artist(),
                Duration.ofSeconds(album.duration() != null ? album.duration() : 0),
                ofNullable(album.genre()).filter(s -> !s.isBlank()),
                ofNullable(album.year()),
                ofNullable(album.starred()),
                toCoverArt(album.coverArt(), new AlbumIdentifier(album.id()))
        );
    }

    private List<ArtistAlbumInfo> toAlbumInfoList(ArtistWithAlbumsJson artist) {
        if (artist.album() == null) {
            return List.of();
        }
        return artist.album().stream()
                .map(this::toArtistAlbumInfo)
                .toList();
    }

    private PlaylistSimple toPlaylistSimple(PlaylistJson pl) {
        return new PlaylistSimple(
                pl.id,
                pl.name,
                PlaylistKind.NORMAL,
                toCoverArt(pl.coverArt, new PlaylistIdentifier(pl.id))
                        .or(() -> ofNullable(pl.entry).flatMap(entries -> entries.stream()
                                .filter(c -> c.coverArt() != null && !c.coverArt().isBlank())
                                .findFirst()
                                .flatMap(c -> toCoverArt(c.coverArt(), new ObjectIdentifier.SongIdentifier(c.id())))
                        )),
                pl.songCount,
                pl.changed,
                pl.created
        );
    }

    private Optional<CoverArt> toCoverArt(String coverArtId, ObjectIdentifier identifier) {
        return ofNullable(coverArtId).map(id -> {
            var coverArtLink = buildUri("/rest/getCoverArt", Map.of("id", id));
            var filePath = toCachePath(this.dataDir, this.serverId, coverArtId);
            return new CoverArt(SERVER_ID, id, coverArtLink, filePath.cachePath().toAbsolutePath(), Optional.of(identifier));
        });
    }

    @Override
    public ServerType getServerType() {
        return ServerType.SUBSONIC;
    }

    @Override
    public boolean testConnection() {
        try {
            var parsed = fetchJson("/rest/ping", Map.of(), PingResponseJson.class);
            return "ok".equalsIgnoreCase(parsed.subsonicResponse.status);
        } catch (Exception e) {
            log.warn("testConnection failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public URI getStreamUri(String songId) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("id", songId);
        if (streamBitRate > 0) {
            params.put("maxBitRate", String.valueOf(streamBitRate));
        }
        if (!streamFormat.isEmpty()) {
            params.put("format", streamFormat);
        }
        return buildUri("/rest/stream", params);
    }

    @Override
    public void starId(String id) {
        fetchVoid("/rest/star", Map.of("id", id));
    }

    @Override
    public void unStarId(String id) {
        fetchVoid("/rest/unstar", Map.of("id", id));
    }

    @Override
    public void nowPlaying(ReportNowPlaying req) {
        var songId = req.songId().orElse("");
        if (songId.isBlank()) {
            return;
        }

        if (this.isFeatureSupported(PlaybackReport)) {
            // Valid Playback states: 'starting', 'playing', 'paused', 'stopped'.
            var playbackState = switch (req.playerState()) {
                case PLAYING -> "playing";
                case PAUSED -> "paused";
                case STOPPED -> "stopped";
            };

            var positionMs = req.positionMs();
            // while playing, we must move the position by the time elapsed since the positionAnchorAt point:
            if (req.playerState() == ReportNowPlaying.PlayerState.PLAYING && req.positionAnchorAt().isPresent()) {
                var now = Instant.now();
                positionMs = (now.toEpochMilli() - req.positionAnchorAt().get().toEpochMilli());
            }
            fetchVoid("/rest/reportPlayback", Map.of(
                    "mediaId", songId,
                    "mediaType", "song",
                    "state", playbackState,
                    "positionMs", String.valueOf(positionMs),
                    "ignoreScrobbling", "true"
            ));
        } else {
            // old subsonic way of reporting now playing state, with submission = false:
            var timeMs = req.startedAt().orElseGet(Instant::now).toEpochMilli();
            fetchVoid("/rest/scrobble", Map.of(
                    "id", songId,
                    "time", String.valueOf(timeMs),
                    "submission", "false"
            ));
        }
    }

    @Override
    public void scrobble(ScrobbleRequest req) {
        fetchVoid("/rest/scrobble", Map.of(
                "id", req.songId(),
                "time", String.valueOf(req.playedAt().toEpochMilli()),
                "submission", "true"
        ));
    }

    @Override
    public SongInfo getSong(String songId) {
        var res = fetchAndCheck("/rest/getSong", Map.of("id", songId), GetSongResponseJson.class);
        return toSongInfo(res.subsonicResponse.song);
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        var res = fetchAndCheck("/rest/getAlbum", Map.of("id", albumId), GetAlbumResponseJson.class);
        var album = res.subsonicResponse.album;
        return new AlbumInfo(
                album.id(),
                album.name(),
                album.songCount() != null ? album.songCount() : 0,
                ofNullable(album.year()),
                album.artistId(),
                album.artist(),
                Duration.ofSeconds(album.duration() != null ? album.duration() : 0),
                ofNullable(album.starred()),
                toCoverArt(album.coverArt(), new AlbumIdentifier(album.id())),
                toSongInfoList(album.song())
        );
    }

    @Override
    public ArtistInfo getArtistInfo(String artistId) {
        var task1 = Utils.doAsync(() -> fetchAndCheck(
                "/rest/getArtist", Map.of("id", artistId), GetArtistResponseJson.class
        ));
        var task2 = Utils.doAsync(() -> fetchAndCheck(
                "/rest/getArtistInfo2", Map.of("id", artistId), GetArtistInfo2ResponseJson.class
        ));
        return task1.thenCombine(task2, (artistRes, infoRes) -> {
            var artist = artistRes.subsonicResponse.artist;
            var info = infoRes.subsonicResponse.artistInfo2;
            var albums = toAlbumInfoList(artist);
            var bio = ofNullable(info.biography())
                    .filter(str -> !str.isBlank())
                    .orElse("");
            Biography biography = TextUtils.parseLink(bio);
            return new ArtistInfo(
                    artist.id(),
                    artist.name(),
                    artist.albumCount() != null ? artist.albumCount() : 0,
                    ofNullable(artist.starred()),
                    toCoverArt(artist.coverArt(), new ArtistIdentifier(artist.id())),
                    albums,
                    biography
            );
        }).join();
    }

    @Override
    public ArtistInfo getArtistWithAlbums(String artistId) {
        var res = fetchAndCheck("/rest/getArtist", Map.of("id", artistId), GetArtistResponseJson.class);
        var artist = res.subsonicResponse.artist;
        var albums = toAlbumInfoList(artist);
        return new ArtistInfo(
                artist.id(),
                artist.name(),
                artist.albumCount() != null ? artist.albumCount() : 0,
                ofNullable(artist.starred()),
                toCoverArt(artist.coverArt(), new ArtistIdentifier(artist.id())),
                albums,
                new Biography("", "", "")
        );
    }

    @Override
    public ListArtists getArtists() {
        var res = fetchAndCheck("/rest/getArtists", Map.of(), GetArtistsResponseJson.class);
        var indexes = res.subsonicResponse.artists.index;
        var list = indexes == null ? List.<ArtistEntry>of() : indexes.stream()
                .filter(idx -> idx.artist() != null)
                .flatMap(idx -> idx.artist().stream())
                .map(this::toArtistEntry)
                .toList();
        return new ListArtists(list);
    }

    @Override
    public ListStarred getStarred() {
        var res = fetchAndCheck("/rest/getStarred2", Map.of(), GetStarred2ResponseJson.class);
        var starred = res.subsonicResponse.starred2;
        var songs = starred != null && starred.song() != null
                ? starred.song().stream().map(this::toSongInfo).toList()
                : List.<SongInfo>of();
        return new ListStarred(songs);
    }

    @Override
    public ListPlaylists getPlaylists() {
        var res = fetchAndCheck("/rest/getPlaylists", Map.of(), GetPlaylistsResponseJson.class);
        var playlists = res.subsonicResponse.playlists;
        if (playlists == null || playlists.playlist == null) {
            return new ListPlaylists(List.of());
        }
        var list = playlists.playlist.stream()
                .map(this::toPlaylistSimple)
                .toList();
        return new ListPlaylists(list);
    }

    @Override
    public Playlist getPlaylist(String playlistId) {
        var res = fetchAndCheck("/rest/getPlaylist", Map.of("id", playlistId), GetPlaylistResponseJson.class);
        var pl = res.subsonicResponse.playlist;
        var songs = toSongInfoList(pl.entry);
        return new Playlist(
                pl.id,
                pl.name,
                PlaylistKind.NORMAL,
                toCoverArt(pl.coverArt, new PlaylistIdentifier(pl.id)),
                songs.size(),
                pl.changed,
                pl.created,
                songs
        );
    }

    @Override
    public HomeOverview getHomeOverview() {
        var recentTask = Utils.doAsync(() -> loadAlbumList("recent"));
        var newestTask = Utils.doAsync(() -> loadAlbumList("newest"));
        var frequentTask = Utils.doAsync(() -> loadAlbumList("frequent"));
        var highestTask = Utils.doAsync(() -> loadAlbumList("highest"));
        var yearTask = Utils.doAsync(() -> loadAlbumListByYear());

        return CompletableFuture
                .allOf(recentTask, newestTask, frequentTask, highestTask, yearTask)
                .thenApply(_ -> new HomeOverview(
                        recentTask.join(),
                        newestTask.join(),
                        frequentTask.join(),
                        highestTask.join(),
                        yearTask.join()
                ))
                .join();
    }

    private List<ArtistAlbumInfo> loadAlbumList(String type) {
        var res = fetchAndCheck("/rest/getAlbumList2", Map.of("type", type), GetAlbumList2ResponseJson.class);
        var albumList = res.subsonicResponse.albumList2;
        if (albumList == null || albumList.album() == null) {
            return List.of();
        }
        return albumList.album().stream()
                .map(this::toArtistAlbumInfo)
                .toList();
    }

    private List<ArtistAlbumInfo> loadAlbumListByYear() {
        int currentYear = Instant.now().atZone(ZoneOffset.UTC).getYear();
        var res = fetchAndCheck("/rest/getAlbumList2", Map.of(
                "type", "byYear",
                "fromYear", String.valueOf(currentYear),
                "toYear", "1900",
                "size", "20"
        ), GetAlbumList2ResponseJson.class);
        var albumList = res.subsonicResponse.albumList2;
        if (albumList == null || albumList.album() == null) {
            return List.of();
        }
        return albumList.album().stream()
                .map(this::toArtistAlbumInfo)
                .toList();
    }

    @Override
    public SearchResult search(String query) {
        var res = fetchAndCheck("/rest/search3", Map.of("query", query), SearchResult3ResponseJson.class);
        var sr = res.subsonicResponse.searchResult3;
        var artists = sr != null && sr.artist() != null
                ? sr.artist().stream().map(this::toArtistEntry).toList()
                : List.<ArtistEntry>of();
        var albums = sr != null && sr.album() != null
                ? sr.album().stream().map(this::toArtistAlbumInfo).toList()
                : List.<ArtistAlbumInfo>of();
        var songs = sr != null && sr.song() != null
                ? sr.song().stream().map(this::toSongInfo).toList()
                : List.<SongInfo>of();
        return new SearchResult(artists, albums, songs);
    }

    @Override
    public AddSongToPlaylist addToPlaylist(AddSongToPlaylist req) {
        fetchVoidMulti("/rest/updatePlaylist",
                Map.of("playlistId", req.playlistId()),
                Map.of("songIdToAdd", req.songIds())
        );
        return req;
    }

    @Override
    public PlaylistSimple playlistCreate(PlaylistCreateRequest request) {
        var res = fetchAndCheck("/rest/createPlaylist", Map.of("name", request.name()), CreatePlaylistResponseJson.class);
        var pl = res.subsonicResponse.playlist;
        var playlist = toPlaylistSimple(pl);
        if (!request.songIds().isEmpty()) {
            this.addToPlaylist(new AddSongToPlaylist(playlist.id(), request.songIds()));
        }
        return playlist;
    }

    @Override
    public void playlistRename(PlaylistRenameRequest req) {
        fetchVoid("/rest/updatePlaylist", Map.of("playlistId", req.id(), "name", req.newName()));
    }

    @Override
    public void playlistDelete(PlaylistDeleteRequest req) {
        fetchVoid("/rest/deletePlaylist", Map.of("id", req.id()));
    }

    @Override
    public void playlistRemove(PlaylistRemoveSongRequest req) {
        var indices = req.songIds().stream()
                .map(r -> String.valueOf(r.indexInPlaylist()))
                .toList();
        fetchVoidMulti("/rest/updatePlaylist",
                Map.of("playlistId", req.playlistId()),
                Map.of("songIndexToRemove", indices)
        );
    }

    @Override
    public ScanStatus scanStatus() {
        var res = fetchAndCheck("/rest/getScanStatus", Map.of(), ScanStatusResponseJson.class);
        var scan = res.subsonicResponse.scanStatus;
        if (scan.scanning() != null && scan.scanning()) {
            return new ScanStatus.Scanning(scan.count() != null ? scan.count() : 0);
        } else {
            return new ScanStatus.NotScanning();
        }
    }

    @Override
    public ScanStatus startScan(boolean quickScan) {
        return switch (scanStatus()) {
            case ScanStatus.Scanning scanning -> scanning;
            case ScanStatus.NotScanning _ -> {
                var res = fetchAndCheck("/rest/startScan", Map.of("fullScan", Boolean.toString(!quickScan)), ScanStatusResponseJson.class);
                var scan = res.subsonicResponse.scanStatus;
                int count = scan.count() != null ? scan.count() : 0;
                yield new ScanStatus.Scanning(count);
            }
        };
    }

    // name: songLyrics
    // https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/
    // /rest/getLyricsBySongId: id={songId}
    public record OpenSubsonicExtension(
            String name,
            List<Integer> versions
    ){}
    public record OpenSubsonicExtensions(
            boolean supported,
            List<OpenSubsonicExtension> list
    ){
        public boolean supports(OpenSubsonicFeature feature) {
            for (var e : list) {
                if (feature.value().equals(e.name())) {
                    return true;
                }
            }
            return false;
        }
        public enum OpenSubsonicFeature {
            FormPost("formPost"), // application/x-www-form-urlencoded
            SongLyrics("songLyrics"),
            IndexBasedQueue("indexBasedQueue"),
            Transcode("transcode"),
            TranscodeOffset("transcodeOffset"),
            PlaybackReport("playbackReport"),
            ;
            private final String value;
            OpenSubsonicFeature(String value) {
                this.value = value;
            }
            public String value() {
                return value;
            }
        }
    }

    static class SubsonicExtensionsResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        SubsonicExtensionsInner subsonicResponse;
        static class SubsonicExtensionsInner {
            String status;
            String version;
            String type;
            String serverVersion;
            boolean openSubsonic;
            List<OpenSubsonicExtension> openSubsonicExtensions;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    public Response getUnauthed(String path) throws IOException {
        var url = buildUrl(path, Map.of());
        var request = new Request.Builder().url(url).get().build();
        return this.httpClient.newCall(request).execute();
    }

    public OpenSubsonicExtensions getOpenSubsonicExtensions() {
        // needs to be called outside the normal send functions,
        // as the normal send functions loads features to detect if it can use body-form,
        // creating a circular call graph and StackOverflowError
        try (var response = getUnauthed("/rest/getOpenSubsonicExtensions")) {
            if (response.code() != 200) {
                return new OpenSubsonicExtensions(false, List.of());
            }
            var res = Utils.fromJson(response.body().string(), SubsonicExtensionsResponseJson.class);
            if (!"ok".equals(res.getStatus())) {
                return new OpenSubsonicExtensions(false, List.of());
            }
            var inner = res.subsonicResponse;
            boolean supported = inner.openSubsonic;
            List<OpenSubsonicExtension> list = inner.openSubsonicExtensions == null ? List.of() : inner.openSubsonicExtensions;
            String str = list.stream().map(OpenSubsonicExtension::name).collect(Collectors.joining(", "));
            log.info("getOpenSubsonicExtensions: got extensions=[{}]", str);
            return new OpenSubsonicExtensions(supported, list);
        } catch (HttpException e) {
            // assume a 404 means endpoint does not exist so extensions are not supported
            if (e.statusCode == 404) {
                log.info("getOpenSubsonicExtensions: not supported error={}", e.getMessage());
                return new OpenSubsonicExtensions(false, List.of());
            }
            log.warn("getOpenSubsonicExtensions: failed: {}", e.getMessage());
            throw e;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ServerInfo getServerInfo() {
        var res = fetchAndCheck("/rest/getScanStatus", Map.of(), ScanStatusResponseJson.class);
        var inner = res.subsonicResponse;
        var scan = inner.scanStatus;
        long count = scan.count() != null ? scan.count() : 0;
        Optional<Integer> folderCount = scan.folderCount() != null ? Optional.of(scan.folderCount()) : Optional.empty();
        Optional<Instant> lastScan = scan.lastScan() != null ? Optional.of(scan.lastScan()) : Optional.empty();
        Optional<String> serverVersion = inner.serverVersion != null ? Optional.of(inner.serverVersion) : Optional.empty();
        String apiVersion = inner.version != null ? inner.version : API_VERSION;
        var extensions = this.supportedExtensions.get();
        return new ServerInfo(
                ofNullable(inner.type),
                apiVersion,
                count,
                folderCount,
                lastScan,
                serverVersion,
                inner.openSubsonic,
                extensions
        );
    }

    @Override
    public StreamResponse openStream(TranscodeInfo transcodeInfo) {
        var params = new HashMap<String, String>();
        params.put("id", transcodeInfo.songId());
        if (streamBitRate > 0) {
            params.put("maxBitRate", String.valueOf(streamBitRate));
        }
        if (!streamFormat.isEmpty()) {
            params.put("format", streamFormat);
        }
        var url = buildUrl("/rest/stream", params);
        var request = new Request.Builder().url(url).get().build();
        try {
            Response response = streamingHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new RuntimeException("HTTP %d from /rest/stream for songId=%s".formatted(
                        response.code(), transcodeInfo.songId()));
            }
            String contentType = response.header("Content-Type", "");
            if (contentType.isEmpty() || contentType.contains("xml") || contentType.contains("html") || contentType.contains("json")) {
                response.close();
                throw new RuntimeException("Unexpected content-type=%s from /rest/stream for songId=%s".formatted(
                        contentType, transcodeInfo.songId()));
            }
            long contentLength = response.body() != null ? response.body().contentLength() : -1;
            InputStream wrappedStream = new FilterInputStream(response.body().byteStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
            return new StreamResponse(transcodeInfo.songId(), wrappedStream, contentLength, contentType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open stream for songId=%s".formatted(transcodeInfo.songId()), e);
        }
    }

    @Override
    public CoverArtResponse downloadCoverArt(CoverArt coverArt, int maxSize) {
        var url = HttpUrl.get(coverArt.coverArtLink()).newBuilder()
                .setQueryParameter("size", "%d".formatted(maxSize))
                .build();
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP %d fetching coverArt=%s".formatted(
                        response.code(), coverArt.coverArtId()));
            }
            String contentType = response.header("Content-Type", "image/webp");
            if (contentType.contains("xml") || contentType.contains("html") || contentType.contains("json")) {
                throw new RuntimeException("Unexpected content-type=%s for coverArt=%s".formatted(
                        contentType, coverArt.coverArtId()));
            }
            if (response.body() == null) {
                throw new RuntimeException("Null response body for coverArt=%s".formatted(coverArt.coverArtId()));
            }
            byte[] data = response.body().bytes();
            if (data.length == 0) {
                throw new RuntimeException("Empty response body for coverArt=%s".formatted(coverArt.coverArtId()));
            }
            return new CoverArtResponse(data, contentType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download coverArt=%s".formatted(coverArt.coverArtId()), e);
        }
    }

    public static class LyricsListRes {
        public static class StructuredLyrics {
            String displayArtist;
            String displayTitle;
            // lang is three letter language code
            String lang;
            int offset;
            boolean synced;
            List<Line> line;
            static class Line {
                int start;
                String value;
            }
        }
        List<StructuredLyrics> structuredLyrics;
    }
    static class GetSongLyricsResponse implements HasStatus {
        @SerializedName("subsonic-response")
        GetSongLyricsInner subsonicResponse;
        static class GetSongLyricsInner {
            String status;
            LyricsListRes lyricsList;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    public record GetSongLyricsRequest(String songId){}

    // https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/
    public LyricsListRes getSongLyrics(GetSongLyricsRequest req) {
        var res = fetchAndCheck("/rest/getLyricsBySongId.view", Map.of("id", req.songId()), GetSongLyricsResponse.class);
        return res.subsonicResponse.lyricsList;
    }

    @Override
    public Optional<LyricsResult> getSongLyrics(String songId) {
        if (!isLyricsSupported()) {
            return Optional.empty();
        }
        var lyricsList = getSongLyrics(new GetSongLyricsRequest(songId));
        return toLyricsResult(lyricsList);
    }

    static Optional<LyricsResult> toLyricsResult(@Nullable LyricsListRes res) {
        if (res == null || res.structuredLyrics == null) {
            return Optional.empty();
        }
        // synced lyrics win; fall back to the first entry with any text lines
        for (var entry : res.structuredLyrics) {
            if (!entry.synced || entry.line == null) {
                continue;
            }
            var lines = new ArrayList<LyricLine>();
            for (var line : entry.line) {
                if (line.value == null || line.value.isBlank()) {
                    continue;
                }
                // LRC offset semantics: positive offset shifts lyrics earlier
                long timeMs = Math.max(0L, (long) line.start - entry.offset);
                lines.add(new LyricLine(timeMs, line.value.strip()));
            }
            if (!lines.isEmpty()) {
                lines.sort(Comparator.comparingLong(LyricLine::timeMs));
                return Optional.of(new LyricsResult.SyncedLyrics(List.copyOf(lines)));
            }
        }
        for (var entry : res.structuredLyrics) {
            if (entry.line == null) {
                continue;
            }
            var lines = entry.line.stream()
                    .map(line -> line.value)
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .toList();
            if (!lines.isEmpty()) {
                return Optional.of(new LyricsResult.PlainLyrics(lines));
            }
        }
        return Optional.empty();
    }

    public static SubsonicClientV2 create(ServerConfig cfg) {
        return new SubsonicClientV2(cfg);
    }
}
