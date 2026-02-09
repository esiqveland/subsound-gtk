package com.github.subsound.integration.servers.subsonic;

import com.github.subsound.configuration.Config.ServerConfig;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.AlbumIdentifier;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.ArtistIdentifier;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import com.github.subsound.integration.servers.subsonic.SubsonicClient.SubsonicResponseJson.SubsonicResponse;
import com.github.subsound.utils.Utils;
import com.github.subsound.utils.javahttp.LoggingHttpClient;
import com.github.subsound.utils.javahttp.TextUtils;
import com.google.gson.annotations.SerializedName;
import net.beardbot.subsonic.client.Subsonic;
import net.beardbot.subsonic.client.SubsonicPreferences;
import net.beardbot.subsonic.client.api.lists.AlbumListParams;
import net.beardbot.subsonic.client.api.lists.AlbumListType;
import net.beardbot.subsonic.client.api.media.CoverArtParams;
import net.beardbot.subsonic.client.api.media.StreamParams;
import net.beardbot.subsonic.client.api.playlist.UpdatePlaylistParams;
import net.beardbot.subsonic.client.base.ApiParams;
import okhttp3.HttpUrl;
import org.subsonic.restapi.Child;
import org.subsonic.restapi.PlaylistWithSongs;
import org.subsonic.restapi.Starred2;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.github.subsound.app.state.AppManager.SERVER_ID;
import static com.github.subsound.persistence.ThumbnailCache.toCachePath;
import static java.util.Optional.ofNullable;


