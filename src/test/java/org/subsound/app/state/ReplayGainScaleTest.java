package org.subsound.app.state;

import org.junit.Test;
import org.subsound.integration.ServerClient.ReplayGain;
import org.subsound.integration.ServerClient.ReplayGainConfig;
import org.subsound.integration.ServerClient.ReplayGainConfig.Mode;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class ReplayGainScaleTest {

    private static SongInfo song(ReplayGain replayGain) {
        return ServerClientSongInfoBuilder.builder()
                .id("song-1")
                .title("Song One")
                .replayGain(Optional.ofNullable(replayGain))
                .build();
    }

    @Test
    public void disabledReturnsUnity() {
        var config = new ReplayGainConfig(false, Mode.TRACK, 6.0, -3.0);
        // Even with metadata and a pre-amp, a disabled config must not touch the volume.
        var s = song(new ReplayGain(-6.0, -4.0, 0.5, 0.5));
        assertThat(AppManager.computeReplayGainScale(s, config)).isEqualTo(1.0);
    }

    @Test
    public void trackModeUsesTrackGain() {
        var config = new ReplayGainConfig(true, Mode.TRACK, 0.0, 0.0);
        // -6 dB -> 10^(-6/20) ≈ 0.5012; peak 0.5 -> 0.25 boosted, no clip.
        var s = song(new ReplayGain(-6.0, -12.0, 0.5, 0.5));
        assertThat(AppManager.computeReplayGainScale(s, config)).isCloseTo(0.50119, within(1e-4));
    }

    @Test
    public void albumModeUsesAlbumGain() {
        var config = new ReplayGainConfig(true, Mode.ALBUM, 0.0, 0.0);
        // album gain -12 dB -> 10^(-12/20) ≈ 0.2512.
        var s = song(new ReplayGain(-6.0, -12.0, 0.5, 0.5));
        assertThat(AppManager.computeReplayGainScale(s, config)).isCloseTo(0.25119, within(1e-4));
    }

    @Test
    public void missingMetadataUsesFallbackGain() {
        var config = new ReplayGainConfig(true, Mode.TRACK, 0.0, -3.0);
        // no ReplayGain -> fallback -3 dB -> 10^(-3/20) ≈ 0.7079. No peak known -> no clamp.
        assertThat(AppManager.computeReplayGainScale(song(null), config)).isCloseTo(0.70795, within(1e-4));
    }

    @Test
    public void preAmpIsAdded() {
        var config = new ReplayGainConfig(true, Mode.TRACK, 6.0, 0.0);
        // 0 dB gain + 6 dB pre-amp -> 10^(6/20) ≈ 1.9953; peak 0.1 -> 0.199, no clip.
        var s = song(new ReplayGain(0.0, 0.0, 0.1, 0.1));
        assertThat(AppManager.computeReplayGainScale(s, config)).isCloseTo(1.99526, within(1e-4));
    }

    @Test
    public void peakClampPreventsClipping() {
        var config = new ReplayGainConfig(true, Mode.TRACK, 0.0, 0.0);
        // +12 dB -> 10^(12/20) ≈ 3.981; peak 0.5 -> would reach ~1.99 (clip). Clamp to 1/0.5 = 2.0.
        var s = song(new ReplayGain(12.0, 12.0, 0.5, 0.5));
        assertThat(AppManager.computeReplayGainScale(s, config)).isCloseTo(2.0, within(1e-9));
    }
}
