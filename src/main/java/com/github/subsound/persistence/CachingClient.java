package com.github.subsound.persistence;

import com.github.subsound.app.state.NetworkMonitoring.NetworkStatus;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.persistence.database.Album;
import com.github.subsound.persistence.database.Artist;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.PlaylistRow;
import com.github.subsound.persistence.database.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.subsound.persistence.ThumbnailCache.toCachePath;

public class CachingClient implements ServerClient {
    private static final Logger log = LoggerFactory.getLogger(CachingClient.class);

    private final ServerClient delegate;
    private final DatabaseServerService dbService;
    private final String serverId;
    private final Path cacheRoot;
    // Default to OFFLINE - safer to assume offline until network monitor confirms online
    private volatile NetworkStatus networkStatus = NetworkStatus.OFFLINE;

    public CachingClient(ServerClient delegate, DatabaseServerService dbService, String serverId, Path cacheRoot) {
        this.delegate = delegate;
        this.dbService = dbService;
        this.serverId = serverId;
        this.cacheRoot = cacheRoot;
    }

    public void setNetworkStatus(NetworkStatus status) {
        this.networkStatus = status;
        log.info("CachingClient network status changed to: {}", status);
    }

    private boolean isOffline() {
        return networkStatus == NetworkStatus.OFFLINE;
    }

    @Override
    public ListArtists getArtists() {
        if (isOffline()) {
            log.debug("Offline mode: using cached artists");
            var artists = dbService.listArtists();
            return new ListArtists(artists.stream().map(this::toArtistEntry).toList());
        }
        try {
            return delegate.getArtists();
        } catch (Exception e) {
            log.warn("Failed to fetch artists from server, falling back to database", e);
            var artists = dbService.listArtists();
            return new ListArtists(artists.stream().map(this::toArtistEntry).toList());
        }
    }

    @Override
    public ArtistInfo getArtistInfo(String artistId) {
        if (isOffline()) {
            log.debug("Offline mode: using cached artist info for {}", artistId);
            return dbService.getArtistById(artistId)
                    .map(this::toArtistInfo)
                    .orElseThrow(() -> new RuntimeException("Artist not found in database: " + artistId));
        }
        try {
            return delegate.getArtistInfo(artistId);
        } catch (Exception e) {
            log.warn("Failed to fetch artist info from server, falling back to database: {}", artistId, e);
            return dbService.getArtistById(artistId)
                    .map(this::toArtistInfo)
                    .orElseThrow(() -> new RuntimeException("Artist not found in database: " + artistId, e));
        }
    }

    @Override
    public ArtistInfo getArtistWithAlbums(String artistId) {
        if (isOffline()) {
            log.debug("Offline mode: using cached artist with albums for {}", artistId);
            return dbService.getArtistById(artistId)
                    .map(this::toArtistInfo)
                    .orElseThrow(() -> new RuntimeException("Artist not found in database: " + artistId));
        }
        try {
            return delegate.getArtistWithAlbums(artistId);
        } catch (Exception e) {
            log.warn("Failed to fetch artist with albums from server, falling back to database: {}", artistId, e);
            return dbService.getArtistById(artistId)
                    .map(this::toArtistInfo)
                    .orElseThrow(() -> new RuntimeException("Artist not found in database: " + artistId, e));
        }
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        if (isOffline()) {
            log.debug("Offline mode: using cached album info for {}", albumId);
            return dbService.getAlbumById(albumId)
                    .map(this::toAlbumInfo)
                    .orElseThrow(() -> new RuntimeException("Album not found in database: " + albumId));
        }
        try {
            return delegate.getAlbumInfo(albumId);
        } catch (Exception e) {
            log.warn("Failed to fetch album info from server, falling back to database: {}", albumId, e);
            return dbService.getAlbumById(albumId)
                    .map(this::toAlbumInfo)
                    .orElseThrow(() -> new RuntimeException("Album not found in database: " + albumId, e));
        }
    }

    @Override
    public ListPlaylists getPlaylists() {
        if (isOffline()) {
            log.debug("Offline mode: using cached playlists");
            var playlists = dbService.listPlaylists();
            return new ListPlaylists(playlists.stream().map(this::toPlaylistSimple).toList());
        }
        try {
            return delegate.getPlaylists();
        } catch (Exception e) {
            log.warn("Failed to fetch playlists from server, falling back to database", e);
            var playlists = dbService.listPlaylists();
            return new ListPlaylists(playlists.stream().map(this::toPlaylistSimple).toList());
        }
    }

