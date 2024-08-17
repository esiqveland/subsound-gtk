package io.github.jwharm.javagi.examples.playsound.utils;

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.formatDurationShort;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.formatDurationShortest;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.withinEpsilon;
import static org.assertj.core.api.Assertions.assertThat;

public class UtilsTest {

    @Test
    public void futureHandling() throws InterruptedException {
        var latch = new CountDownLatch(2);
        var future1 = CompletableFuture.completedFuture("ok");
        var w1 = future1.whenCompleteAsync((val, error) -> {
            latch.countDown();
        });
        var w2 = w1.whenCompleteAsync((val, error) -> {
            latch.countDown();
        });
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

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

    @Test
    public void testformatDurationShortest() {
        assertThat(formatDurationShortest(Duration.ofSeconds(0))).isEqualTo("0:00");
        assertThat(formatDurationShortest(Duration.ofSeconds(5))).isEqualTo("0:05");
        assertThat(formatDurationShortest(Duration.ofSeconds(59))).isEqualTo("0:59");
        assertThat(formatDurationShortest(Duration.ofSeconds(61))).isEqualTo("1:01");
        assertThat(formatDurationShortest(Duration.ofSeconds(121))).isEqualTo("2:01");
        assertThat(formatDurationShortest(Duration.ofSeconds(3601))).isEqualTo("1:00:01");
        assertThat(formatDurationShortest(Duration.ofSeconds(3601))).isEqualTo("1:00:01");
        assertThat(formatDurationShortest(Duration.ofSeconds(3661))).isEqualTo("1:01:01");
        assertThat(formatDurationShortest(Duration.ofSeconds(3600))).isEqualTo("1:00:00");
    }

    @Test
    public void testWithinEpsilon() {
        assertThat(withinEpsilon(0.01, 0.01, 0.01)).isEqualTo(true);
        assertThat(withinEpsilon(0.02, 0.01, 0.01)).isEqualTo(false);
        assertThat(withinEpsilon(0.1, 1.0, 0.01)).isEqualTo(false);
        assertThat(withinEpsilon(1.0, 0.1, 0.01)).isEqualTo(false);
    }
}