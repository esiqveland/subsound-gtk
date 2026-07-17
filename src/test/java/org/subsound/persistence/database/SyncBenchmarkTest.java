package org.subsound.persistence.database;

import ch.qos.logback.classic.Level;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import org.subsound.configuration.Config;
import org.subsound.configuration.Config.ServerConfig;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ServerType;
import org.subsound.integration.ServerClient.TranscodeBitrate;
import org.subsound.integration.ServerClient.TranscodeFormat;
import org.subsound.integration.platform.secret.SecretService;
import org.subsound.integration.servers.subsonic.SubsonicClientV2;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.persistence.ThumbnailCache.ThumbLoaded;
import org.subsound.persistence.database.SyncService.SyncStats;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Benchmark comparing the two full-sync strategies against a real server:
 * the per-artist/per-album walk versus paged search3 empty-query fetching.
 *
 * Not a correctness test — it prints a stdout summary of elapsed time, request
 * counts, and sync stats for both methods, and flags any stat mismatch.
 *
 * Requires a real server: either export SUBSOUND_BENCH_URL, SUBSOUND_BENCH_USERNAME
 * and SUBSOUND_BENCH_PASSWORD, or have a server configured in the app (config.json +
 * app database + keyring, resolved the same way AppManager does). Syncs into throwaway
 * temp databases; the real app database is only read. Thumbnail downloads are
 * stubbed out so both runs measure metadata sync only.
 *
 * Run manually by removing @Ignore:
 *   ./gradlew cleanTest test --tests "org.subsound.persistence.database.SyncBenchmarkTest"
 */