    @Override
    public Playlist getPlaylist(String playlistId) {
        if (isOffline()) {
            log.debug("Offline mode: using cached playlist for {}", playlistId);
            return dbService.getPlaylistById(playlistId)
                    .map(this::toPlaylist)
                    .orElseThrow(() -> new RuntimeException("Playlist not found in database: " + playlistId));
        }
        try {
            return delegate.getPlaylist(playlistId);
        } catch (Exception e) {
            log.warn("Failed to fetch playlist from server, falling back to database: {}", playlistId, e);
            return dbService.getPlaylistById(playlistId)
                    .map(this::toPlaylist)
                    .orElseThrow(() -> new RuntimeException("Playlist not found in database: " + playlistId, e));
        }
    }

    @Override
    public ListStarred getStarred() {
        if (isOffline()) {
            log.debug("Offline mode: using cached starred");
            var songs = dbService.listSongsByStarredAt();
            return new ListStarred(songs.stream().map(this::toSongInfo).toList());
        }
        try {
            return delegate.getStarred();
        } catch (Exception e) {
            log.warn("Failed to fetch starred from server, falling back to database", e);
            var songs = dbService.listSongsByStarredAt();
            return new ListStarred(songs.stream().map(this::toSongInfo).toList());
        }
    }

    @Override
    public SongInfo getSong(String songId) {
        if (isOffline()) {
            log.debug("Offline mode: using cached song for {}", songId);
            return dbService.getSongById(songId)
                    .map(this::toSongInfo)
                    .orElseThrow(() -> new RuntimeException("Song not found in database: " + songId));
        }
        try {
            return delegate.getSong(songId);
        } catch (Exception e) {
            log.warn("Failed to fetch song from server, falling back to database: {}", songId, e);
            return dbService.getSongById(songId)
                    .map(this::toSongInfo)
                    .orElseThrow(() -> new RuntimeException("Song not found in database: " + songId, e));
        }
    }

    @Override
    public AddSongToPlaylist addToPlaylist(AddSongToPlaylist req) {
        if (isOffline()) {
            throw new IllegalStateException("Cannot add to playlist while offline");
        }
        this.delegate.addToPlaylist(req);
        return req;
    }

    @Override
    public HomeOverview getHomeOverview() {
        if (isOffline()) {
            log.debug("Offline mode: using cached home overview");
            var recentAlbums = dbService.listAlbumsByAddedAt();
            var albumInfos = recentAlbums.stream().map(this::toArtistAlbumInfo).toList();
            // For offline, use recent albums for all categories
            return new HomeOverview(albumInfos, albumInfos, List.of(), List.of());
        }
        try {
            return delegate.getHomeOverview();
        } catch (Exception e) {
            log.warn("Failed to fetch home overview from server, falling back to database", e);
            var recentAlbums = dbService.listAlbumsByAddedAt();
            var albumInfos = recentAlbums.stream().map(this::toArtistAlbumInfo).toList();
            // For offline, use recent albums for all categories
            return new HomeOverview(albumInfos, albumInfos, List.of(), List.of());
        }
    }

    @Override
    public void scrobble(ScrobbleRequest req) {
        this.delegate.scrobble(req);
    }

    @Override
    public PlaylistSimple playlistCreate(PlaylistCreateRequest req) {
        return this.delegate.playlistCreate(req);
    }

    @Override
    public void playlistRename(PlaylistRenameRequest req) {
        this.delegate.playlistRename(req);
    }

    @Override
    public void playlistDelete(PlaylistDeleteRequest req) {
        this.delegate.playlistDelete(req);
    }

    @Override
    public void playlistRemove(PlaylistRemoveSongRequest req) {
        this.delegate.playlistRemove(req);
    }

    @Override
    public void starId(String id) {
        if (isOffline()) {
            throw new IllegalStateException("Cannot star while offline");
        }
        delegate.starId(id);
    }

    @Override
    public void unStarId(String id) {
        if (isOffline()) {
            throw new IllegalStateException("Cannot unstar while offline");
        }
        delegate.unStarId(id);
    }

    @Override
    public ServerType getServerType() {
        return delegate.getServerType();
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }

