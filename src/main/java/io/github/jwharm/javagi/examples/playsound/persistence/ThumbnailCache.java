package io.github.jwharm.javagi.examples.playsound.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.jwharm.javagi.base.GErrorException;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.utils.javahttp.LoggingHttpClient;
import org.gnome.gdkpixbuf.Pixbuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static io.github.jwharm.javagi.examples.playsound.persistence.SongCache.joinPath;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.sha256;

/**
 * ThumbnailCache does 3 things:
 *  1. make sure to get requested images from server onto local disk
 *  2. make sure to cache remote photos on disk and organize this. we store originals on disk.
 *  3. keep often requested images as GDK Pixbufs in a in-memory-cache
 */
public class ThumbnailCache {
    private static final Logger log = LoggerFactory.getLogger(ThumbnailCache.class);

    private final Path root;
    private final HttpClient client = new LoggingHttpClient(HttpClient.newBuilder().build());
    // semaphore limits concurrency a little, we could send 1000s request concurrently on page load of a e.g. starred page:
    private final Semaphore semaphore = new Semaphore(2);
    // A separate semaphore for querying the cache, so downloading new content does not block us from loading content we already have stored
    private final Semaphore semaphorePixbuf = new Semaphore(2);
    private final Cache<PixbufCacheKey, Pixbuf> pixbufCache = Caffeine.newBuilder().maximumSize(500).build();

    record PixbufCacheKey(
            CoverArt coverArt,
            String id,
            int size
    ) {
    }

    public ThumbnailCache(Path root) {
        this.root = root;
    }

    public CompletableFuture<Pixbuf> loadPixbuf(CoverArt coverArt, int size) {
        return Utils.doAsync(() -> {
            try {
                semaphorePixbuf.acquire(1);
                var key = new PixbufCacheKey(coverArt, coverArt.coverArtId(), size);

                var pixbuf = pixbufCache.get(key, k -> {
                    log.debug("ThumbCache: cache miss: {} size={}", k.coverArt.coverArtId(), k.size);
                    ThumbLoaded loaded = loadThumbAsync(k.coverArt).join();
                    String path = loaded.path().cachePath().toAbsolutePath().toString();
                    try {
                        var p = Pixbuf.fromFileAtSize(path, k.size, k.size);
                        p.readPixelBytes()
                        return p;
                    } catch (GErrorException e) {
                        throw new RuntimeException("unable to create pixbuf from path='%s'".formatted(path), e);
                    }
                });
                return pixbuf;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                semaphorePixbuf.release(1);
            }
        });
    }

    record ThumbLoaded(
            CachehPath path,
            byte[] blob
    ) {
    }

    // TODO: since we are using Pixbuf.fromFileAtSize anyway, we dont need the blob in memory any more.
    private CompletableFuture<ThumbLoaded> loadThumbAsync(CoverArt coverArt) {
        var cachehPath = toCachePath(coverArt);
        var cacheAbsPath = cachehPath.cachePath().toAbsolutePath();
        var cacheFile = cacheAbsPath.toFile();
        return Utils.doAsync(() -> {
            try {
                semaphore.acquire(1);
                var file = cacheAbsPath.toFile();
                if (file.exists() && file.length() > 0) {
                    return new ThumbLoaded(cachehPath, Files.readAllBytes(cacheAbsPath));
                }
                var link = coverArt.coverArtLink();
                var req = HttpRequest.newBuilder().GET().uri(link).build();
                var bodyHandler = HttpResponse.BodyHandlers.ofByteArray();
                //CompletableFuture<HttpResponse<Void>> httpResponseCompletableFuture = this.client.sendAsync(req, bodyHandler);

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
                    return new ThumbLoaded(cachehPath, body);
                }
            } catch (IOException e) {
                throw new RuntimeException("error loading: " + coverArt.coverArtLink().toString(), e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                semaphore.release(1);
            }
        }).thenApply(thumbsLoaded -> {
            try {
                cacheAbsPath.getParent().toFile().mkdirs();
                cacheFile.delete();

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

                tmpFile.renameTo(cacheFile);

                return thumbsLoaded;
            } catch (IOException ex) {
                throw new RuntimeException(ex);
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
