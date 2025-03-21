package com.github.subsound.configuration;

import com.github.subsound.integration.ServerClient.ServerType;
import org.junit.Test;

import static com.github.subsound.configuration.Config.parseCfg;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigTest {

    @Test
    public void testConfigV1Format() {
        assertThat(parseCfg(CONFIG_V1_JSON)).isPresent().hasValueSatisfying(dto -> {
            assertThat(dto.server.url()).isEqualTo("https://play.example.org");
            assertThat(dto.server.username()).isEqualTo("username");
            assertThat(dto.server.password()).isEqualTo("password");
            assertThat(dto.server.type()).isEqualTo(ServerType.SUBSONIC);
        });
    }

    private static final String CONFIG_V1_JSON = """
            {
              "server": {
                "type": "SUBSONIC",
                "url": "https://play.example.org",
                "username": "username",
                "password": "password"
              }
            }""";
}