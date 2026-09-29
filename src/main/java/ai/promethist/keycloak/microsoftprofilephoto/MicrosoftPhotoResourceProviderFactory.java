package ai.promethist.keycloak.microsoftprofilephoto;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public class MicrosoftPhotoResourceProviderFactory implements RealmResourceProviderFactory {

    /** Also the path segment: {@code /realms/{realm}/ms-photo/...}. */
    public static final String ID = "ms-photo";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new MicrosoftPhotoResourceProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
