package org.subsound.integration.platform.secret;

import org.gnome.secret.Schema;
import org.gnome.secret.SchemaAttributeType;
import org.gnome.secret.SchemaFlags;
import org.gnome.secret.Secret;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Optional.ofNullable;

/**
 * Credential storage using libsecret.
 *
 * <p>Inside Flatpak, libsecret (>= 0.20) has two distinct backends depending on which API is used:
 *
 * <ul>
 *   <li><b>Simple API</b> ({@code secret_password_store_sync}, {@code secret_password_lookup_sync},
 *       {@code secret_password_clear_sync}): uses a local encrypted file backend via the
 *       {@code org.freedesktop.portal.Secret} portal. Secrets are stored in
 *       {@code ~/.var/app/$APPID/data/keyrings/}.</li>
 *   <li><b>Complete API</b> ({@code SecretService}, {@code secret_service_search_sync}): bypasses
 *       the portal and talks directly to the host keyring over D-Bus
 *       ({@code org.freedesktop.secrets}). This is a completely different storage location.</li>
 * </ul>
 *
 * <p>All methods in this class use the Simple API to ensure they read from and write to the same
 * backend. Mixing Simple and Complete API calls will silently read/write different stores, causing
 * credentials to appear missing on Flatpak.
 */
public final class LibsecretService implements SecretService {
    private static final Logger log = LoggerFactory.getLogger(LibsecretService.class);
    private final Schema SCHEMA;

    LibsecretService() {
        // we want to make sure this fails on constructor, not on class load at least:
        SCHEMA = buildSchema();
    }

    private static Schema buildSchema() {
        // Schema(name, flags, ...) binds the variadic secret_schema_new(): the varargs must be
        // (attribute-name, SchemaAttributeType) pairs terminated with null. Passing a GHashTable
        // here (that overload is Schema.newv) makes libsecret read the table pointer as an
        // attribute-name char*, yielding a schema without any of our attributes.
        return new Schema(
                "io.github.subsoundorg.Subsound.Credentials",
                SchemaFlags.NONE,
                "server-id", SchemaAttributeType.STRING,
                "username", SchemaAttributeType.STRING,
                null
        );
    }

    @Override
    public boolean storeCredentialsSync(String serverId, String username, String password) {
        try {
            var attributes = Secret.attributesBuild(SCHEMA, "server-id", serverId, "username", username, null);
            return Secret.passwordStorevSync(
                    SCHEMA, attributes, Secret.COLLECTION_DEFAULT,
                    "Subsound: " + username, password, null
            );
        } catch (Exception e) {
            log.warn("Failed to store credentials in libsecret for server={}: {}", serverId, e.getMessage());
            return false;
        }
    }

    @Override
    public @Nullable Credentials lookupCredentialsSync(String serverId, String username) {
        var c = lookupCredentialsSyncInner(serverId, username);
        int ss = ofNullable(c).map(Credentials::password).map(String::length).orElse(0);
        log.info("lookupCredentialsSync serverId={}, username={}, credentials.size={}", serverId, username, ss);
        return c;
    }

    private @Nullable Credentials lookupCredentialsSyncInner(String serverId, String username) {
        try {
            var attributes = Secret.attributesBuild(SCHEMA, "server-id", serverId, "username", username, null);
            var password = Secret.passwordLookupvSync(SCHEMA, attributes, null);
            if (password == null || password.isEmpty()) {
                return null;
            }
            return new Credentials(username, password);
        } catch (Exception e) {
            log.warn("Failed to lookup credentials in libsecret for server={}: {}", serverId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteCredentials(String serverId) {
        try {
            var attributes = Secret.attributesBuild(SCHEMA, "server-id", serverId, null);
            return Secret.passwordClearvSync(SCHEMA, attributes, null);
        } catch (Exception e) {
            log.warn("Failed to delete credentials in libsecret for server={}: {}", serverId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
