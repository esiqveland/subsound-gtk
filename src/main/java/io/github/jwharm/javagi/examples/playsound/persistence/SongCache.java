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
            URI streamUri,
            // originalFileSuffix is the original file format originalFileSuffix
            String originalFileSuffix,
            // the stream originalFileSuffix is the format we will receive when loading streamUri
            String streamSuffix,
            // original size
            long originalSize,
            DownloadProgressHandler progressHandler
    ) {
    }

    public enum CacheResult {
        HIT, MISS,
    }

    public record LoadSongResult(
            CacheResult result,
            URI uri
    ) {
    }

    public LoadSongResult getSong(CacheSong songData) {
        var streamUri = songData.streamUri();
        switch (streamUri.getScheme()) {
            case "http", "https":
                break;
            case "file":
                return new LoadSongResult(CacheResult.HIT, streamUri);
        }

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
            return new LoadSongResult(CacheResult.HIT, cacheFile.toURI());
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
            long downloadSize = downloadTo(
                    songData.streamUri(),
                    new FileOutputStream(cacheTmpFile),
                    songData.originalSize,
                    songData.progressHandler
            );
            if (downloadSize != songData.originalSize) {
                //log.info("download size={} does not equal originalSize={}", downloadSize, songData.originalSize);
            }
            // rename tmp file to target file.
            cacheTmpFile.renameTo(cacheFile);
            return new LoadSongResult(CacheResult.MISS, cacheFile.toURI());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public interface DownloadProgressHandler {
        void progress(long total, long count);
    }
    private long downloadTo(URI uri, OutputStream output, long originalSize, DownloadProgressHandler ph) {
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
            long expectedSize = res.headers().firstValueAsLong("Content-Length").orElse(originalSize);

            var stream = res.body();
            byte[] buffer = new byte[8192];
            long count = 0L;
            int n;
            while(-1 != (n = stream.read(buffer))) {
                output.write(buffer, 0, n);
                count += n;
                if (count > expectedSize) {
                    expectedSize = count;
                }
                ph.progress(expectedSize, count);
            }

            return count;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    record CacheKey(String part1, String part2, String part3) {
    }

    static CacheKey toCacheKey(String songId) {
        // I mean, ideally we would use the checksum of the data, but we dont have this until after we download,
        // and this is even more weird for live transcoded streams.
        // So instead we take a hash of the songId as this will probably also give a uniform distribution into our buckets:
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
        var key = toCacheKey(songId);
        var fileName = "%s.%s".formatted(songId, songData.streamSuffix);
        var cachePath = joinPath(root, songData.serverId, "songs", key.part1, key.part2, key.part3, fileName);
        var cachePathTmp = joinPath(cachePath.getParent(), fileName + ".tmp");
        return new CachehPath(cachePath, cachePathTmp);
    }

    public static Path joinPath(Path base, String... elements) {
        return Arrays.stream(elements).map(Path::of).reduce(base, Path::resolve);
    }
}
