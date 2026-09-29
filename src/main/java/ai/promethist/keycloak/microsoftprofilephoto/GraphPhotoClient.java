package ai.promethist.keycloak.microsoftprofilephoto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

/** Downloads the signed-in user's photo from Microsoft Graph using their delegated access token. */
final class GraphPhotoClient {

    record Photo(byte[] bytes, String contentType) {
    }

    private static final String ME = "https://graph.microsoft.com/v1.0/me";
    private static final int MAX_BYTES = 512 * 1024;

    // Login is blocked while we wait, so fail fast rather than inherit the server-wide defaults.
    private static final RequestConfig TIMEOUTS = RequestConfig.custom()
            .setConnectionRequestTimeout(2000)
            .setConnectTimeout(3000)
            .setSocketTimeout(5000)
            .build();

    private final CloseableHttpClient http;

    GraphPhotoClient(CloseableHttpClient http) {
        this.http = http;
    }

    /**
     * @param size one of Graph's supported sizes (e.g. {@code 240x240}), or {@code null} for the original
     * @return the photo, or {@code null} if the user has none
     * @throws IOException on any other failure (bad token, missing consent, network), so callers can keep
     *                     whatever photo they already have
     */
    Photo fetch(String accessToken, String size) throws IOException {
        if (size != null) {
            // Sized variants are not available for every account type (e.g. personal accounts); fall back to
            // the original on any client error.
            Photo sized = get(ME + "/photos/" + size + "/$value", accessToken, true);
            if (sized != null) {
                return sized;
            }
        }
        return get(ME + "/photo/$value", accessToken, false);
    }

    private Photo get(String url, String accessToken, boolean nullOnClientError) throws IOException {
        HttpGet request = new HttpGet(url);
        request.setConfig(TIMEOUTS);
        request.setHeader("Authorization", "Bearer " + accessToken);
        request.setHeader("Accept", "image/*");

        try (CloseableHttpResponse response = http.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            if (status != 200) {
                EntityUtils.consumeQuietly(entity);
                if (status == 404 || (nullOnClientError && status >= 400 && status < 500)) {
                    return null;
                }
                throw new IOException("Microsoft Graph returned HTTP " + status + " for " + url);
            }
            if (entity == null) {
                throw new IOException("Microsoft Graph returned an empty photo");
            }
            String contentType = mimeType(entity.getContentType());
            if (!StoredPhoto.ALLOWED_TYPES.contains(contentType)) {
                EntityUtils.consumeQuietly(entity);
                throw new IOException("Unexpected photo content type: " + contentType);
            }
            try (InputStream in = entity.getContent()) {
                return new Photo(readLimited(in), contentType);
            }
        }
    }

    private static String mimeType(Header header) {
        if (header == null || header.getValue() == null) {
            return "";
        }
        String value = header.getValue();
        int semicolon = value.indexOf(';');
        return (semicolon >= 0 ? value.substring(0, semicolon) : value).trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_BYTES) {
                throw new IOException("Photo exceeds " + MAX_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
