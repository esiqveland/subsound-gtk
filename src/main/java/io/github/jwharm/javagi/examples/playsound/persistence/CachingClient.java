package io.github.jwharm.javagi.examples.playsound.persistence;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;

public class CachingClient implements ServerClient {
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
    public ListStarred getStarred() {
        return delegate.getStarred();
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
}