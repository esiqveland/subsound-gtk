package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.components.SettingsPage.SettingsInfo;
import com.github.subsound.ui.models.GSongInfo;
import org.gnome.adw.Toast;

import java.time.Duration;
import java.util.List;

public sealed interface PlayerAction {
    record Play() implements PlayerAction {}
    record Pause() implements PlayerAction {}
    record PlayPrev() implements PlayerAction {}
    record PlayNext() implements PlayerAction {}
    enum PlayMode {
        NORMAL,
        SHUFFLE,
        REPEAT_ONE,
        //REPEAT_ALL,
    }
    record SetPlayMode(PlayMode mode) implements PlayerAction {}
    record SeekTo(Duration position) implements PlayerAction {}
    record PlayAndReplaceQueue(List<SongInfo> queue, int position) implements PlayerAction {
        public static PlayAndReplaceQueue of(List<GSongInfo> queue, int position) {
            return new PlayAndReplaceQueue(queue.stream().map(GSongInfo::getSongInfo).toList(), position);
        }
        @Override
        public String toString() {
            return "PlayQueue(position=%d, size=%d)".formatted(position, this.queue.size());
        }
    }
    record PlayPositionInQueue(int position) implements PlayerAction {}
    record PlaySong(SongInfo song, boolean startPaused) implements PlayerAction {
        public PlaySong(SongInfo song) {
            this(song, false);
        }
    }
    record Enqueue(SongInfo song) implements PlayerAction {}
    record EnqueueLast(SongInfo song) implements PlayerAction {}
    record RemoveFromQueue(int position) implements PlayerAction {}
    record Star(SongInfo song) implements PlayerAction {}
    record Star2(GSongInfo song) implements PlayerAction {}
    record StarRefresh(boolean forced) implements PlayerAction {}
    record Unstar(SongInfo song) implements PlayerAction {}
    record AddToPlaylist(SongInfo song) implements PlayerAction {}
    record AddToDownloadQueue(SongInfo song) implements PlayerAction {}
    record RefreshPlaylists() implements PlayerAction {}

    // not strictly player actions:
    record SaveConfig(SettingsInfo next) implements PlayerAction {}
    record Toast(org.gnome.adw.Toast toast) implements PlayerAction {}
    record SyncDatabase() implements PlayerAction {}
}