public class SubsonicClient implements ServerClient {
    private final String serverId;
    private final Path dataDir;
    private final SubsonicPreferences settings;
    private final URI uri;
    private final Subsonic client;
    private final HttpClient httpClient = new LoggingHttpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());

    public SubsonicClient(String serverId, Path dataDir, SubsonicPreferences settings) {
        this.serverId = serverId;
        this.dataDir = dataDir;
        this.settings = settings;
        this.uri = URI.create(settings.getServerUrl());
        this.client = new Subsonic(settings);
    }

    public URI coverArtLink(String coverArtId) {
        try {
            return this.client.media().getCoverArt(coverArtId, CoverArtParams.create()).getUrl().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException("generated bad uri from coverArtId=" + coverArtId, e);
        }
    }

    public URI coverArtLink(String coverArtId, int size) {
        try {
            return this.client.media().getCoverArt(coverArtId, CoverArtParams.create().size(size)).getUrl().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException("generated bad uri from coverArtId=" + coverArtId, e);
        }
    }

    @Override
    public ListArtists getArtists() {
        //this.client.browsing().getIndexes().get(0).getArtists();
        var res = this.client.browsing().getArtists();
        var list = res.stream()
                .flatMap(s -> s.getArtists().stream())
                .map(artist -> new ArtistEntry(
                        artist.getId(),
                        artist.getName(),
                        artist.getAlbumCount(),
                        toCoverArt(artist.getCoverArtId(), new ArtistIdentifier(artist.getId()))
                )).toList();
        return new ListArtists(list);
    }

    @Override
    public void starId(String id) {
        this.client.annotation().star(id);
    }

    @Override
    public void unStarId(String id) {
        this.client.annotation().unstar(id);
    }

    @Override
    public ListStarred getStarred() {
        Starred2 starred2 = this.client.lists().getStarred2();
        var songs = starred2.getSongs().stream().map(this::toSongInfo).toList();
        return new ListStarred(songs);
    }

    @Override
    public SongInfo getSong(String songId) {
        Child song = this.client.browsing().getSong(songId);
        return toSongInfo(song);
    }

    @Override
    public HomeOverview getHomeOverview() {
        var recentTask = Utils.doAsync(() -> this.loadAlbumList(AlbumListType.RECENT));
        var newestTask = Utils.doAsync(() -> this.loadAlbumList(AlbumListType.NEWEST));
        var frequentTask = Utils.doAsync(() -> this.loadAlbumList(AlbumListType.FREQUENT));
        var highestTask = Utils.doAsync(() -> this.loadAlbumList(AlbumListType.HIGHEST));

        return CompletableFuture
                .allOf(recentTask, newestTask, frequentTask, highestTask)
                .thenApply(_ -> new HomeOverview(
                        recentTask.join(),
                        newestTask.join(),
                        frequentTask.join(),
                        highestTask.join()
                ))
                .join();
    }

    private List<ArtistAlbumInfo> loadAlbumList(AlbumListType albumListType) {
        var albums = this.client.lists().getAlbumList2(listParams(albumListType)).getAlbums();
        return albums.stream()
                .map(album -> ArtistAlbumInfo.create(album, toCoverArt(album.getCoverArtId(), new AlbumIdentifier(album.getId()))))
                .toList();
    }

    private AlbumListParams listParams(AlbumListType albumListType) {
        return AlbumListParams.create().type(albumListType);
    }

    @Override
    public ArtistInfo getArtistInfo(String artistId) {
        var task1 = Utils.doAsync(() -> this.getArtistJson(artistId));
        var task2 = Utils.doAsync(() -> this.getArtistInfo2Json(artistId));
        return task1.thenCombine(task2, (artist, info) -> {
            var albums = toAlbumInfoList(artist);
            var bio = Optional.ofNullable(info.biography())
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
        var artist = this.getArtistJson(artistId);
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

    private List<ArtistAlbumInfo> toAlbumInfoList(ArtistWithAlbumsJson artist) {
        if (artist.album() == null) {
            return List.of();
        }
        return artist.album().stream()
                .map(album -> new ArtistAlbumInfo(
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
                ))
                .toList();
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        var task1 = Utils.doAsync(() -> this.client.browsing().getAlbum(albumId));
        //var task2 = Utils.doAsync(() -> this.client.browsing().getAlbumInfo2(albumId));
        //var info = task2.join();
        var album = task1.join();
        return new AlbumInfo(
                album.getId(),
                album.getName(),
                album.getSongCount(),
                ofNullable(album.getYear()),
                album.getArtistId(),
                album.getArtist(),
                Duration.ofSeconds(album.getDuration()),
                ofNullable(album.getStarred()).map(d -> d.toInstant(ZoneOffset.UTC)),
                toCoverArt(album.getCoverArtId(), new AlbumIdentifier(album.getId())),
                toSongInfo(album.getSongs())
        );
    }

    @Override
    public AddSongToPlaylist addToPlaylist(AddSongToPlaylist req) {
        this.client.playlists().updatePlaylist(req.playlistId(), UpdatePlaylistParams.create().addSong(req.songId()));
        return req;
    }

    @Override
    public ListPlaylists getPlaylists() {
        var list = Optional.ofNullable(this.client.playlists().getPlaylists()).orElseGet(List::of)
                .stream()
                .map(p -> new PlaylistSimple(
                        p.getId(),
                        p.getName(),
                        PlaylistKind.NORMAL,
                        toCoverArt(p.getCoverArtId(), new PlaylistIdentifier(p.getId())),
                        p.getSongCount(),
                        p.getCreated().toInstant(ZoneOffset.UTC)
                ))
                .toList();
        return new ListPlaylists(list);
    }

    @Override
    public Playlist getPlaylist(String playlistId) {
        PlaylistWithSongs playlist = this.client.playlists().getPlaylist(playlistId);
        var songs = playlist.getEntries().stream().map(this::toSongInfo).toList();
        return new Playlist(
                playlist.getId(),
                playlist.getName(),
                PlaylistKind.NORMAL,
                toCoverArt(playlist.getCoverArtId(), new PlaylistIdentifier(playlist.getId())),
                songs.size(),
                playlist.getCreated().toInstant(ZoneOffset.UTC),
                songs
        );
    }

    @Override
    public ServerType getServerType() {
        return ServerType.SUBSONIC;
    }

    @Override
    public boolean testConnection() {
        return this.client.testConnection();
    }

    @Override
    public ServerInfo getServerInfo() {
        var scanStatusResponse = this.getScanStatusJson();
        var scanStatus = scanStatusResponse.scanStatus;
        var apiVersion = this.client.getApiVersion().getVersionString();
        long count = scanStatus.count() != null ? scanStatus.count : 0;
        Optional<Integer> folderCount = scanStatus.folderCount != null ? Optional.of(scanStatus.folderCount) : Optional.empty();
        Optional<Instant> lastScan = scanStatus.lastScan != null ? Optional.of(scanStatus.lastScan) : Optional.empty();
        Optional<String> serverVersion = scanStatusResponse.serverVersion != null ? Optional.of(scanStatusResponse.serverVersion) : Optional.empty();
        return new ServerInfo(apiVersion, count, folderCount, lastScan, serverVersion);
    }


    // {
    //  "subsonic-response": {
    //    "status": "ok",
    //    "version": "1.16.1",
    //    "type": "navidrome",
    //    "serverVersion": "0.59.0 (cc3cca60)",
    //    "openSubsonic": true,
    //    "scanStatus": {
    //      "scanning": false,
    //      "count": 3658,
    //      "folderCount": 357,
    //      "lastScan": "2026-01-25T10:38:16.590557775Z",
    //      "scanType": "full",
    //      "elapsedTime": 12590557775
    //    }
    //  }
    //}
    public static class SubsonicResponseJson<T> {
        public static class SubsonicResponse<T> {
            public String status;
            public String version;
            public String type;
            public String serverVersion;
            public Boolean openSubsonic;
            // TODO: find a way to make the subtype here have a dynamic key name
            public T scanStatus;
        }
        @SerializedName("subsonic-response")
        public SubsonicResponse<T> subsonicResponse;
    }
    public static class ScanStatusResponse extends SubsonicResponseJson<ScanStatusJson> {}
    public record ScanStatusJson(
            Boolean scanning,
            Integer count, // songCount
            Integer folderCount,
            Instant lastScan,
            String scanType,
            Long elapsedTime
    ) {}

    public SubsonicResponse<ScanStatusJson> getScanStatusJson() {
        try {
            URI link = getServerUri("/rest/getScanStatus", ResponseFormat.JSON);
            var req = HttpRequest.newBuilder().GET().uri(link).build();
            var bodyHandler = HttpResponse.BodyHandlers.ofByteArray();
            HttpResponse<byte[]> res = this.httpClient.send(req, bodyHandler);
            var bodyBytes = res.body();
            var bodyString = new String(bodyBytes, StandardCharsets.UTF_8);
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                var parsed = Utils.fromJson(bodyString, ScanStatusResponse.class);
                if ("ok".equalsIgnoreCase(parsed.subsonicResponse.status)) {
                    return parsed.subsonicResponse;
                }
                throw new RuntimeException("error loading: status=" + res.statusCode() + " link=" + link.toString()+" body="+bodyString);
            } else {
                throw new RuntimeException("error loading: status=" + res.statusCode() + " link=" + link.toString()+" body="+bodyString);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // JSON response models for /rest/getArtist
    public static class GetArtistResponseJson {
        @SerializedName("subsonic-response")
        public GetArtistResponseInner subsonicResponse;
        public static class GetArtistResponseInner {
            public String status;
            public ArtistWithAlbumsJson artist;
        }
    }
    public record ArtistWithAlbumsJson(
            String id,
            String name,
            Integer albumCount,
            String coverArt,
            Instant starred,
            List<AlbumID3Json> album
    ) {}
    public record AlbumID3Json(
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

    // JSON response models for /rest/getArtistInfo2
    public static class GetArtistInfo2ResponseJson {
        @SerializedName("subsonic-response")
        public GetArtistInfo2ResponseInner subsonicResponse;
        public static class GetArtistInfo2ResponseInner {
            public String status;
            public ArtistInfo2Json artistInfo2;
        }
    }
    public record ArtistInfo2Json(
            String biography,
            String musicBrainzId,
            String lastFmUrl,
            String smallImageUrl,
            String mediumImageUrl,
            String largeImageUrl
    ) {}

    public ArtistWithAlbumsJson getArtistJson(String artistId) {
        try {
            URI link = getServerUri("/rest/getArtist", ResponseFormat.JSON, Map.of("id", artistId));
            var req = HttpRequest.newBuilder().GET().uri(link).build();
            HttpResponse<byte[]> res = this.httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            var bodyString = new String(res.body(), StandardCharsets.UTF_8);
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                var parsed = Utils.fromJson(bodyString, GetArtistResponseJson.class);
                if ("ok".equalsIgnoreCase(parsed.subsonicResponse.status)) {
                    return parsed.subsonicResponse.artist;
                }
            }
            throw new RuntimeException("error loading getArtist: status=" + res.statusCode() + " body=" + bodyString);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    ArtistInfo2Json getArtistInfo2Json(String artistId) {
        try {
            URI link = getServerUri("/rest/getArtistInfo2", ResponseFormat.JSON, Map.of("id", artistId));
            var req = HttpRequest.newBuilder().GET().uri(link).build();
            HttpResponse<byte[]> res = this.httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            var bodyString = new String(res.body(), StandardCharsets.UTF_8);
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                var parsed = Utils.fromJson(bodyString, GetArtistInfo2ResponseJson.class);
                if ("ok".equalsIgnoreCase(parsed.subsonicResponse.status)) {
                    return parsed.subsonicResponse.artistInfo2;
                }
            }
            throw new RuntimeException("error loading getArtistInfo2: status=" + res.statusCode() + " body=" + bodyString);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    String fetchJsonBody(String path, Map<String, String> extraParams) {
        try {
            URI link = getServerUri(path, ResponseFormat.JSON, extraParams);
            var req = HttpRequest.newBuilder().GET().uri(link).build();
            HttpResponse<byte[]> res = this.httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return new String(res.body(), StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    enum ResponseFormat { XML, JSON }

    final URI getServerUri(String path, ResponseFormat format) {
        return getServerUri(path, format, Map.of());
    }

    final URI getServerUri(String path, ResponseFormat format, Map<String, String> extraParams) {
        var auth = this.settings.getAuthentication();
        var v = this.client.getApiVersion().getVersionString();
        var builder = HttpUrl.get(this.uri).newBuilder(path)
                .setQueryParameter("u", this.settings.getUsername())
                .setQueryParameter("s", auth.getSalt())
                .setQueryParameter("t", auth.getToken())
                .setQueryParameter("c", this.settings.getClientName())
                .setQueryParameter("f", switch (format) {
                    case XML -> "xml";
                    case JSON -> "json";
                })
                .setQueryParameter("v", v);
        for (var param : extraParams.entrySet()) {
            builder.setQueryParameter(param.getKey(), param.getValue());
        }
        return builder.build().uri();
    }

    private SongInfo toSongInfo(Child song) {
        try {
            var duration = Duration.ofSeconds(song.getDuration());
            var downloadUri = toDownloadUri(client, song);
            var streamBitrate = this.client.getPreferences().getStreamBitRate();
            var streamFormat = this.client.getPreferences().getStreamFormat();
            var params = StreamParams.create()
                    // estimateContentLength breaks java httpclient when the content-length
                    // is shorter than the file ends up being ie. the server has over-estimated the final file size.
                    // So instead we use a custom way to estimate some content size after transcoding based on the
                    // wanted bitrate and duration.
                    //.estimateContentLength(true)
                    .maxBitRate(streamBitrate)
                    .format(streamFormat);
            URI streamUri = this.client.media()
                    .stream(song.getId(), params)
                    .getUrl().toURI();

            var transcodeInfo = new TranscodeInfo(
                    ofNullable(song.getBitRate()),
                    streamBitrate,
                    duration,
                    streamFormat,
                    streamUri
            );

            return new SongInfo(
                    song.getId(),
                    song.getTitle(),
                    ofNullable(song.getTrack()),
                    ofNullable(song.getDiscNumber()),
                    ofNullable(song.getBitRate()),
                    song.getSize(),
                    ofNullable(song.getYear()),
                    ofNullable(song.getGenre()).orElse(""),
                    song.getPlayCount(),
                    ofNullable(song.getUserRating()),
                    song.getArtistId(),
                    song.getArtist(),
                    song.getAlbumId(),
                    song.getAlbum(),
                    duration,
                    ofNullable(song.getStarred()).map(d -> d.toInstant(ZoneOffset.UTC)),
                    toCoverArt(song.getCoverArtId(), new AlbumIdentifier(song.getAlbumId())),
                    song.getSuffix(),
                    transcodeInfo,
                    downloadUri
            );
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private URI toDownloadUri(Subsonic client, Child song) {
        try {
            var params = DownloadParams.create().id(song.getId());
            var url = client.createUrl("download", params.getParamMap()).toURI();
            return url;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    static class DownloadParams extends ApiParams {
        public static DownloadParams create() {
            return new DownloadParams();
        }

        public DownloadParams id(String id) {
            setParam("id", id);
            return this;
        }
    }

    private List<SongInfo> toSongInfo(List<Child> songs) {
        return songs.stream()
                .filter(child -> !child.isDir())
                .filter(child -> child.isVideo() == null || !child.isVideo())
                .map(this::toSongInfo).toList();
    }

    public Optional<CoverArt> toCoverArt(String coverArtId, ObjectIdentifier identifier) {
        return ofNullable(coverArtId).map(id -> {
            var filePath = toCachePath(this.dataDir, this.serverId, coverArtId);
            return new CoverArt(SERVER_ID, id, coverArtLink(id), filePath.cachePath().toAbsolutePath(), Optional.of(identifier));
        });
    }

    public record TranscodeSettings(
            TranscodeFormat format,
            TranscodeBitrate bitrate
    ) {
    }

    enum TranscodeFormat {
        raw,
        mp3,
        opus,
    }

    public sealed interface TranscodeBitrate {
        record Unlimited() implements TranscodeBitrate {
        }

        record MaximumBitrate(int v) implements TranscodeBitrate {
        }

        ;
    }

    public static SubsonicPreferences createSettings(ServerConfig cfg) {
        SubsonicPreferences preferences = new SubsonicPreferences(
                cfg.url(),
                cfg.username(),
                cfg.password()
        );
        preferences.setStreamFormat("mp3");
        preferences.setStreamBitRate(321);
        preferences.setClientName("subsound-gtk");
        return preferences;
    }

    public static SubsonicClient create(ServerConfig cfg) {
        var settings = createSettings(cfg);
        return new SubsonicClient(cfg.id(), cfg.dataDir(), settings);
    }
}
