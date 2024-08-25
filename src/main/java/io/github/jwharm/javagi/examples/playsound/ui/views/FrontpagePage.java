package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.HomeOverview;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.AlbumFlowBoxChild;
import io.github.jwharm.javagi.examples.playsound.ui.components.AlbumsFlowBox;
import io.github.jwharm.javagi.examples.playsound.ui.components.LoadingSpinner;
import io.github.jwharm.javagi.examples.playsound.ui.components.OverviewAlbumChild;
import io.github.jwharm.javagi.examples.playsound.ui.views.FrontpagePage.FrontpagePageState.Loading;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.gio.ListIndexModel;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.jwharm.javagi.examples.playsound.ui.views.ArtistInfoFlowBox.BIG_SPACING;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.borderBox;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.heading1;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;

public class FrontpagePage extends Box {


    sealed interface FrontpagePageState {
        record Loading() implements FrontpagePageState {}
        record Ready(HomeOverview data) implements FrontpagePageState {}
        record Error(Throwable t) implements FrontpagePageState {}
    }

    private final ThumbnailCache thumbLoader;
    private final AppManager appManager;
    private final AtomicReference<FrontpagePageState> state = new AtomicReference<>(new Loading());

    private final Box view;
    private final Stack viewStack;
    private final LoadingSpinner spinner = new LoadingSpinner();
    private final Label errorLabel;
    private final HomeView homeView;

    public FrontpagePage(ThumbnailCache thumbLoader, AppManager appManager) {
        super(Orientation.VERTICAL, 0);
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(START);
        this.setValign(START);

        this.view = borderBox(Orientation.VERTICAL, BIG_SPACING).setHalign(START).setValign(START).build();
        this.thumbLoader = thumbLoader;
        this.appManager = appManager;
        this.homeView = new HomeView(thumbLoader);
        this.onMap(() -> this.doLoad());

        this.viewStack = Stack.builder().setHhomogeneous(false).setHexpand(true).setVexpand(true).build();
        this.viewStack.setHalign(START);
        this.viewStack.setValign(START);
        this.viewStack.addNamed(spinner, "loading");
        this.errorLabel = Label.builder().build();
        this.viewStack.addNamed(errorLabel, "error");
        this.viewStack.addNamed(homeView, "home");
        this.view.append(Label.builder().setLabel("fdsafdsafdsa").build());
        this.view.append(viewStack);
        this.append(view);
    }

    private void doLoad() {
        try {
            this.setState(new Loading());
            var data = this.appManager.getClient().getHomeOverview();
            this.setState(new FrontpagePageState.Ready(data));
        } catch (Exception e) {
            this.setState(new FrontpagePageState.Error(e));
        }
    }

    private void setState(FrontpagePageState next) {
        this.state.set(next);
        this.updateView();
    }

    private void updateView() {
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
    }

    private static class HomeView extends Box {
        private final ThumbnailCache thumbLoader;

        private HomeOverview data;

        private final Label recentLabel = heading1("Recently played").build();
        private final Label newLabel = heading1("Newly added").build();

        private HorizontalAlbumsFlowBoxV3 recentList;
        private HorizontalAlbumsFlowBoxV3 newList;

        public HomeView(ThumbnailCache thumbLoader) {
            super(Orientation.VERTICAL, 0);
            this.setHexpand(true);
            this.setVexpand(true);
            this.setHalign(START);
            this.setValign(START);
            this.thumbLoader = thumbLoader;
            this.recentList = flowBox(List.of());
            this.newList = flowBox(List.of());
            this.append(recentLabel);
            this.append(recentList);
            this.append(newLabel);
            this.append(newList);
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
            private final ThumbnailCache thumbLoader;

            public HorizontalAlbumsFlowBoxV2(List<ArtistAlbumInfo> albums, ThumbnailCache thumbLoader) {
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
            private final List<AlbumFlowBoxChild> list;
            private final Box carousel;
            private final ThumbnailCache thumbLoader;
            private final ScrolledWindow scroll;

            public HorizontalAlbumsFlowBoxV3(List<ArtistAlbumInfo> albums, ThumbnailCache thumbLoader) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.setHalign(START);
                this.setHexpand(true);
                this.carousel = Box.builder()
                        .setOrientation(HORIZONTAL)
                        .setHexpand(true)
                        .setHalign(START)
                        .setValign(START)
                        .setSpacing(24)
                        .build();
                this.list = this.albums.stream().map(album -> {
                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
                    return item;
                }).toList();
                for (AlbumFlowBoxChild albumFlowBoxChild : list) {
                    this.carousel.append(albumFlowBoxChild);
                }
                this.scroll = ScrolledWindow.builder()
                        .setHexpand(true)
                        .setVexpand(true)
                        .setPropagateNaturalHeight(true)
                        .setPropagateNaturalWidth(true)
                        .setVscrollbarPolicy(PolicyType.NEVER)
                        .build();
                this.scroll.setChild(this.carousel);
                this.append(this.scroll);
            }
        }

        public static class HorizontalAlbumsListView extends Box {
            private final List<ArtistAlbumInfo> albums;
            private final ListView listView;
            private final ThumbnailCache thumbLoader;
            private final ListIndexModel listModel;
            private final ScrolledWindow scroll;

            public HorizontalAlbumsListView(List<ArtistAlbumInfo> albums, ThumbnailCache thumbLoader) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.setHalign(START);
                this.setHexpand(true);
                var factory = new SignalListItemFactory();
                factory.onSetup(object -> {
                    ListItem listitem = (ListItem) object;
                    listitem.setActivatable(true);
                    var item = new OverviewAlbumChild(thumbLoader);
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
            return new HorizontalAlbumsFlowBoxV3(list, this.thumbLoader);
        }

        public void setData(HomeOverview homeOverview) {
            this.data = homeOverview;
            this.update(homeOverview);
        }

        private void update(HomeOverview data) {
            var r = flowBox(data.recent());
            var n = flowBox(data.newest());
            Utils.runOnMainThread(() -> {
                this.remove(this.recentList);
                this.remove(this.newList);
                this.append(r);
                this.append(n);
            });
            this.recentList = r;
            this.newList = n;
        }
    }
}
