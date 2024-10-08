package com.github.subsound.configuration;

import com.github.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import com.github.subsound.configuration.Config.ConfigurationDTO.ServerConfigDTO;
import com.github.subsound.integration.ServerClient.ServerType;
import com.github.subsound.integration.platform.PortalUtils;
import com.github.subsound.utils.Utils;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private final Path configFilePath;
    public final boolean isTestpageEnabled = "true".equals(System.getenv("SUBSOUND_TESTPAGE_ENABLED"));
    public Path dataDir = defaultStorageDir();
    public ServerConfig serverConfig;
    public OnboardingState onboarding;

    public Config(Path configFilePath) {
        this.configFilePath = configFilePath;
    }

    public void saveToFile() throws IOException {
        // assure parent folder exists:
        this.configFilePath.getParent().toFile().mkdirs();
        var dto = toFileFormat();
        var jsonStr = Utils.toJson(dto);
        Files.writeString(this.configFilePath, jsonStr, StandardCharsets.UTF_8);
    }

    private ConfigurationDTO toFileFormat() {
        var d = new ConfigurationDTO();
        d.onboarding = this.onboarding;
        d.server = new ServerConfigDTO(
                this.serverConfig.id(),
                this.serverConfig.type(),
                this.serverConfig.url(),
                this.serverConfig.username(),
                this.serverConfig.password()
        );
        return d;
    }

    public record ServerConfig(
            // the root of where we store app data
            Path dataDir,
            String id,
            ServerType type,
            String url,
            String username,
            String password
    ) {}

    public static Config createDefault() {
        var configDir = defaultConfigDir();
        var configFilePath = configDir.resolve("config.json");

        Config config = new Config(configFilePath);
        config.dataDir = defaultStorageDir().toAbsolutePath();

        readConfigFile(configFilePath)
                .ifPresentOrElse(
                        cfg -> {
                            log.debug("read config file at path={}", configFilePath);
                            config.onboarding = cfg.onboarding;
                            if (cfg.server != null) {
                                config.onboarding = OnboardingState.DONE;
                                config.serverConfig = new ServerConfig(
                                        config.dataDir,
                                        cfg.server.id,
                                        cfg.server.type,
                                        cfg.server.url,
                                        cfg.server.username,
                                        cfg.server.password
                                );
                            }
                        },
                        () -> log.debug("no config file found at path={}", configFilePath)
                );

        return config;
    }

    public static class ConfigurationDTO {
        public record ServerConfigDTO(
                String id,
                ServerType type,
                String url,
                String username,
                String password
        ) {}

        @SerializedName("server")
        public ServerConfigDTO server;
        @SerializedName("onboarding")
        public OnboardingState onboarding;
        public enum OnboardingState {
            DONE,
        }
    }

    private static Optional<ConfigurationDTO> readConfigFile(Path configPath) {
        var configFile = configPath.toFile();
        if (!configFile.exists()) {
            return Optional.empty();
        }
        if (configFile.isDirectory()) {
            throw new IllegalStateException("expected file at path=%s".formatted(configPath.toString()));
        }
        if (!configFile.isFile()) {
            throw new IllegalStateException("expected file at path=%s".formatted(configPath.toString()));
        }
        if (!configFile.canRead()) {
            throw new IllegalStateException("unable to read file at path=%s".formatted(configPath.toString()));
        }
        try {
            String value = Files.readString(configPath, StandardCharsets.UTF_8);
            return parseCfg(value);
        } catch (IOException e) {
            throw new RuntimeException("unable to read file at path=%s".formatted(configPath.toString()), e);
        }
    }

    static Optional<ConfigurationDTO> parseCfg(String value) {
        var cfg = Utils.fromJson(value, ConfigurationDTO.class);
        return Optional.ofNullable(cfg);
    }

    private static Path defaultStorageDir() {
        {
            String userDataDir = PortalUtils.getUserDataDir();
            if (userDataDir != null && !userDataDir.isBlank()) {
                var p = Path.of(userDataDir, "subsound-gtk").toAbsolutePath();
                var fd = p.toFile();
                if (!fd.exists()) {
                    fd.mkdirs();
                }
                return p;
            }
        }
        {
            var xdg = Utils.firstNotBlank(System.getenv("XDG_DATA_HOME"), System.getenv("XDG_CACHE_HOME"));
            if (!xdg.isBlank()) {
                Path path = java.nio.file.Path.of(xdg, "subsound-gtk").toAbsolutePath();
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

    // https://specifications.freedesktop.org/basedir-spec/latest/index.html#variables
    private static Path defaultConfigDir() {
        var userConfigDir = PortalUtils.getUserConfigDir();
        if (userConfigDir != null && !userConfigDir.isBlank()) {
            Path path = Path.of(userConfigDir, "subsound-gtk").toAbsolutePath();
            var fHandle = path.toFile();
            if (!fHandle.exists()) {
                fHandle.mkdirs();
            }
            return path;
        }

        var xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            Path path = Path.of(xdg, "subsound-gtk").toAbsolutePath();
            var fHandle = path.toFile();
            if (!fHandle.exists()) {
                fHandle.mkdirs();
            }
            return path;
        }

        //  If $XDG_CONFIG_HOME is either not set or empty, a default equal to $HOME/.config should be used
        var homeDir = System.getenv("HOME");
        if (homeDir == null || homeDir.isBlank()) {
            throw new IllegalStateException("unable to determine a location for cache dir");
        }
        Path path = Path.of(homeDir, ".config", "subsound-gtk").toAbsolutePath();
        var fHandle = path.toFile();
        if (!fHandle.exists()) {
            fHandle.mkdirs();
        }
        return path;
    }
}
