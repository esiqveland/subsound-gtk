package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.ListStarred;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import com.github.subsound.ui.components.StarredItemRow;
import com.github.subsound.ui.views.StarredListView.UpdateListener.MiniState;
import com.github.subsound.utils.Utils;
import io.github.jwharm.javagi.gio.ListIndexModel;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.VERTICAL;

public class StarredListView extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(StarredListView.class);
    private final AppManager appManager;
    private final ListStarred data;
    private final ListView listView;
    private final ListIndexModel listModel;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ScrolledWindow scroll;
    private final AtomicReference<MiniState> prevState;
    private final Consumer<AppNavigation.AppRoute> onNavigate;

    public interface UpdateListener {
        record MiniState(Optional<SongInfo> songInfo, NowPlayingState nowPlayingState) {}
        void update(MiniState n);
    }
    private final ConcurrentHashMap<StarredItemRow, StarredItemRow> listeners = new ConcurrentHashMap<>();

    public StarredListView(
            ListStarred data,
            AppManager appManager,
            Consumer<AppNavigation.AppRoute> onNavigate
    ) {
        super(VERTICAL, 0);
        this.data = data;
        this.appManager = appManager;
        this.onAction = appManager::handleAction;
        this.prevState = new AtomicReference<>(selectState(null, appManager.getState()));
        this.onNavigate = onNavigate;
        this.setHalign(CENTER);
        this.setValign(START);
        this.setHexpand(true);
        this.setVexpand(true);
        log.info("StarredListView: item count={}", this.data.songs().size());

        var factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);

            var item = new StarredItemRow(this.appManager, this.onAction, this.onNavigate);
            listeners.put(item, item);
            listitem.setChild(item);
        });
        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            ListIndexModel.ListIndex item = (ListIndexModel.ListIndex) listitem.getItem();
            if (item == null) {
                return;
            }
            // The ListIndexModel contains ListIndexItems that contain only their index in the list.
            int index = item.getIndex();

            // Retrieve the index of the item and show the entry from the ArrayList with random strings.
            var songInfo = this.data.songs().get(index);
            if (songInfo == null) {
                return;
            }
            var child = (StarredItemRow) listitem.getChild();
            if (child == null) {
                return;
            }
            listitem.setActivatable(true);
            log.info("factory.onBind: {} {}", songInfo.id(), songInfo.title());
            child.setSongInfo(songInfo, index, prevState.get());
        });
        factory.onTeardown(item -> {
            ListItem listitem = (ListItem) item;
            var child = (StarredItemRow) listitem.getChild();
            if (child == null) {
                return;
            }
            //log.info("StarredListView.onTeardown");
            this.listeners.remove(child);
        });
        this.listModel = ListIndexModel.newInstance(data.songs().size());
        this.listView = ListView.builder()
                //.setCssClasses(cssClasses(Classes.richlist.className()))
                //.setCssClasses(cssClasses("boxed-list"))
                .setShowSeparators(false)
                .setOrientation(VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(true)
                .setSingleClickActivate(false)
                .setModel(new SingleSelection(this.listModel))
                .setFactory(factory)
                .build();
        this.listView.onActivate(index -> {
            var songInfo = this.data.songs().get(index);
            if (songInfo == null) {
                return;
            }
            List<SongInfo> songs = this.data.songs();
            log.debug("listView.onActivate: {} {}", index, songInfo.title());
            this.onAction.apply(new PlayerAction.PlayQueue(songs, index));
        });
        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .build();
        this.scroll.setChild(this.listView);
        this.append(this.scroll);
        this.onMap(() -> appManager.addOnStateChanged(this));
        this.onUnmap(() -> appManager.removeOnStateChanged(this));
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var prev = prevState.get();
        var next = selectState(prev, state);
        if (next == prev) {
            return;
        }
        this.prevState.set(next);
        Utils.doAsync(() -> this.listeners.forEach((k, listener) -> {
            listener.update(next);
        }));

    }

    private MiniState selectState(@Nullable MiniState prev, AppManager.AppState state) {
        var npSong = state.nowPlaying().map(AppManager.NowPlaying::song);
        var nowPlayingState = getNowPlayingState(state.player().state());
        if (prev == null) {
            return new MiniState(npSong, nowPlayingState);
        }
        if (prev.songInfo == npSong && prev.nowPlayingState == nowPlayingState) {
            return prev;
        }
        if (npSong.isPresent()) {
            if (npSong.get().id().equals(prev.songInfo.map(SongInfo::id).orElse(""))) {
                if (prev.nowPlayingState == nowPlayingState) {
                    return prev;
                } else {
                    return new MiniState(npSong, nowPlayingState);
                }
            }
        }
        return new MiniState(npSong, nowPlayingState);
    }

    private NowPlayingState getNowPlayingState(PlaybinPlayer.PlayerStates state) {
        return switch (state) {
            case INIT -> NowPlayingState.NONE;
            case BUFFERING -> NowPlayingState.LOADING;
            case READY -> NowPlayingState.PAUSED;
            case PAUSED -> NowPlayingState.PAUSED;
            case PLAYING -> NowPlayingState.PLAYING;
            case END_OF_STREAM -> NowPlayingState.NONE;
        };
    }
}