    @Override
    public ServerInfo getServerInfo() {
        return delegate.getServerInfo();
    }

    @Override
    public URI getStreamUri(String songId) {
        return delegate.getStreamUri(songId);
    }

    @Override
    public SearchResult search(String query) {
        return delegate.search(query);
    }

    // Conversion methods: database records -> ServerClient types

    private ArtistEntry toArtistEntry(Artist artist) {
        return new ArtistEntry(
                artist.id(),
                artist.name(),
                artist.albumCount(),
                artist.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.ArtistIdentifier(artist.id()))),
                artist.starredAt()
        );
    }

    private ArtistInfo toArtistInfo(Artist artist) {
        var albums = dbService.listAlbumsByArtist(artist.id());
        return new ArtistInfo(
                artist.id(),
                artist.name(),
                artist.albumCount(),
                artist.starredAt(),
                artist.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.ArtistIdentifier(artist.id()))),
                albums.stream().map(this::toArtistAlbumInfo).toList(),
                artist.biography()
                        .map(b -> new ServerClient.Biography(b.original(), "", ""))
                        .orElse(null)
        );
    }

    private ArtistAlbumInfo toArtistAlbumInfo(Album album) {
        return new ArtistAlbumInfo(
                album.id(),
                album.name(),
                album.songCount(),
                album.artistId(),
                album.artistName(),
                album.duration(),
                album.genre(),
                album.year(),
                album.starredAt(),
                album.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.AlbumIdentifier(album.id())))
        );
    }

    private AlbumInfo toAlbumInfo(Album album) {
        var songs = dbService.listSongsByAlbumId(album.id());
        return new AlbumInfo(
                album.id(),
                album.name(),
                album.songCount(),
                album.year(),
                album.artistId(),
                album.artistName(),
                album.duration(),
                album.starredAt(),
                album.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.AlbumIdentifier(album.id()))),
                songs.stream().map(this::toSongInfo).toList()
        );
    }

    private SongInfo toSongInfo(Song song) {
        // For offline mode, transcodeInfo and downloadUri are unavailable
        // streamUri is empty and will be resolved at play time if needed
        var transcodeInfo = new TranscodeInfo(
                song.id(),
                song.bitRate(),
                song.bitRate().orElse(0),
                song.duration(),
                song.suffix().isEmpty() ? "mp3" : song.suffix()
                //, Optional.empty()
        );
        return new SongInfo(
                song.id(),
                song.name(),
                song.trackNumber(),
                song.discNumber(),
                song.bitRate(),
                song.size(),
                song.year(),
                song.genre(),
                0L,
                Optional.empty(),
                song.artistId(),
                song.artistName(),
                song.albumId(),
                "", // album name not stored in song record
                song.duration(),
                song.starredAt(),
                song.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.AlbumIdentifier(song.albumId()))),
                song.suffix().isEmpty() ? "mp3" : song.suffix(),
                transcodeInfo,
                URI.create("offline://unavailable/" + song.id())
        );
    }

    private PlaylistSimple toPlaylistSimple(PlaylistRow row) {
        return new PlaylistSimple(
                row.id(),
                row.name(),
                PlaylistKind.NORMAL,
                row.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.PlaylistIdentifier(row.id()))),
                row.songCount(),
                row.createdAt()
        );
    }

    private Playlist toPlaylist(PlaylistRow row) {
        var songIds = dbService.listPlaylistSongIds(row.id());
        var songs = new ArrayList<SongInfo>();
        for (String songId : songIds) {
            dbService.getSongById(songId).map(this::toSongInfo).ifPresent(songs::add);
        }
        return new Playlist(
                row.id(),
                row.name(),
                PlaylistKind.NORMAL,
                row.coverArtId().flatMap(id -> toCoverArt(id, new ObjectIdentifier.PlaylistIdentifier(row.id()))),
                row.songCount(),
                row.createdAt(),
                songs
        );
    }

    private Optional<CoverArt> toCoverArt(String coverArtId, ObjectIdentifier identifier) {
        var cachePath = toCachePath(this.cacheRoot, this.serverId, coverArtId);
        return Optional.of(new CoverArt(
                serverId,
                coverArtId,
                URI.create("offline://coverart/" + coverArtId),
                cachePath.cachePath().toAbsolutePath(),
                Optional.of(identifier)
        ));
    }
}
