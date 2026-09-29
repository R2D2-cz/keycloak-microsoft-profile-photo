package ai.promethist.keycloak.microsoftprofilephoto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.Urls;

/**
 * How a Microsoft photo is kept on the Keycloak user. The image itself lives in user attributes that are
 * never mapped into tokens; only a short URL pointing at {@link MicrosoftPhotoResourceProvider} goes into
 * the {@code picture} attribute/claim.
 */
final class StoredPhoto {

    static final String DATA_ATTRIBUTE = "ms-photo";
    static final String TYPE_ATTRIBUTE = "ms-photo-type";
    static final String HASH_ATTRIBUTE = "ms-photo-hash";

    /** Only raster formats; never serve anything a browser could execute from the Keycloak origin. */
    static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private StoredPhoto() {
    }

    /**
     * Content hash used both as a cache-buster and as the unguessable part of the photo URL,
     * so knowing a user ID alone is not enough to fetch someone's photo.
     */
    static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean hashMatches(String stored, String requested) {
        return stored != null && requested != null
                && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), requested.getBytes(StandardCharsets.UTF_8));
    }

    static String url(KeycloakSession session, RealmModel realm, UserModel user, String hash) {
        return Urls.realmBase(session.getContext().getUri().getBaseUri())
                .path(realm.getName())
                .path(MicrosoftPhotoResourceProviderFactory.ID)
                .path(user.getId())
                .path(hash)
                .build()
                .toString();
    }

    /** True if the value is a URL this extension generated for the user (regardless of hostname). */
    static boolean isOurUrl(String value, UserModel user) {
        return value != null && value.contains("/" + MicrosoftPhotoResourceProviderFactory.ID + "/" + user.getId() + "/");
    }
}
