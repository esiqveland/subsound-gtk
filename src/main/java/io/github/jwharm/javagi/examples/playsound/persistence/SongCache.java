package io.github.jwharm.javagi.examples.playsound.persistence;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.utils.javahttp.LoggingHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.sha256;

public class SongCache {
    private static final Logger log = LoggerFactory.getLogger(SongCache.class);

    private final Path root;
    private final HttpClient client = new LoggingHttpClient(HttpClient.newBuilder().build());

    public SongCache(Path cacheDir) {
        var f = cacheDir.toFile();
        if (!f.exists()) {
            if (!f.mkdirs()) {
                throw new RuntimeException("unable to create cache dir=" + cacheDir);
            }
        }
        if (!f.isDirectory()) {
            throw new RuntimeException("error: given cache dir=" + cacheDir + " is not a directory");
        }
        this.root = cacheDir;
    }

    public record CacheSong(
            String serverId,
            String songId,
            URI uri,
            String suffix,
            long expectedSize
    ) {
    }

    public enum CacheResult {
        HIT, MISS,
    }

    public record GetSongResult(
            CacheResult result,
            URI uri
    ) {
    }

    public GetSongResult getSong(CacheSong songData) {
        var cachePath = this.cachePath(songData);
        var cacheFile = cachePath.cachePath.toAbsolutePath().toFile();
        if (cacheFile.isDirectory()) {
            cacheFile.delete();
        }
        if (cacheFile.length() == 0) {
            cacheFile.delete();
        }
        if (cacheFile.exists()) {
            // serve this !
            return new GetSongResult(CacheResult.HIT, cacheFile.toURI());
        }


        cachePath.tmpFilePath.getParent().toFile().mkdirs();
        var cacheTmpFile = cachePath.tmpFilePath.toAbsolutePath().toFile();
        if (cacheTmpFile.exists()) {
            cacheTmpFile.delete();
        }
        try {
            cacheTmpFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            long downloadSize = downloadTo(songData.uri, new FileOutputStream(cacheTmpFile));
            if (downloadSize != songData.expectedSize) {
                log.info("download size={} does not equal expectedSize={}", downloadSize, songData.expectedSize);
            }
            // rename tmp file to target file.
            cacheTmpFile.renameTo(cacheFile);
            return new GetSongResult(CacheResult.MISS, cacheFile.toURI());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private long downloadTo(URI uri, OutputStream out) {
        var req = HttpRequest.newBuilder().uri(uri).GET().build();
        try {
            HttpResponse<InputStream> res = this.client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                throw new RuntimeException("error: statusCode=%d uri=%s".formatted(res.statusCode(), uri.toString()));
            }
            String contentType = res.headers().firstValue("content-type").orElse("");
            if (contentType.isEmpty() || contentType.contains("xml") || contentType.contains("html") || contentType.contains("json")) {
                // response does not look like binary music data...
                throw new RuntimeException("error: statusCode=%d uri=%s contentType=%s".formatted(res.statusCode(), uri.toString(), contentType));
            }
            return Utils.copyLarge(res.body(), out);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    record CacheKey(String part1, String part2, String part3) {
    }

    static CacheKey toCacheKey(String songId) {
        String shasum = sha256(songId);
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

    private CachehPath cachePath(CacheSong songData) {
        var songId = songData.songId;
        CacheKey key = toCacheKey(songId);
        var fileName = "%s.%s".formatted(songId, songData.suffix);
        var cachePath = joinPath(root, songData.serverId, "songs", key.part1, key.part2, key.part3, fileName);
        var cachePathTmp = joinPath(cachePath.getParent(), fileName + ".tmp");
        return new CachehPath(cachePath, cachePathTmp);
    }

    public static Path joinPath(Path base, String... elements) {
        return Arrays.stream(elements).map(Path::of).reduce(base, Path::resolve);
    }
}