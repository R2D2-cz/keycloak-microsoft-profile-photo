package ai.promethist.keycloak.microsoftprofilephoto;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.util.JsonSerialization;

/**
 * Identity provider mapper for Microsoft / Entra ID that, on login, downloads the user's photo from
 * Microsoft Graph, stores it on the Keycloak user and sets the picture attribute to a URL serving it.
 * A failure never blocks the login; the previously stored photo is kept.
 */
public class MicrosoftPhotoMapper extends AbstractIdentityProviderMapper {

    public static final String PROVIDER_ID = "microsoft-graph-photo-mapper";

    static final String CONFIG_SIZE = "photoSize";
    static final String CONFIG_PICTURE_ATTRIBUTE = "pictureAttribute";

    private static final String ORIGINAL_SIZE = "original";
    private static final String DEFAULT_SIZE = "240x240";
    private static final String DEFAULT_PICTURE_ATTRIBUTE = "picture";

    // Context data keys set by Keycloak's built-in providers.
    private static final String MICROSOFT_ACCESS_TOKEN = "FEDERATED_ACCESS_TOKEN";
    private static final String OIDC_TOKEN_RESPONSE = "FEDERATED_ACCESS_TOKEN_RESPONSE";

    // "microsoft" is the built-in social provider, "oidc" covers Entra ID configured as a generic OIDC provider.
    private static final String[] COMPATIBLE_PROVIDERS = {"microsoft", "oidc"};

    private static final Logger LOG = Logger.getLogger(MicrosoftPhotoMapper.class);

    private static final List<ProviderConfigProperty> CONFIG = ProviderConfigurationBuilder.create()
            .property()
                .name(CONFIG_SIZE)
                .label("Photo size")
                .helpText("Size requested from Microsoft Graph. Smaller is faster and lighter on the user store; "
                        + "'original' can be up to 648x648.")
                .type(ProviderConfigProperty.LIST_TYPE)
                .options("48x48", "64x64", "96x96", "120x120", "240x240", "360x360", "432x432", "504x504",
                        "648x648", ORIGINAL_SIZE)
                .defaultValue(DEFAULT_SIZE)
                .add()
            .property()
                .name(CONFIG_PICTURE_ATTRIBUTE)
                .label("Picture URL attribute")
                .helpText("User attribute that receives the photo URL. Map it to the 'picture' claim "
                        + "(the built-in 'profile' scope already maps the 'picture' attribute).")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(DEFAULT_PICTURE_ATTRIBUTE)
                .add()
            .build();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String[] getCompatibleProviders() {
        return COMPATIBLE_PROVIDERS;
    }

    @Override
    public String getDisplayCategory() {
        return "Attribute Importer";
    }

    @Override
    public String getDisplayType() {
        return "Microsoft Graph Photo";
    }

    @Override
    public String getHelpText() {
        return "Downloads the user's photo from Microsoft Graph (needs the User.Read scope) and sets the picture "
                + "attribute to a Keycloak URL serving it. Use sync mode 'force' to refresh it on every login.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG;
    }

    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode syncMode) {
        return true;
    }

    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm, UserModel user,
                              IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        syncPhoto(session, realm, user, mapperModel, context);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm, UserModel user,
                                   IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        syncPhoto(session, realm, user, mapperModel, context);
    }

    @Override
    public void updateBrokeredUserLegacy(KeycloakSession session, RealmModel realm, UserModel user,
                                         IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        syncPhoto(session, realm, user, mapperModel, context);
    }

    private void syncPhoto(KeycloakSession session, RealmModel realm, UserModel user,
                           IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        String accessToken = accessToken(context);
        if (accessToken == null) {
            LOG.warnf("No Microsoft access token available for user %s, skipping photo sync", user.getId());
            return;
        }

        GraphPhotoClient.Photo photo;
        try {
            HttpClientProvider httpClient = session.getProvider(HttpClientProvider.class);
            photo = new GraphPhotoClient(httpClient.getHttpClient()).fetch(accessToken, size(mapperModel));
        } catch (IOException | RuntimeException e) {
            LOG.warnf(e, "Could not fetch Microsoft Graph photo for user %s, keeping the stored one", user.getId());
            return;
        }

        String pictureAttribute = pictureAttribute(mapperModel);
        if (photo == null) {
            removePhoto(user, pictureAttribute);
            return;
        }

        String hash = StoredPhoto.hash(photo.bytes());
        // Skip the write when nothing changed, which is the common case on repeat logins.
        if (!hash.equals(user.getFirstAttribute(StoredPhoto.HASH_ATTRIBUTE))) {
            user.setSingleAttribute(StoredPhoto.DATA_ATTRIBUTE, Base64.getEncoder().encodeToString(photo.bytes()));
            user.setSingleAttribute(StoredPhoto.TYPE_ATTRIBUTE, photo.contentType());
            user.setSingleAttribute(StoredPhoto.HASH_ATTRIBUTE, hash);
        }
        String url = StoredPhoto.url(session, realm, user, hash);
        if (!url.equals(user.getFirstAttribute(pictureAttribute))) {
            user.setSingleAttribute(pictureAttribute, url);
        }
    }

    private static void removePhoto(UserModel user, String pictureAttribute) {
        if (user.getFirstAttribute(StoredPhoto.HASH_ATTRIBUTE) != null) {
            user.removeAttribute(StoredPhoto.DATA_ATTRIBUTE);
            user.removeAttribute(StoredPhoto.TYPE_ATTRIBUTE);
            user.removeAttribute(StoredPhoto.HASH_ATTRIBUTE);
        }
        // Leave pictures that came from somewhere else (e.g. a linked Google account) alone.
        if (StoredPhoto.isOurUrl(user.getFirstAttribute(pictureAttribute), user)) {
            user.removeAttribute(pictureAttribute);
        }
    }

    private static String accessToken(BrokeredIdentityContext context) {
        Object token = context.getContextData().get(MICROSOFT_ACCESS_TOKEN);
        if (token instanceof String value && !value.isBlank()) {
            return value;
        }
        Object response = context.getContextData().get(OIDC_TOKEN_RESPONSE);
        if (response instanceof AccessTokenResponse value && value.getToken() != null) {
            return value.getToken();
        }
        // Only present when "Store tokens" is enabled on the identity provider.
        String stored = context.getToken();
        if (stored != null && stored.startsWith("{")) {
            try {
                return JsonSerialization.readValue(stored, AccessTokenResponse.class).getToken();
            } catch (IOException e) {
                LOG.debug("Stored identity provider token is not a JSON token response", e);
            }
        }
        return null;
    }

    private static String size(IdentityProviderMapperModel mapperModel) {
        String size = mapperModel.getConfig().getOrDefault(CONFIG_SIZE, DEFAULT_SIZE);
        if (size == null || size.isBlank()) {
            return DEFAULT_SIZE;
        }
        return ORIGINAL_SIZE.equals(size) ? null : size;
    }

    private static String pictureAttribute(IdentityProviderMapperModel mapperModel) {
        String attribute = mapperModel.getConfig().get(CONFIG_PICTURE_ATTRIBUTE);
        return attribute == null || attribute.isBlank() ? DEFAULT_PICTURE_ATTRIBUTE : attribute.trim();
    }
}
