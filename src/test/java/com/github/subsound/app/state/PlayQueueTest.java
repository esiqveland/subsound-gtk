package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.SongInfoFactory;
import com.github.subsound.sound.PlaybinPlayer.PlayerState;
import com.github.subsound.sound.PlaybinPlayer.PlayerStates;
import com.github.subsound.sound.PlaybinPlayer.Source;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.sound.Player;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

public class PlayQueueTest {

    private StubPlayer player;
    private PlayQueueStateRecorder stateChangedRecorder;
    private SongInfoRecorder playRecorder;
    private PlayQueue playQueue;

    @Before
    public void setUp() {
        Utils.setTestMode(true);
        player = new StubPlayer();
        stateChangedRecorder = new PlayQueueStateRecorder();
        playRecorder = new SongInfoRecorder();

        playQueue = new PlayQueue(player, stateChangedRecorder, playRecorder);
    }

    @After
    public void tearDown() {
        Utils.setTestMode(false);
    }

    @Test
    public void testEnqueue() {
        SongInfo song = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueue(song);

        PlayQueue.PlayQueueState state = playQueue.getState();
        assertThat(state.position()).isEmpty();
        assertThat(stateChangedRecorder.states).isNotEmpty();
        assertThat(playQueue.getListStore().getFirst().songInfo()).isEqualTo(song);
    }

