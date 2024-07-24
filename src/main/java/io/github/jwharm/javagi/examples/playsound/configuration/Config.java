package io.github.jwharm.javagi.examples.playsound.configuration;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ServerType;

import java.nio.file.Path;

public class Config {
    public static final Dotenv DOTENV = Dotenv.load();

    public static Config createDefault() {
        Config config = new Config();
        config.cacheDir = defaultCacheDir();
        config.serverConfig = defaultServerConfig();
        return config;
    }

    public Path cacheDir = defaultCacheDir();

    public static record ServerConfig(
            ServerType type,
            String url,
            String username,
            String password
    ) {
    }

    public ServerConfig serverConfig;

    private static ServerConfig defaultServerConfig() {
        return new ServerConfig(
                ServerType.SUBSONIC,
                DOTENV.get("SERVER_URL"),
                DOTENV.get("SERVER_USERNAME"),
                DOTENV.get("SERVER_PASSWORD")
        );

    }

    private static Path defaultCacheDir() {
        {
            var xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isBlank()) {
                Path path = Path.of(xdg, "subsound-gtk").toAbsolutePath();
                var fHandle = path.toFile();
                if (!fHandle.exists()) {
                    fHandle.mkdirs();
                }
                return path;
            }
        }

        {
            var homeDir = System.getenv("HOME");
            if (homeDir == null || homeDir.isBlank()) {
                throw new IllegalStateException("unable to determine a location for cache dir");
            }
            Path path = Path.of(homeDir, ".cache", "subsound-gtk").toAbsolutePath();
            var fHandle = path.toFile();
            if (!fHandle.exists()) {
                fHandle.mkdirs();
            }
            return path;
        }
    }
}
