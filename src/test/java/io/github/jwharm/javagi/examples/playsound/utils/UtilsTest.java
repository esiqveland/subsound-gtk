package io.github.jwharm.javagi.examples.playsound.utils;

import org.junit.Test;

import java.time.Duration;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.formatDurationShort;
import static org.assertj.core.api.Assertions.assertThat;

public class UtilsTest {

    @Test
    public void testformatDurationShort() {
        assertThat(formatDurationShort(Duration.ofSeconds(0))).isEqualTo("00:00");
        assertThat(formatDurationShort(Duration.ofSeconds(5))).isEqualTo("00:05");
        assertThat(formatDurationShort(Duration.ofSeconds(59))).isEqualTo("00:59");
        assertThat(formatDurationShort(Duration.ofSeconds(61))).isEqualTo("01:01");
        assertThat(formatDurationShort(Duration.ofSeconds(121))).isEqualTo("02:01");
        assertThat(formatDurationShort(Duration.ofSeconds(3601))).isEqualTo("01:00:01");
        assertThat(formatDurationShort(Duration.ofSeconds(3601))).isEqualTo("01:00:01");
        assertThat(formatDurationShort(Duration.ofSeconds(3600))).isEqualTo("01:00:00");
    }

}