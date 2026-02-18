package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient.ObjectIdentifier.SongIdentifier;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.database.DownloadQueueItem;
import com.github.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
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

import static com.github.subsound.utils.Utils.runOnMainThread;

public class GSongInfo extends GObject {
    public static final Type gtype = Types.register(GSongInfo.class);

    public static class GSongStore {
        private final ConcurrentHashMap<String, GSongInfo> store = new ConcurrentHashMap<>();
        private final Function<String, Optional<DownloadQueueItem>> downloadManager;
        private final Function<String, SongInfo> songLoader;

        public GSongStore(
                Function<String, SongInfo> songLoader,
                Function<String, Optional<DownloadQueueItem>> downloadManager
        ) {
            this.downloadManager = downloadManager;
            this.songLoader = songLoader;
        }

        public GSongInfo getSongById(SongIdentifier id) {
            return store.computeIfAbsent(id.songId(), key -> {
                var song = songLoader.apply(key);
                return this.newInstance(song);
            });
        }
        public GSongInfo getSongById(String songId) {
            return getSongById(new SongIdentifier(songId));
        }


        public Optional<GSongInfo> getExisting(String songId) {
            return Optional.ofNullable(store.get(songId));
        }
        public GSongInfo get(SongInfo songInfo) {
            return newInstance(songInfo);
        }

        public GSongInfo newInstance(SongInfo value) {
            // TODO: replace with the updated SongInfo data
            var gsong = store.computeIfAbsent(
                    value.id(),
                    key -> {
                        GSongInfo instance = GObject.newInstance(getType());
                        instance.songInfo = value;
                        var songStatus = this.downloadManager.apply(key);
                        songStatus.ifPresent(item -> instance.setDownloadStateEnum(item.status()));
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

    private volatile GDownloadState downloadState = GDownloadState.NONE;

    public static Type getType() {
        return gtype;
    }

    private SongInfo songInfo;

    public GSongInfo(MemorySegment address) {
        super(address);
    }

    public SongInfo getSongInfo() {
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
    // for some reason java-gi throws an exception on startup if we return the raw enum here:
    public int getDownloadState() {
        return this.downloadState.ordinal();
    }
    @Property
    public void setDownloadState(int next) {
        var nex = GDownloadState.fromOrdinal(next);
        if (this.downloadState != nex) {
            this.downloadState = nex;
            runOnMainThread(() -> this.notify(Signal.DOWNLOAD_STATE.signal));
        }
    }
    @Property(skip = true)
    public GDownloadState getDownloadStateEnum() {
        return this.downloadState;
    }

    @Property(skip = true)
    public void setDownloadStateEnum(GDownloadState next) {
        this.setDownloadState(next.ordinal());
    }
    @Property(skip = true)
    public void setDownloadStateEnum(DownloadStatus next) {
        this.setDownloadStateEnum(switch (next) {
            case PENDING -> GDownloadState.PENDING;
            case DOWNLOADING -> GDownloadState.DOWNLOADING;
            case COMPLETED -> GDownloadState.DOWNLOADED;
            case FAILED -> GDownloadState.NONE;
            case CACHED -> GDownloadState.CACHED;
        });
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

    public void mutate(Function<SongInfo, SongInfo> modifier) {
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
        DOWNLOAD_STATE("download-state"),
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
