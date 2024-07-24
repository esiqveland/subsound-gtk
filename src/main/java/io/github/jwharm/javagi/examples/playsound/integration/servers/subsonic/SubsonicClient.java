package io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import net.beardbot.subsonic.client.Subsonic;
import net.beardbot.subsonic.client.SubsonicPreferences;
import net.beardbot.subsonic.client.api.media.CoverArtParams;
import net.beardbot.subsonic.client.base.ApiParams;
import org.subsonic.restapi.AlbumWithSongsID3;
import org.subsonic.restapi.ArtistWithAlbumsID3;
import org.subsonic.restapi.Child;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public class SubsonicClient implements ServerClient {
    private final Subsonic client;

    public SubsonicClient(Subsonic client) {
        this.client = client;
    }

    public static SubsonicClient create(SubsonicPreferences preferences) {
        return new SubsonicClient(new Subsonic(preferences));
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
    public ArtistInfo getArtistInfo(String artistId) {
        ArtistWithAlbumsID3 artist = this.client.browsing().getArtist(artistId);

        var albums = artist.getAlbums().stream().map(album -> new ArtistAlbumInfo(
                album.getId(),
                album.getName(),
                album.getSongCount(),
                album.getArtistId(),
                album.getArtist(),
                Duration.ofSeconds(album.getDuration()),
                ofNullable(album.getStarred()).map(ts -> ts.toInstant(ZoneOffset.UTC)),
                toCoverArt(album.getCoverArtId())
        )).toList();

        return new ArtistInfo(
                artist.getId(),
                artist.getName(),
                artist.getAlbumCount(),
                ofNullable(artist.getStarred()).map(d -> d.toInstant(ZoneOffset.UTC)),
                toCoverArt(artist.getCoverArtId()),
                albums
        );
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        AlbumWithSongsID3 album = this.client.browsing().getAlbum(albumId);
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
    }

    @Override
    public ServerType getServerType() {
        return ServerType.SUBSONIC;
    }

    private SongInfo toSongInfo(Child song) {
        try {
            URI downloadUri = toDownloadUri(client, song);
            URI streamUri = this.client.media().streamUrl(song.getId()).toURI();

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
        return ofNullable(coverArtId).map(id -> new CoverArt(id, coverArtLink(id)));
    }
}
