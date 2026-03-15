package org.subsound.integration.platform.mpriscontroller;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.persistence.ThumbnailCache;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Serves cached album artwork over a local HTTP server so that MPRIS clients can load it.
 *
 * <p>MPRIS metadata includes an {@code mpris:artUrl} field. When running inside a Flatpak
 * sandbox, {@code file://} URIs point to paths inside the app's sandbox that other processes
 * (e.g. GNOME Shell, Plasma media controls, playerctl) cannot read. Serving the images over
 * {@code http://127.0.0.1} makes them accessible to all local processes regardless of sandbox
 * boundaries.
 */
public class ArtworkHttpServer {
    private static final Logger log = LoggerFactory.getLogger(ArtworkHttpServer.class);

    private final ThumbnailCache thumbnailCache;
    private final HttpServer server;
    private final int port;

    public ArtworkHttpServer(ThumbnailCache thumbnailCache) {
        this.thumbnailCache = thumbnailCache;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.server.createContext("/art", exchange -> {
            // path: /art/{serverId}/{coverArtId}
            var path = exchange.getRequestURI().getPath();
            var parts = path.split("/", 4); // ["", "art", serverId, coverArtId]
            if (parts.length < 4) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            var serverId = parts[2];
            var coverArtId = parts[3];
            var cached = thumbnailCache.getCachedPath(serverId, coverArtId);
            if (cached.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            var cachePath = cached.get();
            var contentType = Optional.ofNullable(Files.probeContentType(cachePath)).orElse("image/jpeg");
            var bytes = Files.readAllBytes(cachePath);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        this.server.setExecutor(null); // default executor
        this.server.start();
        this.port = this.server.getAddress().getPort();
        log.info("ArtworkHttpServer started on port {}", port);
    }

    public void stop() {
        server.stop(0);
    }

    public Optional<URI> getArtUrl(CoverArt coverArt) {
        var url = "http://127.0.0.1:%d/art/%s/%s".formatted(
                port,
                coverArt.serverId(),
                coverArt.coverArtId()
        );
        return Optional.of(URI.create(url));
    }
}
