package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.ui.views.StarredListView;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public class GSongInfo extends GObject {
    public static final Type gtype = Types.register(GSongInfo.class);

    public enum Signal {
        IS_PLAYING("is-playing"),
        IS_FAVORITE("is-favorite");
        private final String signal;

        Signal(String signal) {
            this.signal = signal;
        }

        public String getId() {
            return this.signal;
        }
    }

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isFavorite = new AtomicBoolean(false);
    private final Lock lock = new ReentrantLock();

    public static Type getType() {
        return gtype;
    }

    private ServerClient.SongInfo songInfo;

    @Property
    public boolean getIsPlaying() {
        return this.isPlaying.get();
    }

    @Property
    public void setIsPlaying(boolean isPlaying) {
        if (this.isPlaying.compareAndSet(!isPlaying, isPlaying)) {
            this.notify(Signal.IS_PLAYING.signal);
        }
    }

    @Property
    public void setIsFavorite(boolean isFavorite) {
        if (this.isFavorite.compareAndSet(!isFavorite, isFavorite)) {
            Optional<Instant> starredAt = Optional.ofNullable(isFavorite ? Instant.now() : null);
            this.mutate(songInfo -> songInfo.withStarred(starredAt));
        }
    }

    public GSongInfo setStarredAt(Optional<Instant> starredAt) {
        if (starredAt.isPresent() != this.songInfo.starred().isPresent()) {
            this.mutate(songInfo -> songInfo.withStarred(starredAt));
        }
        return this;
    }

    public void mutate(Function<ServerClient.SongInfo, ServerClient.SongInfo> modifier) {
        lock.lock();
        try {
            this.songInfo = modifier.apply(this.songInfo);
            boolean isFavorite = this.songInfo.starred().isPresent();
            if (this.isFavorite.get() != isFavorite) {
                this.isFavorite.set(isFavorite);
                this.notify(Signal.IS_FAVORITE.signal);
            }
        } finally {
            lock.unlock();
        }
    }

    public GSongInfo(MemorySegment address) {
        super(address);
    }

    public ServerClient.SongInfo getSongInfo() {
        return songInfo;
    }

    public static GSongInfo newInstance(ServerClient.SongInfo value) {
        GSongInfo instance = GObject.newInstance(gtype);
        instance.songInfo = value;
        return instance;
    }
}
