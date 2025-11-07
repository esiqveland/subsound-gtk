package com.github.subsound.integration.platform.secret;

import org.gnome.secret.Schema;
import org.gnome.secret.SchemaType;
import org.gnome.secret.Secret;
import org.javagi.base.GErrorException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecretService {
    private static final Schema SCHEMA = Secret.getSchema(SchemaType.COMPAT_NETWORK);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public CompletableFuture<Boolean> storePassword(
            String serverId,
            String username,
            String password
    ) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var attributes = Secret.attributesBuild(SCHEMA, "server", serverId, "user", username, null);
                        return Secret.passwordStoreSync(SCHEMA, attributes, Secret.COLLECTION_DEFAULT, "SubSound", password, null);
                    } catch (GErrorException e) {
                        return false;
                    }
                },
                executor
        );
    }

    public CompletableFuture<String> lookupPassword(String serverId, String username) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var attributes = Secret.attributesBuild(SCHEMA, "server", serverId, "user", username, null);
                        attributes.insert("server", serverId);
                        attributes.insert("user", username);
                        return Secret.passwordLookupSync(SCHEMA, attributes, null);
                    } catch (GErrorException e) {
                        return null;
                    }
                },
                executor
        );
    }
}
