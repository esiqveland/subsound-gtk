package com.github.subsound.debug;

import io.github.jwharm.javagi.gio.ListIndexModel;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.Align;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.pango.EllipsizeMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DebugGtkListViewIssue {
    private static final Logger log = LoggerFactory.getLogger(DebugGtkListViewIssue.class);

    public static void main(String[] args) {
        int size = 4000;
        var dataList = new ArrayList<SampleData>(size);
        for (int i = 0; i < size; i++) {
            dataList.add(generateSample(i));
        }

        Application app = new Application("com.listview.example", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(() -> {
            new MainApplication(app, dataList);
        });
        app.onShutdown(() -> {
            System.out.println("app.onShutdown: exit");
        });
        app.run(args);

    }

    public record SampleData(int index, String value) {
    }

    private static SampleData generateSample(int i) {
        return new SampleData(i, UUID.randomUUID().toString());
    }

    public static class MainApplication {
        private final Application app;
        private final List<SampleData> data;

        private final ScrolledWindow scrollView;
        private final ListView listView;
        private final ListIndexModel listModel;
        private final SignalListItemFactory factory;

        public MainApplication(Application app, List<SampleData> data) {
            this.app = app;
            this.data = data;

            this.listModel = ListIndexModel.newInstance(this.data.size());

            factory = new SignalListItemFactory();
            factory.onSetup(object -> {
                ListItem listitem = (ListItem) object;
                var item = Label.builder().setLabel("").setUseMarkup(false).setEllipsize(EllipsizeMode.END).build();
                listitem.setChild(item);
            });
            factory.onBind(object -> {
                ListItem listitem = (ListItem) object;
                ListIndexModel.ListIndex item = (ListIndexModel.ListIndex) listitem.getItem();
                if (item == null) {
                    return;
                }
                int index = item.getIndex();

                var sample = this.data.get(index);
                if (sample == null) {
                    return;
                }
                var child = (Label) listitem.getChild();
                if (child == null) {
                    return;
                }
                listitem.setActivatable(true);
                log.info("factory.onBind: {} {}", index, sample.value());
                child.setLabel("%d - %s".formatted(index + 1, sample.value));
            });
            this.listView = new ListView(new SingleSelection(this.listModel), factory);
//            this.listView.onActivate(index -> {
//                var sample = this.data.get(index);
//                log.info("onActivate: {} - {}", index, sample.value);
//            });

//            var centerBox = Box.builder().setHexpand(true).setVexpand(true).setOrientation(Orientation.VERTICAL).setHalign(Align.CENTER).build();
//            centerBox.append(listView);

//            var centerBox = Box.builder().setHexpand(true).setVexpand(true).setOrientation(Orientation.VERTICAL).build();
//            centerBox.append(listView);

//            var centerBox = Clamp.builder().setMaximumSize(400).setChild(listView).build();

            this.scrollView = new ScrolledWindow();
            this.scrollView.setHalign(Align.CENTER);
            this.scrollView.setVexpand(true);
            this.scrollView.setHexpand(true);
            this.scrollView.setMaxContentWidth(400);
            this.scrollView.setPropagateNaturalHeight(true);
            this.scrollView.setPropagateNaturalWidth(true);
            this.scrollView.setChild(listView);

            Box mainWindow = Box.builder().setHexpand(true).setVexpand(true).setOrientation(Orientation.VERTICAL).build();
            mainWindow.append(this.scrollView);

            var window = ApplicationWindow.builder()
                    .setApplication(app)
                    .setDefaultWidth(1100)
                    .setDefaultHeight(800)
                    .setChild(mainWindow)
                    .build();

            window.present();
        }
    }
}