@Ignore("Benchmark requires a real server connection")
public class SyncBenchmarkTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private record DbCounts(int artists, int albums, int songs, int playlists) {}

    private record BenchResult(String name, SyncStats stats, DbCounts dbCounts, Duration elapsed, Map<String, AtomicInteger> requests) {
        int totalRequests() {
            return requests.values().stream().mapToInt(AtomicInteger::get).sum();
        }
        String breakdown() {
            return requests.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, AtomicInteger> e) -> e.getValue().get()).reversed())
                    .map(e -> "%s=%d".formatted(e.getKey(), e.getValue().get()))
                    .collect(Collectors.joining(", "));
        }
    }

    @Test
    public void benchmarkSyncMethods() throws Exception {
        var serverConfig = resolveServerConfig();
        var client = SubsonicClientV2.create(serverConfig);

        // keep stdout readable: per-album/per-request info logs off, summaries (warn) stay
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.subsound")).setLevel(Level.WARN);

        var info = client.getServerInfo();
        System.out.println();
        System.out.println("=== Full sync benchmark ===");
        System.out.println("server:  %s (%s %s, api %s)".formatted(
                serverConfig.url(),
                info.serverType().orElse("unknown"),
                info.serverVersion().orElse("?"),
                info.apiVersion()));
        System.out.println("library: %d songs".formatted(info.songCount()));
        System.out.println();

        var walk = runSync("artist-walk", client, true);
        var search3 = runSync("search3", client, false);

        printSummary(walk, search3);
    }

    /**
     * Server selection, in priority order:
     *  1. SUBSOUND_BENCH_URL / SUBSOUND_BENCH_USERNAME / SUBSOUND_BENCH_PASSWORD env vars
     *  2. the app's own configured server (config.json serverId -> servers table in the
     *     app database -> password from the keyring, same resolution as AppManager)
     */
    private ServerConfig resolveServerConfig() throws Exception {
        var benchUrl = System.getenv("SUBSOUND_BENCH_URL");
        if (benchUrl != null && !benchUrl.isBlank()) {
            return new ServerConfig(
                    folder.newFolder("data").toPath(),
                    "benchmark",
                    ServerType.SUBSONIC,
                    benchUrl,
                    System.getenv("SUBSOUND_BENCH_USERNAME"),
                    System.getenv("SUBSOUND_BENCH_PASSWORD"),
                    null,
                    null,
                    false,
                    List.of()
            );
        }

        var secretService = SecretService.create();
        var config = Config.createDefault(secretService);
        if (config.serverId == null) {
            throw new IllegalStateException("no server configured: set SUBSOUND_BENCH_URL or configure a server in the app first");
        }
        var appDb = new Database();
        var server = new DatabaseService(appDb).getServerById(config.serverId)
                .orElseThrow(() -> new IllegalStateException("serverId=%s not found in app database".formatted(config.serverId)));
        var creds = secretService.lookupCredentialsSync(server.id().toString(), server.username());
        var password = creds != null ? creds.password() : config.fallbackPassword;

        TranscodeFormat audioFormat = null;
        if (server.audioFormat() != null) {
            try {
                audioFormat = TranscodeFormat.valueOf(server.audioFormat());
            } catch (IllegalArgumentException ignored) {}
        }
        TranscodeBitrate audioBitrate = null;
        if (server.audioBitrate() != null && server.audioBitrate() > 0) {
            audioBitrate = TranscodeBitrate.MaximumBitrate.of(server.audioBitrate());
        }
        return new ServerConfig(
                folder.newFolder("data").toPath(),
                server.id().toString(),
                server.serverType(),
                server.serverUrl(),
                server.username(),
                password != null ? password : "",
                audioFormat,
                audioBitrate,
                server.tlsSkipVerify(),
                server.customHeaders()
        );
    }

    private BenchResult runSync(String name, ServerClient realClient, boolean rejectSearch3) throws Exception {
        File dbFile = folder.newFile(name + ".db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        var serverId = UUID.randomUUID();
        var dbService = new DatabaseServerService(serverId, db);

        var requests = new ConcurrentHashMap<String, AtomicInteger>();
        var countingClient = countingClient(realClient, rejectSearch3, requests);

        ThumbnailCache thumbnailCache = mock(ThumbnailCache.class);
        when(thumbnailCache.loadThumbAsync(any()))
                .thenAnswer(_ -> CompletableFuture.completedFuture(new ThumbLoaded(null)));

        var syncService = new SyncService(countingClient, dbService, serverId, thumbnailCache, query -> true);

        System.out.println("running %s sync...".formatted(name));
        long start = System.nanoTime();
        var stats = syncService.syncAll();
        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        System.out.println("  done in %d ms: %s".formatted(elapsed.toMillis(), stats));
        var dbCounts = new DbCounts(
                countRows(db, "artists"),
                countRows(db, "albums"),
                countRows(db, "songs"),
                countRows(db, "playlists")
        );
        return new BenchResult(name, stats, dbCounts, elapsed, requests);
    }

    private static int countRows(Database db, String table) throws Exception {
        try (var conn = db.openConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Wraps the real client, counting every call by method name. With rejectSearch3
     * the search3 probe throws, forcing SyncService onto the artist-walk path.
     */
    private static ServerClient countingClient(ServerClient delegate, boolean rejectSearch3, Map<String, AtomicInteger> requests) {
        return (ServerClient) Proxy.newProxyInstance(
                ServerClient.class.getClassLoader(),
                new Class<?>[]{ServerClient.class},
                (_, method, args) -> {
                    if (method.getDeclaringClass() != Object.class) {
                        requests.computeIfAbsent(method.getName(), _ -> new AtomicInteger()).incrementAndGet();
                    }
                    if (rejectSearch3 && method.getName().equals("search3")) {
                        throw new UnsupportedOperationException("search3 disabled for artist-walk benchmark");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void printSummary(BenchResult walk, BenchResult search3) {
        System.out.println();
        System.out.println("method        elapsed      requests   artists   albums    songs   playlists");
        for (var r : new BenchResult[]{walk, search3}) {
            System.out.println("%-12s %8d ms   %9d %9d %8d %8d %11d".formatted(
                    r.name(),
                    r.elapsed().toMillis(),
                    r.totalRequests(),
                    r.dbCounts().artists(),
                    r.dbCounts().albums(),
                    r.dbCounts().songs(),
                    r.dbCounts().playlists()));
        }
        System.out.println();

        double speedup = search3.elapsed().toMillis() > 0
                ? (double) walk.elapsed().toMillis() / search3.elapsed().toMillis()
                : Double.POSITIVE_INFINITY;
        System.out.println("search3 was %.1fx faster with %d fewer requests (%d -> %d)".formatted(
                speedup,
                walk.totalRequests() - search3.totalRequests(),
                walk.totalRequests(),
                search3.totalRequests()));
        System.out.println();

        System.out.println("request breakdown:");
        System.out.println("  %-12s %s".formatted(walk.name() + ":", walk.breakdown()));
        System.out.println("  %-12s %s".formatted(search3.name() + ":", search3.breakdown()));
        System.out.println();

        if (walk.dbCounts().equals(search3.dbCounts())) {
            System.out.println("database contents match: yes (%s)".formatted(walk.dbCounts()));
        } else {
            System.out.println("database contents match: NO — walk=%s search3=%s".formatted(walk.dbCounts(), search3.dbCounts()));
        }
    }
}
