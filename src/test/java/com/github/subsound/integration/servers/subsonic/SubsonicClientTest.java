package com.github.subsound.integration.servers.subsonic;

import net.beardbot.subsonic.client.base.LocalDateTimeAdapter;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

public class SubsonicClientTest {

    @Test
    public void testNavidromeStarredTimestamp() {
        {
            // What is actually sent:
            String value = "2024-07-18T22:20:25.220976486Z";

            // test if a the jdk Instant parser could have handled it:
            Instant parse = Instant.parse(value);
//            assertThat(instant.getEpochSecond()).isEqualTo(parse.getEpochSecond());

//            var adapter = new LocalDateTimeAdapter();
//            LocalDateTime unmarshal = adapter.unmarshal(value);
//            var instant = unmarshal.toInstant(ZoneOffset.UTC);
//            assertThat(instant.getEpochSecond()).isEqualTo(123L);
        }
        {
            // need to use max 3 decimals in seconds portion to make it work:
            String value = "2024-07-18T22:20:25.220Z";
            var adapter = new LocalDateTimeAdapter();
            LocalDateTime unmarshal = adapter.unmarshal(value);
            var instant = unmarshal.toInstant(ZoneOffset.UTC);
            assertThat(instant.getEpochSecond()).isEqualTo(1721341225L);

            // test if a the jdk Instant parser could have handled it:
            Instant parse = Instant.parse(value);
            assertThat(instant.getEpochSecond()).isEqualTo(parse.getEpochSecond());
        }
    }

}