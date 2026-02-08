package com.github.subsound.persistence;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.persistence.database.Album;
import com.github.subsound.persistence.database.Artist;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.PlaylistRow;
import com.github.subsound.persistence.database.Song;
import net.beardbot.subsonic.client.api.playlist.UpdatePlaylistParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.subsound.persistence.ThumbnailCache.toCachePath;

public class CachingClient implements ServerClient {
    private static final Logger log = LoggerFactory.getLogger(CachingClient.class);

    private final ServerClient delegate;
    public CachingClient(ServerClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ListArtists getArtists() {
        return delegate.getArtists();
    }

    @Override
    public ArtistInfo getArtistInfo(String artistId) {
        return delegate.getArtistInfo(artistId);
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        return delegate.getAlbumInfo(albumId);
    }

    @Override
    public ListPlaylists getPlaylists() {
        return delegate.getPlaylists();
    }

    @Override
    public Playlist getPlaylist(String playlistId) {
        return delegate.getPlaylist(playlistId);
    }

    @Override
    public ListStarred getStarred() {
        return delegate.getStarred();
    }

    @Override
    public SongInfo getSong(String songId) {
        return delegate.getSong(songId);
    }

    @Override
    public AddSongToPlaylist addToPlaylist(AddSongToPlaylist req) {
        this.delegate.addToPlaylist(req);
        return req;
    }

    @Override
    public HomeOverview getHomeOverview() {
        return delegate.getHomeOverview();
    }

    @Override
    public void starId(String id) {
        delegate.starId(id);
    }

    @Override
    public void unStarId(String id) {
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
}
