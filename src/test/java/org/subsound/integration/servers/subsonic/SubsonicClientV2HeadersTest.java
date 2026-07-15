package org.subsound.integration.servers.subsonic;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.subsound.app.state.AppManager;
import org.subsound.configuration.Config.ServerConfig;
import org.subsound.integration.ServerClient.HttpHeader;
import org.subsound.integration.ServerClient.ServerType;
import org.subsound.integration.ServerClient.TranscodeFormat;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that user-configured custom headers are attached to every outgoing request
 * by wiring a real {@link SubsonicClientV2} against a MockWebServer.
 */
public class SubsonicClientV2HeadersTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    private ServerConfig configWithHeaders(List<HttpHeader> headers) {
        return new ServerConfig(
                folder.getRoot().toPath(),
                AppManager.SERVER_ID,
                ServerType.SUBSONIC,
                server.url("/").toString().replaceAll("/$", ""),
                "user",
                "pass",
                TranscodeFormat.source,
                null,
                false,
                headers
        );
    }

    @Test
    public void customHeadersAttachedToAllRequests() throws InterruptedException {
        // First request the client makes is getOpenSubsonicExtensions; 404 => no formPost,
        // so the subsequent ping is a plain GET. Both flow through the shared httpClient.
        server.enqueue(new MockResponse.Builder().code(404).body("{}").build());
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}")
                .build());

        var client = SubsonicClientV2.create(configWithHeaders(List.of(
                new HttpHeader("CF-Access-Client-Id", "abc123"),
                new HttpHeader("CF-Access-Client-Secret", "s3cr3t")
        )));

        assertThat(client.testConnection()).isTrue();

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        for (RecordedRequest req : List.of(first, second)) {
            assertThat(req.getHeaders().get("CF-Access-Client-Id")).isEqualTo("abc123");
            assertThat(req.getHeaders().get("CF-Access-Client-Secret")).isEqualTo("s3cr3t");
            // interceptor must not clobber the existing User-Agent header
            assertThat(req.getHeaders().get("User-Agent")).contains("Subsound");
        }
    }

    @Test
    public void noCustomHeadersLeavesRequestsUnchanged() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(404).body("{}").build());
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}")
                .build());

        var client = SubsonicClientV2.create(configWithHeaders(List.of()));

        assertThat(client.testConnection()).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeaders().get("CF-Access-Client-Id")).isNull();
        assertThat(req.getHeaders().get("User-Agent")).contains("Subsound");
    }
}
