package org.subsound.integration.lyrics;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.configuration.constants.Constants;
import org.subsound.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.subsound.utils.LogUtils.loggingInterceptor;
import static org.subsound.utils.LogUtils.userAgentInterceptor;

/**
 * Client for lrclib.net — a free synced lyrics API.
 * Fetches time-synced LRC lyrics by exact match or search fallback,
 * falling back to plain unsynced lyrics when no synced version exists.
 */
public class LrclibClient {
    private static final String DEFAULT_BASE_URL = "https://lrclib.net";
    private static final int DURATION_TOLERANCE_SECONDS = 5;
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]");
    private static final Predicate<LrcLibResult> HAS_SYNCED = r -> r.syncedLyrics() != null && !r.syncedLyrics().isBlank();
    private static final Predicate<LrcLibResult> HAS_PLAIN = r -> r.plainLyrics() != null && !r.plainLyrics().isBlank();

    private final Logger log = LoggerFactory.getLogger(LrclibClient.class);
    private final String baseUrl;
    private final OkHttpClient httpClient;

    public record LyricLine(long timeMs, String text) {}

    record LrcLibResult(
            int id,
            String trackName,
            String artistName,
            String albumName,
            Double duration,
            String plainLyrics,
            String syncedLyrics
    ) {}

    private LrclibClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(userAgentInterceptor(Constants.USER_AGENT))
                .addInterceptor(loggingInterceptor(log))
                .build();
    }

    public static LrclibClient create() {
        return new LrclibClient(DEFAULT_BASE_URL);
    }

    static LrclibClient create(String baseUrl) {
        return new LrclibClient(baseUrl);
    }

    /**
     * Fetch lyrics for a song. Tries exact match first, then search fallback.
     * Synced lyrics always win; plain lyrics are only returned when no synced version exists.
     *
     * @return synced lyric lines sorted by time, plain text lines as fallback, or empty if not found
     */
    public Optional<LyricsResult> getLyrics(String title, String artist, @Nullable String album, @Nullable Integer durationSeconds) {
        if ((title == null || title.isBlank()) && (artist == null || artist.isBlank())) {
            return Optional.empty();
        }
        try {
            var exact = fetchExactMatch(title, artist, album, durationSeconds);
            var exactSynced = toSyncedResult(exact);
            if (exactSynced.isPresent()) {
                return exactSynced;
            }

            var searchResults = fetchSearch(title, artist);
            var searchSynced = toSyncedResult(pickBestMatch(searchResults, durationSeconds, HAS_SYNCED));
            if (searchSynced.isPresent()) {
                return searchSynced;
            }

            var exactPlain = toPlainResult(exact);
            if (exactPlain.isPresent()) {
                return exactPlain;
            }
            return toPlainResult(pickBestMatch(searchResults, durationSeconds, HAS_PLAIN));
        } catch (Exception e) {
            log.warn("Failed to fetch lyrics for '{}' by '{}': {}", title, artist, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<LyricsResult> toSyncedResult(@Nullable LrcLibResult result) {
        if (result == null || !HAS_SYNCED.test(result)) {
            return Optional.empty();
        }
        var lines = parseLrc(result.syncedLyrics());
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LyricsResult.SyncedLyrics(lines));
    }

    private static Optional<LyricsResult> toPlainResult(@Nullable LrcLibResult result) {
        if (result == null || !HAS_PLAIN.test(result)) {
            return Optional.empty();
        }
        var lines = parsePlain(result.plainLyrics());
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LyricsResult.PlainLyrics(lines));
    }

    private @Nullable LrcLibResult fetchExactMatch(String title, String artist, @Nullable String album, @Nullable Integer durationSeconds) {
        var params = new LinkedHashMap<String, String>();
        params.put("track_name", title != null ? title : "");
        params.put("artist_name", artist != null ? artist : "");
        if (album != null && !album.isBlank()) {
            params.put("album_name", album);
        }
        if (durationSeconds != null && durationSeconds > 0) {
            params.put("duration", String.valueOf(durationSeconds));
        }
        return fetchJsonOrNull(buildUrl("/api/get", params), LrcLibResult.class);
    }

    private LrcLibResult @Nullable [] fetchSearch(String title, String artist) {
        var query = ((title != null ? title : "") + " " + (artist != null ? artist : "")).trim();
        if (query.isBlank()) {
            return null;
        }
        return fetchJsonOrNull(buildUrl("/api/search", Map.of("q", query)), LrcLibResult[].class);
    }

    private static @Nullable LrcLibResult pickBestMatch(LrcLibResult @Nullable [] results, @Nullable Integer durationSeconds, Predicate<LrcLibResult> hasLyrics) {
        if (results == null || results.length == 0) {
            return null;
        }
        var candidates = new ArrayList<LrcLibResult>();
        for (var r : results) {
            if (hasLyrics.test(r)) {
                candidates.add(r);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (durationSeconds == null || durationSeconds <= 0) {
            return candidates.getFirst();
        }

        LrcLibResult best = null;
        double bestDiff = Double.MAX_VALUE;
        for (var r : candidates) {
            double diff = r.duration() != null ? Math.abs(r.duration() - durationSeconds) : 0;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = r;
            }
        }
        if (best == null || bestDiff > DURATION_TOLERANCE_SECONDS) {
            return null;
        }
        return best;
    }

    static List<LyricLine> parseLrc(String lrcText) {
        if (lrcText == null || lrcText.isBlank()) {
            return List.of();
        }
        var lines = new ArrayList<LyricLine>();
        for (String raw : lrcText.split("\n")) {
            var matcher = LRC_TIMESTAMP.matcher(raw);
            var timestamps = new ArrayList<Long>();
            int lastMatchEnd = 0;
            while (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                String msStr = matcher.group(3);
                int millis = msStr.length() == 2
                        ? Integer.parseInt(msStr) * 10
                        : Integer.parseInt(msStr);
                long timeMs = (minutes * 60L + seconds) * 1000L + millis;
                timestamps.add(timeMs);
                lastMatchEnd = matcher.end();
            }
            if (timestamps.isEmpty()) {
                continue;
            }
            String text = raw.substring(lastMatchEnd).trim();
            if (text.isEmpty()) {
                continue;
            }
            for (long timeMs : timestamps) {
                lines.add(new LyricLine(timeMs, text));
            }
        }
        lines.sort(Comparator.comparingLong(LyricLine::timeMs));
        return List.copyOf(lines);
    }

    static List<String> parsePlain(@Nullable String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return List.of();
        }
        return plainText.lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private HttpUrl buildUrl(String path, Map<String, String> params) {
        var builder = HttpUrl.parse(baseUrl + path).newBuilder();
        for (var entry : params.entrySet()) {
            builder.setQueryParameter(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private <T> @Nullable T fetchJsonOrNull(HttpUrl url, Class<T> responseClass) {
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            var body = response.body() != null ? response.body().string() : "";
            if (body.isBlank()) {
                return null;
            }
            return Utils.fromJson(body, responseClass);
        } catch (IOException e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

}
