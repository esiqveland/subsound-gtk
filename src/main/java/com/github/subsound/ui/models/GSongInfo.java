package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.SongIdentifier;
import com.github.subsound.integration.ServerClient.SongInfo;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.github.subsound.utils.Utils.runOnMainThread;

public class GSongInfo extends GObject {
    public static final Type gtype = Types.register(GSongInfo.class);

    public static class GSongStore {
        private final ConcurrentHashMap<String, GSongInfo> store = new ConcurrentHashMap<>();
        public GSongStore() {}


        public GSongInfo get(SongInfo songInfo) {
            return newInstance(songInfo);
        }

        public GSongInfo newInstance(SongInfo value) {
            // TODO: replace with the updated SongInfo data
            var gsong = store.computeIfAbsent(
                    value.id(),
                    (key) -> {
                        GSongInfo instance = GObject.newInstance(gtype);
                        instance.songInfo = value;
                        return instance;
                    }
            );
            gsong.mutate(_ -> value);
            return gsong;
        }

        public int size() {
            return store.size();
        }
    }

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isFavorite = new AtomicBoolean(false);
    private final Lock lock = new ReentrantLock();

    public static Type getType() {
        return gtype;
    }

    private ServerClient.SongInfo songInfo;

    public GSongInfo(MemorySegment address) {
        super(address);
    }

    public ServerClient.SongInfo getSongInfo() {
        return songInfo;
    }
    @Property
    public String getId() {
        return songInfo.id();
    }

    @Property
    public boolean getIsPlaying() {
        return this.isPlaying.get();
    }

    @Property
    public void setIsPlaying(boolean isPlaying) {
        if (this.isPlaying.compareAndSet(!isPlaying, isPlaying)) {
            runOnMainThread(() -> this.notify(Signal.IS_PLAYING.signal));
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
                // notify always need to happen on the main thread, as it triggers signals in GTK objects that could trigger UI updates...
                runOnMainThread(() -> this.notify(Signal.IS_FAVORITE.signal));
            }
        } finally {
            lock.unlock();
        }
    }

    @Property
    public boolean getIsStarred() {
        return this.isFavorite.get();
    }

    public Optional<Instant> getStarredAt() {
        return this.songInfo.starred();
    }

    public enum Signal {
        NAME("name"),
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

    @Property
    public String getTitle() {
        return this.songInfo.title();
    }
}
