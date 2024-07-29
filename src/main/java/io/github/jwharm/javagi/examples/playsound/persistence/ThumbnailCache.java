package io.github.jwharm.javagi.examples.playsound.persistence;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.utils.javahttp.LoggingHttpClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static io.github.jwharm.javagi.examples.playsound.persistence.SongCache.joinPath;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.sha256;

public class ThumbnailCache {
    private final Path root;
    private final HttpClient client = new LoggingHttpClient(HttpClient.newBuilder().build());
    // semaphore limits concurrency a little, we could send 1000s request concurrently on page load of a e.g. starred page:
    private final Semaphore semaphore = new Semaphore(2);
    private final ConcurrentHashMap<String, CompletableFuture<ThumbLoaded>> inFlight = new ConcurrentHashMap<>();

    public ThumbnailCache(Path root) {
        this.root = root;
    }

    public void load(CoverArt coverArt, Consumer<byte[]> target) {
        var cachehPath = toCachePath(coverArt);
        var filePath = cachehPath.cachePath().toAbsolutePath();
        var file = filePath.toFile();
        if (file.exists() && file.length() > 0) {
            serveFile(file, target);
            return;
        }

        var job = inFlight.computeIfAbsent(
                coverArt.coverArtId(),
                key -> loadThumbAsync(coverArt, cachehPath)
                        .thenApply(thumbsLoaded -> {
                            try {
                                filePath.getParent().toFile().mkdirs();
                                file.delete();

                                var tmpFilePath = cachehPath.tmpFilePath().toAbsolutePath();
                                var tmpFile = tmpFilePath.toFile();
                                if (tmpFile.exists()) {
                                    tmpFile.delete();
                                }
                                tmpFile.createNewFile();
                                tmpFile.deleteOnExit();
                                var out = new FileOutputStream(tmpFile);
                                out.write(thumbsLoaded.blob);
                                out.close();

                                tmpFile.renameTo(file);

                                return thumbsLoaded;
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        })
        );

        try {
            var thumbLoaded = job.join();
            serveFile(thumbLoaded.path().cachePath().toFile(), target);
        } finally {
            inFlight.remove(coverArt.coverArtId());
        }
    }

    private void serveFile(File file, Consumer<byte[]> target) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            target.accept(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    record ThumbLoaded(
            CachehPath path,
            byte[] blob
    ) {
    }

    private CompletableFuture<ThumbLoaded> loadThumbAsync(CoverArt coverArt, CachehPath cachehPath) {
        return Utils.doAsync(() -> {
            var link = coverArt.coverArtLink();
            var req = HttpRequest.newBuilder().GET().uri(link).build();
            var bodyHandler = HttpResponse.BodyHandlers.ofByteArray();
            //CompletableFuture<HttpResponse<Void>> httpResponseCompletableFuture = this.client.sendAsync(req, bodyHandler);
            try {
                semaphore.acquire(1);

                HttpResponse<byte[]> res = this.client.send(req, bodyHandler);
                if (res.statusCode() != 200) {
                    throw new RuntimeException("error loading: status=" + res.statusCode() + " link=" + link.toString());
                }
                String contentType = res.headers().firstValue("content-type").orElse("");
                if (contentType.isEmpty() || contentType.contains("xml") || contentType.contains("html") || contentType.contains("json")) {
                    // response does not look like binary music data...
                    throw new RuntimeException("error: statusCode=%d uri=%s contentType=%s".formatted(res.statusCode(), link.toString(), contentType));
                }

                byte[] body = res.body();
                if (contentType.contains("vp9") || contentType.contains("webp")) {
                    // convert to jpeg, Pixbuf does not support vp9 / webp:
                    return new ThumbLoaded(
                            cachehPath,
                            convertWebpToJpeg(body)
                    );
                } else {
                    return new ThumbLoaded(
                            cachehPath,
                            body
                    );
                }
            } catch (IOException e) {
                throw new RuntimeException("error loading: " + link.toString(), e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                semaphore.release(1);
            }
        });
    }

    record CacheKey(String part1, String part2, String part3) {
    }

    static CacheKey toCacheKey(String coverArtId) {
        // I mean, ideally we would use the checksum of the data, but we dont have this until after we download,
        // and this is even more weird for resized coverArt.
        // So instead we take a hash of the coverArtId as this will probably also give a uniform distribution into our buckets:
        String shasum = sha256(coverArtId);
        var cacheKey = new CacheKey(
                shasum.substring(0, 2),
                shasum.substring(2, 4),
                shasum.substring(4, 6)
        );
        return cacheKey;
    }

    record CachehPath(
            Path cachePath,
            Path tmpFilePath
    ) {
    }

    private CachehPath toCachePath(CoverArt coverArt) {
        var coverArtId = coverArt.coverArtId();
        var key = toCacheKey(coverArtId);
        var fileName = "%s.%s".formatted(coverArtId, "jpg");
        var cachePath = joinPath(root, coverArt.serverId(), "thumbs", key.part1, key.part2, key.part3, fileName);
        var cachePathTmp = joinPath(cachePath.getParent(), fileName + ".tmp");
        return new CachehPath(cachePath, cachePathTmp);
    }

    // Vp9/webp is not supported by gdk Pixbuf. We must convert it to png/jpg first:
    public static byte[] convertWebpToJpeg(byte[] blob) {
        try {
            var out = new ByteArrayOutputStream();
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(blob));
            if (ImageIO.write(read, "jpg", out)) {
                return out.toByteArray();
            } else {
                throw new IllegalStateException("no jpg writer?");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
