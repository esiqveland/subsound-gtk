package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import com.github.subsound.ui.components.StarredItemRow;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.ui.views.PlaylistListViewV2.MiniState;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.MultiSelection;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.subsound.app.state.PlaylistsStore.STARRED_ID;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.VERTICAL;

public class StarredListView extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(StarredListView.class);
    private final AppManager appManager;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Consumer<AppNavigation.AppRoute> onNavigate;
    private final AtomicReference<MiniState> prevState;
    private final ListStore<GSongInfo> listModel;
    private final ListView listView;
    private final ScrolledWindow scroll;
    private final MultiSelection<GSongInfo> selectionModel;
    private final EventControllerKey keyController;

    private final ConcurrentHashMap<StarredItemRow, StarredItemRow> listeners = new ConcurrentHashMap<>();

    public StarredListView(
            ListStore<GSongInfo> listModel,
            AppManager appManager,
            Consumer<AppNavigation.AppRoute> onNavigate
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.onAction = appManager::handleAction;
        this.listModel = listModel;
        this.prevState = new AtomicReference<>(selectState(null, appManager.getState()));
        this.onNavigate = onNavigate;
        this.setHalign(CENTER);
        this.setValign(START);
        this.setHexpand(true);
        this.setVexpand(true);
        log.info("StarredListView: item count={}", this.listModel.getNItems());

        var factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            var start = System.nanoTime();
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);

            var item = new StarredItemRow(this.appManager, this.onAction, this.onNavigate);
            listeners.put(item, item);
            listitem.setChild(item);
            var elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.info("factory.onSetup: {} elapsed={}", listeners.size(), elapsed);
        });

        factory.onBind(object -> {
            var start = System.nanoTime();
            ListItem listitem = (ListItem) object;
            var item = (GSongInfo) listitem.getItem();
            if (item == null) {
                return;
            }

            var songInfo = item.getSongInfo();
            if (songInfo == null) {
                return;
            }
            var child = listitem.getChild();
            if (child == null) {
                return;
            }
            if (child instanceof StarredItemRow row) {
                row.setSongInfo(item, listitem, prevState.get());
            }
            listitem.setActivatable(true);
            var elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            log.info("factory.onBind: {} {} elapsed={}", songInfo.id(), songInfo.title(), elapsed);
        });
        factory.onUnbind(object -> {
            ListItem listitem = (ListItem) object;
            var item = (GSongInfo) listitem.getItem();
            if (item == null) {
                return;
            }
            var child = listitem.getChild();
            if (child == null) {
                return;
            }
            if (child instanceof StarredItemRow row) {
                row.unbind();
            } else {
                log.warn("StarredListView.onUnbind: unexpected child type: {}", child.getClass().getName());
            }
        });
        factory.onTeardown(item -> {
            ListItem listitem = (ListItem) item;
            var child = (StarredItemRow) listitem.getChild();
            if (child == null) {
                return;
            }
            //log.info("StarredListView.onTeardown");
            this.listeners.remove(child);
            listitem.setChild(null);
        });


        this.selectionModel = new MultiSelection<>(this.listModel);
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
                .setFactory(factory)
                .setModel(selectionModel)
                .build();
        var activateSignal = this.listView.onActivate(index -> {
            //var songInfo = this.data.songs().get(index);
            var songInfo = this.listModel.getItem(index);
            if (songInfo == null) {
                return;
            }
            log.info("listView.onActivate: {} {}", index, songInfo.getTitle());
            List<SongInfo> songs = this.listModel.stream().map(GSongInfo::getSongInfo).toList();
            this.onAction.apply(new PlayerAction.PlayAndReplaceQueue(
                    new ServerClient.ObjectIdentifier.PlaylistIdentifier(STARRED_ID),
                    songs,
                    index
            ));
        });

        // Key controller to handle Delete key for unstarring selected songs
        keyController = new EventControllerKey();
        keyController.onKeyPressed((keyval, keycode, state) -> {
            // GDK_KEY_Delete = 0xFFFF (65535)
            if (keyval == 0xFFFF) {
                var selection = this.selectionModel.getSelection();
                if (selection.isEmpty()) {
                    return false;
                }
                int selectionSize = (int) selection.getSize();
                log.info("Delete pressed, unstarring {} songs", selectionSize);
                for (int i = 0; i < selectionSize; i++) {
                    int idx = selection.getNth(i);
                    var gSongInfo = this.listModel.getItem(idx);
                    if (gSongInfo != null) {
                        var song = gSongInfo.getSongInfo();
                        if (song != null) {
                            this.onAction.apply(new PlayerAction.Unstar(song));
                        }
                    }
                }
                return true;
            }
            return false;
        });
        this.listView.addController(keyController);

        var mapSignal = this.onMap(() -> {
            appManager.addOnStateChanged(this);
        });
        this.onDestroy(() -> {
            log.info("StarredListView: onDestroy");
            appManager.removeOnStateChanged(this);
            mapSignal.disconnect();
            activateSignal.disconnect();
        });

        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .build();
//        var clamp = Clamp.builder().setMaximumSize(800).build();
//        clamp.setHalign(FILL);
//        clamp.setValign(FILL);
//        clamp.setHexpand(true);
//        clamp.setChild(this.listView);
//        this.scroll.setChild(clamp);
        this.scroll.setChild(this.listView);
        this.append(this.scroll);
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
            return new MiniState(npSong, nowPlayingState, state.queue().playContext(), state.queue().position());
        }
        var pos = state.queue().position();
        if (prev.songInfo() == npSong && prev.nowPlayingState() == nowPlayingState && prev.position() == pos) {
            return prev;
        }
        var playContext = state.queue().playContext();
        if (npSong.isPresent()) {
            if (npSong.get().id().equals(prev.songInfo().map(SongInfo::id).orElse(""))) {
                if (prev.nowPlayingState() == nowPlayingState) {
                    return prev;
                } else {
                    return new MiniState(npSong, nowPlayingState, playContext, pos);
                }
            }
        }
        return new MiniState(npSong, nowPlayingState, playContext, pos);
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