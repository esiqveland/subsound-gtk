package io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic;

import io.github.jwharm.javagi.examples.playsound.configuration.Config.ServerConfig;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.utils.javahttp.TextUtils;
import net.beardbot.subsonic.client.Subsonic;
import net.beardbot.subsonic.client.SubsonicPreferences;
import net.beardbot.subsonic.client.api.lists.AlbumListParams;
import net.beardbot.subsonic.client.api.lists.AlbumListType;
import net.beardbot.subsonic.client.api.media.CoverArtParams;
import net.beardbot.subsonic.client.base.ApiParams;
import okhttp3.HttpUrl;
import org.subsonic.restapi.Child;
import org.subsonic.restapi.Starred2;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.github.jwharm.javagi.examples.playsound.app.state.AppManager.SERVER_ID;
import static java.util.Optional.ofNullable;


public class SubsonicClient implements ServerClient {
    private final Subsonic client;

    public SubsonicClient(Subsonic client) {
        this.client = client;
    }

    public URI coverArtLink(String coverArtId) {
        try {
            return this.client.media().getCoverArtUrl(coverArtId, CoverArtParams.create()).toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException("generated bad uri from coverArtId=" + coverArtId, e);
        }
    }

    public URI coverArtLink(String coverArtId, int size) {
        try {
            return this.client.media().getCoverArtUrl(coverArtId, CoverArtParams.create().size(size)).toURI();
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
                        toCoverArt(artist.getCoverArtId())
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
                .map(album -> ArtistAlbumInfo.create(album, toCoverArt(album.getCoverArtId())))
                .toList();
    }

    private AlbumListParams listParams(AlbumListType albumListType) {
        return AlbumListParams.create().type(albumListType);
    }

    @Override
    public ArtistInfo getArtistInfo(String artistId) {
//        try (var scope = new StructuredTaskScope<Object>()){
//            var task1 = scope.fork(() -> this.client.browsing().getArtist(artistId));
//            var task2 = scope.fork(() -> this.client.browsing().getArtistInfo2(artistId));
//        }

        var task1 = Utils.doAsync(() -> this.client.browsing().getArtist(artistId));
        var task2 = Utils.doAsync(() -> this.client.browsing().getArtistInfo2(artistId));
        return task1.thenCombine(task2, (artist, info) -> {
            var albums = artist.getAlbums().stream()
                    .map(album -> ArtistAlbumInfo.create(
                            album,
                            toCoverArt(album.getCoverArtId()))
                    )
                    .toList();

            var bio = Optional.ofNullable(info.getBiography())
                    .filter(str -> !str.isBlank())
                    .orElse("");
            Biography biography = TextUtils.parseLink(bio);
            return new ArtistInfo(
                    artist.getId(),
                    artist.getName(),
                    artist.getAlbumCount(),
                    ofNullable(artist.getStarred()).map(d -> d.toInstant(ZoneOffset.UTC)),
                    toCoverArt(artist.getCoverArtId()),
                    albums,
                    biography
            );
        }).join();
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        var task1 = Utils.doAsync(() -> this.client.browsing().getAlbum(albumId));
        var task2 = Utils.doAsync(() -> this.client.browsing().getAlbumInfo2(albumId));
        return task1.thenCombine(task2, (album, info) -> {
            //info.getNotes();

            return new AlbumInfo(
                    album.getId(),
                    album.getName(),
                    album.getSongCount(),
                    album.getArtistId(),
                    album.getArtist(),
                    Duration.ofSeconds(album.getDuration()),
                    ofNullable(album.getStarred()).map(d -> d.toInstant(ZoneOffset.UTC)),
                    toCoverArt(album.getCoverArtId()),
                    toSongInfo(album.getSongs())
            );
        }).join();
    }
    @Override
    public ServerType getServerType() {
        return ServerType.SUBSONIC;
    }

    @Override
    public boolean testConnection() {
        return this.client.testConnection();
    }

    private SongInfo toSongInfo(Child song) {
        try {
            URI downloadUri = toDownloadUri(client, song);
            String streamSuffix = this.client.getPreferences().getStreamFormat();
            URI streamUri = this.client.media().streamUrl(song.getId()).toURI();
            streamUri = HttpUrl.get(streamUri).newBuilder()
                    .setQueryParameter("estimateContentLength", "true")
                    .build()
                    .uri();

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
                    Duration.ofSeconds(song.getDuration()),
                    ofNullable(song.getStarred()).map(d -> d.toInstant(ZoneOffset.UTC)),
                    toCoverArt(song.getCoverArtId()),
                    song.getSuffix(),
                    streamSuffix,
                    downloadUri,
                    streamUri
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
                .filter(child -> !child.isVideo())
                .map(this::toSongInfo).toList();
    }

    public Optional<CoverArt> toCoverArt(String coverArtId) {
        return ofNullable(coverArtId).map(id -> new CoverArt(SERVER_ID, id, coverArtLink(id)));
    }

    public record TranscodeSettings(
            TranscodeFormat format,
            TranscodeBitrate bitrate
    ) {}
    enum TranscodeFormat {
        raw,
        mp3,
        opus,
    }
    public sealed interface TranscodeBitrate {
        record Unlimited() implements TranscodeBitrate {}
        record MaximumBitrate(int v) implements TranscodeBitrate {};
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

    public static SubsonicClient create(SubsonicPreferences preferences) {
        return new SubsonicClient(new Subsonic(preferences));
    }
    public static SubsonicClient create(ServerConfig cfg) {
        return new SubsonicClient(new Subsonic(createSettings(cfg)));
    }
}
