package com.github.subsound.persistence;

import com.github.subsound.integration.ServerClient.TranscodeInfo;
import com.github.subsound.utils.javahttp.LoggingHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;

import static com.github.subsound.utils.Utils.sha256;

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
            TranscodeInfo transcodeInfo,
            // originalFileSuffix is the original file format originalFileSuffix
            String originalFileSuffix,
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
        var streamUri = songData.transcodeInfo.streamUri();
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
            long estimatedContentSize = songData.transcodeInfo.estimateContentSize();
            long downloadSize = downloadTo(
                    songData.transcodeInfo.streamUri(),
                    new FileOutputStream(cacheTmpFile),
                    songData.originalSize,
                    estimatedContentSize,
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

    private long downloadTo(
            URI uri,
            OutputStream output,
            long originalSize,
            long estimatedContentSize,
            DownloadProgressHandler ph
    ) {
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

            long estimatedSizeBytes = estimatedContentSize;
//            long estimatedSizeBytes = res.headers()
// X-Content-Duration is set by navidrome on HEAD and GET requests to the /rest/stream endpoint:
//                    .firstValue("X-Content-Duration")
//                    .map(Double::parseDouble)
//                    .map(durationSeconds -> estimateContentLength(durationSeconds, bitRate))
//                    .orElse(originalSize);
            long expectedSize = res.headers().firstValueAsLong("Content-Length").orElse(estimatedSizeBytes);

            log.info("estimateContentLength: originalSize={} expectedSize={}", originalSize, expectedSize);

            try (var stream = res.body()) {
                byte[] buffer = new byte[8192];
                long sum = 0L;
                int n;
                while(-1 != (n = stream.read(buffer))) {
                    output.write(buffer, 0, n);
                    sum += n;
                    if (sum > expectedSize) {
                        expectedSize = sum;
                    }
                    ph.progress(expectedSize, sum);
                }

                // When transcoding, Content-Length is only an estimate.
                // Make sure we finish the progressbar by flushing with the final size before exiting:
                var finalSize = Math.max(expectedSize, sum);
                log.info("sending final flush: originalSize={} expectedSize={} estimatedSizeBytes={} finalSize={}", originalSize, expectedSize, estimatedSizeBytes, finalSize);
                ph.progress(finalSize, finalSize);
                return sum;
            }
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
        return new CacheKey(
                shasum.substring(0, 2),
                shasum.substring(2, 4),
                shasum.substring(4, 6)
        );
    }

    record CachehPath(
            Path cachePath,
            Path tmpFilePath
    ) {
    }

    private CachehPath cachePath(CacheSong songData) {
        var songId = songData.songId;
        var key = toCacheKey(songId);
        var fileName = "%s.%s".formatted(songId, songData.transcodeInfo.streamFormat());
        var cachePath = joinPath(root, songData.serverId, "songs", key.part1, key.part2, key.part3, fileName);
        var cachePathTmp = joinPath(cachePath.getParent(), fileName + ".tmp");
        return new CachehPath(cachePath, cachePathTmp);
    }

    public static Path joinPath(Path base, String... elements) {
        return Arrays.stream(elements).map(Path::of).reduce(base, Path::resolve);
    }
}
