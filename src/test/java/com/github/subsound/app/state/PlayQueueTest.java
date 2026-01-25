package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.SongInfoFactory;
import com.github.subsound.sound.PlaybinPlayer.PlayerState;
import com.github.subsound.sound.PlaybinPlayer.PlayerStates;
import com.github.subsound.sound.PlaybinPlayer.Source;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.sound.Player;
import org.junit.Before;
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
        player = new StubPlayer();
        stateChangedRecorder = new PlayQueueStateRecorder();
        playRecorder = new SongInfoRecorder();
        
        playQueue = new PlayQueue(player, stateChangedRecorder, playRecorder);
    }

    @Test
    public void testEnqueue() {
        SongInfo song = SongInfoFactory.createRandomSongInfo();
        playQueue.enqueue(song);

        PlayQueue.PlayQueueState state = playQueue.getState();
        assertThat(state.playQueue()).containsExactly(song);
        assertThat(state.position()).isEmpty();
        assertThat(stateChangedRecorder.states).isNotEmpty();
    }

    @Test
    public void testReplaceQueue() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs, 1);

        PlayQueue.PlayQueueState state = playQueue.getState();
        assertThat(state.playQueue()).containsExactlyElementsOf(songs);
        assertThat(state.position()).hasValue(1);
        assertThat(stateChangedRecorder.states).isNotEmpty();
    }

    @Test
    public void testPlayPosition() {
        List<SongInfo> songs = List.of(
                SongInfoFactory.createRandomSongInfo(),
                SongInfoFactory.createRandomSongInfo()
        );
        playQueue.replaceQueue(songs);
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
        playQueue.replaceQueue(songs, 0);
        playQueue.attemptPlayNext();

        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).contains(songs.get(1));
    }

    @Test
    public void testAttemptPlayNextAtEnd() {
        List<SongInfo> songs = List.of(SongInfoFactory.createRandomSongInfo());
        playQueue.replaceQueue(songs, 0);
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
        playQueue.replaceQueue(songs, 0);

        player.currentState = new PlayerState(
                PlayerStates.PLAYING,
                1.0,
                false,
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
        playQueue.replaceQueue(songs, 1);

        player.currentState = new PlayerState(
                PlayerStates.PLAYING,
                1.0,
                false,
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
        playQueue.replaceQueue(songs, 0);

        PlayerState endOfStreamState = new PlayerState(
                PlayerStates.END_OF_STREAM,
                1.0,
                false,
                Optional.empty()
        );

        playQueue.onState(endOfStreamState);

        assertThat(playQueue.getState().position()).hasValue(1);
        assertThat(playRecorder.songs).contains(songs.get(1));
    }

    private static class StubPlayer implements Player {
        PlayerState currentState = new PlayerState(PlayerStates.INIT, 1.0, false, Optional.empty());
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

    private static class SongInfoRecorder implements Consumer<SongInfo> {
        final List<SongInfo> songs = new ArrayList<>();
        @Override
        public void accept(SongInfo songInfo) {
            songs.add(songInfo);
        }
    }
}