# Keycloak Microsoft profile photo

Gives users brokered from Microsoft / Entra ID a `picture` claim, similar to other identity providers (e.g. Google).

Microsoft does not put a photo URL in its tokens. The photo is only available as binary data from
`GET https://graph.microsoft.com/v1.0/me/photo/$value`. This extension provides two parts:

1. **Identity provider mapper** ("Microsoft Graph Photo"). On login it takes the Microsoft access token
   that Keycloak just received, downloads the photo from Graph and saves it on the Keycloak user
   (`ms-photo`, `ms-photo-type` and `ms-photo-hash` attributes). None of these attributes go into tokens.
2. **Realm endpoint** `GET /realms/{realm}/ms-photo/{userId}/{hash}`, which serves that image.

The `picture` user attribute is set to that endpoint's URL. Tokens therefore carry a URL of roughly 120
characters. The `{hash}` is the photo's SHA-256, so the URL can't be guessed and can
be cached permanently. When the photo changes, the URL changes too.

If the Graph call fails, the login still goes through and the user keeps their current photo. If Graph
says the user has no photo, the stored one is removed.

## Build

```bash
mvn package
```

This produces `target/keycloak-microsoft-profile-photo-1.0.0.jar`. Set `keycloak.version` in `pom.xml`
to the Keycloak version you run. The code is written against 26.x.

## Install

Copy the jar to `/opt/keycloak/providers/`, then run `kc.sh build`, or rebuild your image. For example:

```dockerfile
FROM quay.io/keycloak/keycloak:26.7.4 AS builder
COPY keycloak-microsoft-profile-photo-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
```

## Configure

1. **Entra app registration**: under *API permissions*, grant Microsoft Graph the **delegated** `User.Read`
   permission. Most tenants already allow users to consent to it.
2. **Keycloak identity provider**:
   - With the built-in **Microsoft** provider, there is nothing to change: its default scope already
     includes `User.Read`.
   - With Entra ID set up as a generic **OpenID Connect** provider, set *Scopes* to
     `openid profile email User.Read`. Don't request scopes for another API in the same flow. Entra issues
     the access token for one resource only, and it has to be Microsoft Graph.
   - You do **not** need *Store tokens*. The mapper uses the token while the login is still in progress.
3. **Add the mapper**: go to *Identity providers → (your Microsoft IdP) → Mappers → Add mapper*, pick the
   type **Microsoft Graph Photo**, and set *Sync mode override* to **Force** so the photo refreshes on every
   login. The defaults are a size of `240x240` and the attribute `picture`.
4. **The `picture` claim**: the built-in `profile` client scope already maps the `picture` user attribute
   to the `picture` claim.

Existing users get their photo the next time they sign in with Microsoft.

## Notes

- **User profile (Keycloak 24+)**: the `ms-photo*` attributes are unmanaged. Keycloak keeps them on
  profile updates and doesn't show them in the account console. `picture` behaves the same as for other sources.
- **Storage**: a 240x240 JPEG is about 10–20 KB of base64 per user, stored in the user attribute table and
  held in the user cache. A larger size means more storage.
- The endpoint sends `Access-Control-Allow-Origin: *` and answers CORS preflights, so browser code
  (`fetch()`, Unity WebGL's `UnityWebRequest`) can load the photo from any origin. This is safe because the
  response is a public image and no cookies or credentials are involved.
- The photo URL uses the hostname of the request that performed the login. This matters only if
  Keycloak is reachable under more than one hostname.