    @Test
    public void testReplaceQueue() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 1).join();

        PlayQueue.PlayQueueState state = playQueue.getState();
        assertThat(state.position()).hasValue(1);
        assertThat(stateChangedRecorder.states).isNotEmpty();
        assertThat(playQueue.getListStore().get(0).songInfo()).isEqualTo(songs.get(0));
        assertThat(playQueue.getListStore().get(1).songInfo()).isEqualTo(songs.get(1));
    }

    @Test
    public void testPlayPosition() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();
        playQueue.playPosition(1);

        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).contains(songs.get(1));
    }

    @Test
    public void testAttemptPlayNext() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();
        playQueue.attemptPlayNext();

        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).contains(songs.get(1));
    }

    @Test
    public void testAttemptPlayNextAtEnd() {
        List<SongInfo> songs = List.of(SongInfoFactory.createRandomSongInfo());
        playQueue.replaceQueue(songs, 0).join();
        playRecorder.songs.clear();
        playQueue.attemptPlayNext();

        assertThat(playQueue.getState().position()).hasValue(0);
        assertThat(playRecorder.songs).isEmpty();
    }

    @Test
    public void testAttemptPlayPrevSeeksIfFarInSong() {
        var songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        var song = songs.getFirst();
        playQueue.replaceQueue(songs, 0).join();

        player.currentState = new PlayerState(
                PlayerStates.PLAYING,
                1.0,
                false,
                Optional.empty(),
                Optional.of(new Source(song.downloadUri(), Optional.of(Duration.ofSeconds(5)), Optional.of(song.duration())))
        );

        playQueue.attemptPlayPrev();

        assertThat(player.seekedTo).isEqualTo(Duration.ZERO);
        assertThat(playQueue.getState().position()).hasValue(0);
        assertThat(playRecorder.songs).isEmpty();
    }

    @Test
    public void testAttemptPlayPrevGoesToPreviousSong() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 1).join();

        player.currentState = new PlayerState(
                PlayerStates.PLAYING,
                1.0,
                false,
                Optional.empty(),
                Optional.of(new Source(
                        songs.get(1).downloadUri(),
                        Optional.of(Duration.ofSeconds(2)),
                        Optional.of(songs.get(1).duration())
                ))
        );

        playQueue.attemptPlayPrev();

        assertThat(playQueue.getState().position()).hasValue(0);
        assertThat(playRecorder.songs).contains(songs.get(0));
    }

    @Test
    public void testOnEndOfStreamPlaysNext() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();

        PlayerState endOfStreamState = new PlayerState(
                PlayerStates.END_OF_STREAM,
                1.0,
                false,
                Optional.empty(),
                Optional.empty()
        );

        playQueue.onState(endOfStreamState);

        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).contains(songs.get(1));
    }

    @Test
    public void testEnqueueMarksItemAsUserQueued() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();

        SongInfo enqueued = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueue(enqueued);

        // enqueue inserts at position+1 with userQueued=true
        assertThat(playQueue.getListStore().get(1).songInfo()).isEqualTo(enqueued);
        assertThat(playQueue.getListStore().get(1).getIsUserQueued()).isTrue();
        // original items are not user-queued
        assertThat(playQueue.getListStore().get(0).getIsUserQueued()).isFalse();
        assertThat(playQueue.getListStore().get(2).getIsUserQueued()).isFalse();
    }

    @Test
    public void testEnqueueLastInsertsAfterUserQueuedBeforeAutoQueued() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();

        // Enqueue a user-queued song via enqueue (Play Next)
        SongInfo userSong = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueue(userSong);

        // Now enqueueLast should insert after the user-queued song but before auto-queued
        SongInfo lastSong = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueueLast(lastSong);

        // Queue should be: [song0(current)] [userSong] [lastSong] [song1] [song2]
        assertThat(playQueue.getListStore().get(0).songInfo()).isEqualTo(songs.get(0));
        assertThat(playQueue.getListStore().get(1).songInfo()).isEqualTo(userSong);
        assertThat(playQueue.getListStore().get(1).getIsUserQueued()).isTrue();
        assertThat(playQueue.getListStore().get(2).songInfo()).isEqualTo(lastSong);
        assertThat(playQueue.getListStore().get(2).getIsUserQueued()).isTrue();
        assertThat(playQueue.getListStore().get(3).songInfo()).isEqualTo(songs.get(1));
        assertThat(playQueue.getListStore().get(3).getIsUserQueued()).isFalse();
    }

    @Test
    public void testEnqueueLastWithNoUserQueuedInsertsAtPositionPlusOne() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();

        SongInfo lastSong = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueueLast(lastSong);

        // With no user-queued songs, should insert at position+1
        assertThat(playQueue.getListStore().get(1).songInfo()).isEqualTo(lastSong);
        assertThat(playQueue.getListStore().get(1).getIsUserQueued()).isTrue();
        assertThat(playQueue.getListStore().get(2).songInfo()).isEqualTo(songs.get(1));
    }

    @Test
    public void testEnqueueLastWithMultipleUserQueuedInsertsAfterLastOne() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();

        // Add multiple user-queued songs
        SongInfo user1 = SongInfoFactory.createRandomSongInfo();
        SongInfo user2 = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueue(user2);
        playQueue.enqueue(user1);
        // After enqueue: [song0] [user1] [user2] [song1]

        SongInfo lastSong = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueueLast(lastSong);

        // Should insert after user2 but before song1:
        // [song0] [user1] [user2] [lastSong] [song1]
        assertThat(playQueue.getListStore().get(0).songInfo()).isEqualTo(songs.get(0));
        assertThat(playQueue.getListStore().get(1).songInfo()).isEqualTo(user1);
        assertThat(playQueue.getListStore().get(1).getIsUserQueued()).isTrue();
        assertThat(playQueue.getListStore().get(2).songInfo()).isEqualTo(user2);
        assertThat(playQueue.getListStore().get(2).getIsUserQueued()).isTrue();
        assertThat(playQueue.getListStore().get(3).songInfo()).isEqualTo(lastSong);
        assertThat(playQueue.getListStore().get(3).getIsUserQueued()).isTrue();
        assertThat(playQueue.getListStore().get(4).songInfo()).isEqualTo(songs.get(1));
        assertThat(playQueue.getListStore().get(4).getIsUserQueued()).isFalse();
    }

    @Test
    public void testRemoveCurrentThenPlayNextPlaysCorrectSong() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 1).join();
        playRecorder.songs.clear();

        // Remove the currently playing song (index 1)
        playQueue.removeAt(1);

        // Queue is now [song0, song2, song3], position adjusted to 0
        assertThat(playQueue.getListStore().size()).isEqualTo(3);
        assertThat(playQueue.getListStore().get(0).songInfo()).isEqualTo(songs.get(0));
        assertThat(playQueue.getListStore().get(1).songInfo()).isEqualTo(songs.get(2));

        // "Next" should play song2 (the song that was after the removed one)
        playQueue.attemptPlayNext();
        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).containsExactly(songs.get(2));
    }

    @Test
    public void testRemoveCurrentThenEndOfStreamPlaysCorrectSong() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 1).join();
        playRecorder.songs.clear();

        playQueue.removeAt(1);

        // Queue: [song0, song2], position adjusted to 0
        // End of stream should advance to the next song (song2)
        playQueue.onState(new PlayerState(PlayerStates.END_OF_STREAM, 1.0, false, Optional.empty(), Optional.empty()));
        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).containsExactly(songs.get(2));
    }

    @Test
    public void testRemoveCurrentAtStartThenPlayNext() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 0).join();
        playRecorder.songs.clear();

        playQueue.removeAt(0);

        // Queue: [song1, song2], position is empty
        assertThat(playQueue.getListStore().size()).isEqualTo(2);
        assertThat(playQueue.getState().position()).isEmpty();

        // "Next" should play song1 (now at index 0)
        playQueue.attemptPlayNext();
        assertThat(playQueue.getState().position()).hasValue(0);
        assertThat(playRecorder.songs).containsExactly(songs.get(1));
    }

    @Test
    public void testRemoveCurrentAtEndThenPlayNextDoesNothing() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 1).join();
        playRecorder.songs.clear();

        playQueue.removeAt(1);

        // Queue: [song0], position adjusted to 0
        assertThat(playQueue.getListStore().size()).isEqualTo(1);

        // "Next" should do nothing — no songs after
        playQueue.attemptPlayNext();
        assertThat(playRecorder.songs).isEmpty();
    }

    private static class StubPlayer implements Player {
        PlayerState currentState = new PlayerState(PlayerStates.INIT, 1.0, false, Optional.empty(), Optional.empty());
        Duration seekedTo;

        @Override
        public PlayerState getState() {
            return currentState;
        }

        @Override
        public void seekTo(Duration position) {
            this.seekedTo = position;
        }

        @Override
        public void onStateChanged(PlaybinPlayer.OnStateChanged listener) {
            // NOP
        }

        @Override
        public void removeOnStateChanged(PlaybinPlayer.OnStateChanged listener) {
            // NOP
        }
    }

    private static class PlayQueueStateRecorder implements Consumer<PlayQueue.PlayQueueState> {
        final List<PlayQueue.PlayQueueState> states = new ArrayList<>();
        @Override
        public void accept(PlayQueue.PlayQueueState state) {
            states.add(state);
        }
    }

    private static class SongInfoRecorder implements Consumer<GSongInfo> {
        final List<SongInfo> songs = new ArrayList<>();
        @Override
        public void accept(GSongInfo songInfo) {
            songs.add(songInfo.getSongInfo());
        }
    }
}