package com.github.subsound.persistence;

import com.github.subsound.integration.ServerClient.TranscodedStream;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.github.subsound.ui.views.TestPlayerPage.loadSamples;

public class MockMusicServer {
    private static final List<SampleSong> defaultSamples = sampleSongs();

    private final int port;
    private final Map<String, SampleSong> songIdMapping;
    private final HttpServer server;

    public MockMusicServer() {
        this(defaultSamples);
    }
    public MockMusicServer(List<SampleSong> songIdMapping) {
        this.songIdMapping = songIdMapping.stream().collect(Collectors.toMap(
                sampleSong -> sampleSong.songId,
                val -> val
        ));
        try {
            // port 0 = random free port
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.start();
            this.port = server.getAddress().getPort();
            System.out.println("MockMusicServer: started on port " + port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.server.createContext("/rest/stream", (exchange) -> {
            var uri = exchange.getRequestURI();
            var url = HttpUrl.get(uri);
            var songId = url.queryParameter("id");
            if (songId == null) {
                respond(exchange, 400, "text/plain", "Missing songId parameter");
                return;
            }
            var song = this.songIdMapping.get(songId);
            if (song == null) {
                respond(exchange, 400, "text/plain", "Missing songId=%s".formatted(songId));
                return;
            }
            respond(exchange, 200, "", song.data);
        });
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        respond(exchange, status, contentType, bytes);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        //  + "; charset=utf-8"
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public Collection<SampleSong> getSamples() {
        return this.songIdMapping.values();
    }

    public record SampleSong(
            String songId,
            byte[] data
    ){}

    public TranscodedStream getTranscodeStream(String songId) {
        var song = songIdMapping.get(songId);
        var baseUrl = baseUrl().newBuilder("/rest/stream?id=" + song.songId()).build();
        return new TranscodedStream(songId, baseUrl.uri());
    }

    public HttpUrl baseUrl() {
        return HttpUrl.get("http://localhost:%d".formatted(port));
    }

    public void stop() {
        server.stop(0);
    }


    private static List<SampleSong> sampleSongs() {
        var knownSongs = loadSamples("src/main/resources/samples");
        return knownSongs.stream().map(known -> {
            byte[] bytes = readAllBytes(known.absolutePath());
            return new SampleSong(
                    known.id(),
                    bytes
            );
        }).toList();
    }

    public static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file path=" + path, e);
        }
    }
}
