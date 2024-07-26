package io.github.jwharm.javagi.examples.playsound.persistence;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.utils.javahttp.LoggingHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static io.github.jwharm.javagi.examples.playsound.persistence.SongCache.joinPath;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.sha256;

public class ThumbLoader {
    private final Path root;
    private final HttpClient client = new LoggingHttpClient(HttpClient.newBuilder().build());

    public ThumbLoader(Path root) {
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
        filePath.getParent().toFile().mkdirs();

        var tmpFilePath = cachehPath.tmpFilePath().toAbsolutePath();
        var tmpFile = tmpFilePath.toFile();
        if (tmpFile.exists()) {
            tmpFile.delete();
        }
        try {
            tmpFile.createNewFile();
            tmpFile.deleteOnExit();
            var out = new FileOutputStream(tmpFile);
            loadThumb(coverArt.coverArtLink(), bytes -> {
                try {
                    out.write(bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            out.close();
            tmpFile.renameTo(file);

            serveFile(file, target);
        } catch (IOException e) {
            throw new RuntimeException(e);
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

    public void loadThumb(URI link, Consumer<byte[]> target) {
        var req = HttpRequest.newBuilder().GET().uri(link).build();
        var bodyHandler = HttpResponse.BodyHandlers.ofByteArrayConsumer(bytes -> {
            bytes.ifPresent(target);
        });
        //CompletableFuture<HttpResponse<Void>> httpResponseCompletableFuture = this.client.sendAsync(req, bodyHandler);
        try {
            HttpResponse<Void> res = this.client.send(req, bodyHandler);
            if (res.statusCode() != 200) {
                throw new RuntimeException("error loading: status=" + res.statusCode() + " link=" + link.toString());
            }
        } catch (IOException e) {
            throw new RuntimeException("error loading: " + link.toString(), e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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

}
