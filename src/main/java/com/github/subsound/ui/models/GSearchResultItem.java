package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;

import java.lang.foreign.MemorySegment;

public class GSearchResultItem extends GObject {
    public static final Type gtype = Types.register(GSearchResultItem.class);

    public sealed interface SearchEntry {
        record ArtistEntry(ServerClient.ArtistEntry artist) implements SearchEntry {}
        record AlbumEntry(ServerClient.ArtistAlbumInfo album) implements SearchEntry {}
        record SongEntry(ServerClient.SongInfo song) implements SearchEntry {}
    }

    private SearchEntry entry;

    public GSearchResultItem(MemorySegment address) {
        super(address);
    }

    public SearchEntry getEntry() {
        return entry;
    }

    @Property
    public String getId() {
        return switch (entry) {
            case SearchEntry.ArtistEntry a -> a.artist().id();
            case SearchEntry.AlbumEntry a -> a.album().id();
            case SearchEntry.SongEntry s -> s.song().id();
        };
    }

    public static GSearchResultItem ofArtist(ServerClient.ArtistEntry artist) {
        GSearchResultItem item = GObject.newInstance(gtype);
        item.entry = new SearchEntry.ArtistEntry(artist);
        return item;
    }

    public static GSearchResultItem ofAlbum(ServerClient.ArtistAlbumInfo album) {
        GSearchResultItem item = GObject.newInstance(gtype);
        item.entry = new SearchEntry.AlbumEntry(album);
        return item;
    }

    public static GSearchResultItem ofSong(ServerClient.SongInfo song) {
        GSearchResultItem item = GObject.newInstance(gtype);
        item.entry = new SearchEntry.SongEntry(song);
        return item;
    }
}
