package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.integration.ServerClient.HomeOverview;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AlbumFlowBoxChild;
import com.github.subsound.ui.components.AlbumsFlowBox;
import com.github.subsound.ui.components.BoxHolder;
import com.github.subsound.ui.components.Classes;
import com.github.subsound.ui.components.LoadingSpinner;
import com.github.subsound.ui.components.OverviewAlbumChild;
import com.github.subsound.ui.views.FrontpagePage.FrontpagePageState.Loading;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Button;
import org.javagi.gio.ListIndexModel;
import org.gnome.adw.Carousel;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.subsound.ui.views.ArtistInfoFlowBox.BIG_SPACING;
import static com.github.subsound.utils.Utils.borderBox;
import static com.github.subsound.utils.Utils.heading1;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class FrontpagePage extends Box {
    private static final Logger log = LoggerFactory.getLogger(FrontpagePage.class);

    sealed interface FrontpagePageState {
        record Loading() implements FrontpagePageState {}
        record Ready(HomeOverview data) implements FrontpagePageState {}
        record Error(Throwable t) implements FrontpagePageState {}
    }

    private final ThumbnailCache thumbLoader;
    private final AppManager appManager;
    private final Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final AtomicReference<FrontpagePageState> state = new AtomicReference<>(new Loading());
    private final AtomicBoolean isMapped = new AtomicBoolean(false);

    private final ScrolledWindow scroll;
    private final Box view;
    private final Stack viewStack;
    private final LoadingSpinner spinner = new LoadingSpinner();
    private final Label errorLabel;
    private final HomeView homeView;

    public FrontpagePage(ThumbnailCache thumbLoader, AppManager appManager, Consumer<ArtistAlbumInfo> onAlbumSelected) {
        super(Orientation.VERTICAL, 0);
        this.onAlbumSelected = onAlbumSelected;
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(START);
        this.setValign(START);

        this.view = borderBox(Orientation.VERTICAL, BIG_SPACING).setSpacing(BIG_SPACING).setHalign(START).setValign(START).build();
        this.thumbLoader = thumbLoader;
        this.appManager = appManager;
        this.homeView = new HomeView(this.appManager, this.onAlbumSelected);
        this.onMap(() -> {
            if (this.isMapped.get()) {
                return;
            }
            log.info("FrontpagePage: onMap doLoad");
            isMapped.set(true);
            this.doLoad();
        });
        this.onUnmap(() -> {
            log.info("FrontpagePage: onUnmap");
            //isMapped.set(false);
        });
        //this.onRealize(() -> this.doLoad());

        this.viewStack = Stack.builder().setHhomogeneous(false).setHexpand(true).setVexpand(true).build();
        this.viewStack.setHalign(START);
        this.viewStack.setValign(START);
        this.viewStack.addNamed(spinner, "loading");
        this.errorLabel = Label.builder().build();
        this.viewStack.addNamed(errorLabel, "error");
        this.viewStack.addNamed(homeView, "home");
        this.view.append(Label.builder().setLabel("Home").setHalign(START).setCssClasses(Classes.titleLarge.add()).build());
        var refreshBox = borderBox(HORIZONTAL, 0).build();
        var reloadButton = Button.builder().setLabel("Refresh").setCssClasses(Classes.suggestedAction.add()).build();
        reloadButton.onClicked(this::doLoad);
        refreshBox.append(reloadButton);
        this.view.append(refreshBox);
        this.view.append(viewStack);
        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHscrollbarPolicy(PolicyType.NEVER)
                .setPropagateNaturalHeight(true)
                .setPropagateNaturalWidth(true)
                .setChild(view)
                .build();
        this.append(scroll);
    }

    private void doLoad() {
        this.setState(new Loading());
        Utils.doAsync(() -> {
            try {
                var data = this.appManager.useClient(ServerClient::getHomeOverview);
                this.setState(new FrontpagePageState.Ready(data));
            } catch (Exception e) {
                this.setState(new FrontpagePageState.Error(e));
            }
        });
    }

    private void setState(FrontpagePageState next) {
        this.state.set(next);
        this.updateView();
    }

    private void updateView() {
        if (!this.isMapped.get()) {
            return;
        }
        Utils.runOnMainThread(() -> {
            var s = this.state.get();
            switch (s) {
                case Loading l -> {
                    this.errorLabel.setLabel("");
                    this.viewStack.setVisibleChildName("loading");
                }
                case FrontpagePageState.Error t -> {
                    this.errorLabel.setLabel(t.t.getMessage());
                    this.viewStack.setVisibleChildName("error");
                }
                case FrontpagePageState.Ready ready -> {
                    this.errorLabel.setLabel("");
                    this.homeView.setData(ready.data);
                    this.viewStack.setVisibleChildName("home");
                }
            }
        });
    }

    private static class HomeView extends Box {
        private final AppManager appManager;
        private final Consumer<ArtistAlbumInfo> onAlbumSelected;

        private HomeOverview data;

        private final Label recentLabel = heading1("Recently played").build();
        private final Label newLabel = heading1("Newly added releases").build();
        private final Label mostLabel = heading1("Most played").build();

        private final BoxHolder<HorizontalAlbumsFlowBoxV3> recentList;
        private final BoxHolder<HorizontalAlbumsFlowBoxV3> newList;
        private final BoxHolder<HorizontalAlbumsFlowBoxV3> mostPlayedList;

        public HomeView(AppManager appManager, Consumer<ArtistAlbumInfo> onAlbumSelected) {
            super(Orientation.VERTICAL, BIG_SPACING);
            this.appManager = appManager;
            this.onAlbumSelected = onAlbumSelected;
            this.setHexpand(true);
            this.setVexpand(true);
            this.setHalign(START);
            this.setValign(START);
            this.recentList = holder();
            this.newList = holder();
            this.mostPlayedList = holder();
            this.append(wrap(recentLabel, recentList));
            this.append(wrap(newLabel, newList));
            this.append(wrap(mostLabel, mostPlayedList));
        }

        private static Box wrap(Label recentLabel, BoxHolder<HorizontalAlbumsFlowBoxV3> recentList) {
            var b = Box.builder().setOrientation(VERTICAL).setSpacing(8).build();
            b.append(recentLabel);
            b.append(recentList);
            return b;
        }

        private BoxHolder<HorizontalAlbumsFlowBoxV3> holder() {
            var h = new BoxHolder<HorizontalAlbumsFlowBoxV3>();
            h.setHexpand(true);
            h.setHalign(START);
            h.setValign(START);
            return h;
        }

        public static class HorizontalAlbumsFlowBoxV1 extends Box {
            private final ScrolledWindow scroll;

            public HorizontalAlbumsFlowBoxV1(AlbumsFlowBox child) {
                super(HORIZONTAL, 0);
                this.setHalign(START);
                this.setHexpand(true);
                this.scroll = ScrolledWindow.builder().setHalign(START).setHexpand(true).build();
                this.scroll.setPropagateNaturalWidth(true);
                this.scroll.setPropagateNaturalHeight(true);
                this.scroll.setChild(child);
                this.append(this.scroll);
            }
        }

        public static class HorizontalAlbumsFlowBoxV2 extends Box {
            private final List<ArtistAlbumInfo> albums;
            private final List<AlbumFlowBoxChild> list;
            private final Carousel carousel;
            private final AppManager thumbLoader;

            public HorizontalAlbumsFlowBoxV2(List<ArtistAlbumInfo> albums, AppManager thumbLoader) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.setHalign(START);
                this.setHexpand(true);
                this.carousel = Carousel.builder().setHexpand(true).build();
                this.carousel.setHalign(START);
                this.carousel.setHexpand(true);
                this.list = this.albums.stream().map(album -> {
                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
                    return item;
                }).toList();
                for (AlbumFlowBoxChild albumFlowBoxChild : list) {
                    this.carousel.append(albumFlowBoxChild);
                }
                this.append(this.carousel);
            }
        }

        public static class HorizontalAlbumsFlowBoxV3 extends Box {
            private final List<ArtistAlbumInfo> albums;
            private final List<OverviewAlbumChild> list;
            //private final List<AlbumFlowBoxChild> list;
            private final Box carousel;
            private final AppManager thumbLoader;
            private final ScrolledWindow scroll;
            private final Function<PlayerAction, CompletableFuture<Void>> onAction;
            private final Consumer<ArtistAlbumInfo> onAlbumSelected;

            public HorizontalAlbumsFlowBoxV3(
                    List<ArtistAlbumInfo> albums,
                    AppManager thumbLoader,
                    Function<PlayerAction, CompletableFuture<Void>> onAction,
                    Consumer<ArtistAlbumInfo> onAlbumSelected
            ) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.onAction = onAction;
                this.onAlbumSelected = onAlbumSelected;
                this.setHalign(START);
                this.setHexpand(true);
                this.carousel = Box.builder()
                        .setOrientation(HORIZONTAL)
                        .setHexpand(true)
                        .setHalign(START)
                        .setValign(START)
                        .setSpacing(BIG_SPACING / 2)
                        .build();
                this.list = this.albums.stream().map(album -> {
//                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
                    var item = new OverviewAlbumChild(this.thumbLoader, this.onAlbumSelected);
                    item.setAlbumInfo(album);
                    return item;
                }).toList();
                for (var albumFlowBoxChild : list) {
                    this.carousel.append(albumFlowBoxChild);
                }
                this.scroll = ScrolledWindow.builder()
                        .setHexpand(true)
                        .setVexpand(true)
                        .setPropagateNaturalHeight(true)
                        .setPropagateNaturalWidth(true)
                        .setVscrollbarPolicy(PolicyType.NEVER)
                        .setOverlayScrolling(true)
                        //.setVadjustment(Adjustment.builder().setUpper(0))
                        .build();
                this.scroll.setChild(this.carousel);
                this.append(this.scroll);
            }
        }

        public static class HorizontalAlbumsListView extends Box {
            private final AppManager thumbLoader;
            private final Consumer<ArtistAlbumInfo> onAlbumSelected;
            private final List<ArtistAlbumInfo> albums;
            private final ListView listView;
            private final ListIndexModel listModel;
            private final ScrolledWindow scroll;

            public HorizontalAlbumsListView(
                    List<ArtistAlbumInfo> albums,
                    AppManager thumbLoader,
                    Consumer<ArtistAlbumInfo> onAlbumSelected
            ) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.onAlbumSelected = onAlbumSelected;
                this.setHalign(START);
                this.setHexpand(true);
                var factory = new SignalListItemFactory();
                factory.onSetup(object -> {
                    ListItem listitem = (ListItem) object;
                    listitem.setActivatable(true);
                    var item = new OverviewAlbumChild(thumbLoader, this.onAlbumSelected);
                    listitem.setChild(item);
                });
                factory.onBind(object -> {
                    ListItem listitem = (ListItem) object;
                    ListIndexModel.ListIndex item = (ListIndexModel.ListIndex) listitem.getItem();
                    OverviewAlbumChild child = (OverviewAlbumChild) listitem.getChild();
                    if (child == null || item == null) {
                        return;
                    }
                    listitem.setActivatable(true);

                    // The ListIndexModel contains ListIndexItems that contain only their index in the list.
                    int index = item.getIndex();

                    // Retrieve the index of the item and show the entry from the ArrayList with random strings.
                    var album = this.albums.get(index);
                    child.setAlbumInfo(album);

                });
                this.listModel = ListIndexModel.newInstance(albums.size());
                this.listView = ListView.builder()
                        .setModel(new SingleSelection(this.listModel))
                        .setOrientation(HORIZONTAL)
                        .setHexpand(true)
                        .setHalign(START)
                        .setSingleClickActivate(true)
                        .setFactory(factory)
                        .build();
//                this.list = this.albums.stream().map(album -> {
//                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
//                    return item;
//                }).toList();
//                for (AlbumFlowBoxChild albumFlowBoxChild : list) {
//                    this.carousel.append(albumFlowBoxChild);
//                }
                this.scroll = ScrolledWindow.builder()
                        .setHexpand(true)
                        .setVexpand(true)
                        .setPropagateNaturalHeight(true)
                        .setPropagateNaturalWidth(true)
                        .setVscrollbarPolicy(PolicyType.NEVER)
                        .build();
                this.scroll.setChild(this.listView);
                this.append(this.scroll);
            }
        }

        private HorizontalAlbumsFlowBoxV3 flowBox(List<ArtistAlbumInfo> list) {
            return new HorizontalAlbumsFlowBoxV3(list, this.appManager, this.appManager::handleAction, this.onAlbumSelected);
        }

        public void setData(HomeOverview homeOverview) {
            this.data = homeOverview;
            this.update(homeOverview);
        }

        private void update(HomeOverview data) {
            var r = flowBox(data.recent());
            var n = flowBox(data.newest());
            var freq = flowBox(data.frequent());
            Utils.runOnMainThread(() -> {
                this.recentList.setChild(r);
                this.newList.setChild(n);
                this.mostPlayedList.setChild(freq);
            });
        }
    }
}
