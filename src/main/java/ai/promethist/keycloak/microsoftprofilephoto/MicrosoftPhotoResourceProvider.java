package ai.promethist.keycloak.microsoftprofilephoto;

import java.util.Base64;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.resource.RealmResourceProvider;

/**
 * Serves stored photos at {@code /realms/{realm}/ms-photo/{userId}/{hash}}. Public (so it works in a plain
 * {@code <img src>}), but the content hash in the path makes the URL unguessable, like Google's photo URLs.
 */
public class MicrosoftPhotoResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public MicrosoftPhotoResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Path("{userId}/{hash}")
    public Response photo(@PathParam("userId") String userId, @PathParam("hash") String hash) {
        RealmModel realm = session.getContext().getRealm();
        UserModel user = session.users().getUserById(realm, userId);
        if (user == null || !user.isEnabled()) {
            return notFound();
        }

        String data = user.getFirstAttribute(StoredPhoto.DATA_ATTRIBUTE);
        String type = user.getFirstAttribute(StoredPhoto.TYPE_ATTRIBUTE);
        if (data == null || !StoredPhoto.ALLOWED_TYPES.contains(type)
                || !StoredPhoto.hashMatches(user.getFirstAttribute(StoredPhoto.HASH_ATTRIBUTE), hash)) {
            return notFound();
        }

        // The URL changes whenever the photo does, so it can be cached forever.
        return Response.ok(Base64.getDecoder().decode(data), type)
                .header("Access-Control-Allow-Origin", "*")
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .header("ETag", "\"" + hash + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .header("Cross-Origin-Resource-Policy", "cross-origin")
                .build();
    }

    /**
     * CORS preflight, sent by browsers when a script adds custom request headers. A wildcard origin is safe
     * here: the response is the same public image an {@code <img>} tag can load, and no credentials are used.
     */
    @OPTIONS
    @Path("{userId}/{hash}")
    public Response preflight(@HeaderParam("Access-Control-Request-Headers") String requestHeaders) {
        Response.ResponseBuilder response = Response.noContent()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, OPTIONS")
                .header("Access-Control-Max-Age", "86400");
        if (requestHeaders != null && !requestHeaders.isBlank()) {
            response.header("Access-Control-Allow-Headers", requestHeaders);
        }
        return response.build();
    }

    private static Response notFound() {
        // CORS header on errors too, so browser scripts can see the 404 instead of an opaque network error.
        return Response.status(Response.Status.NOT_FOUND)
                .header("Access-Control-Allow-Origin", "*")
                .header("Cache-Control", "no-store")
                .build();
    }

    @Override
    public void close() {
    }
}
