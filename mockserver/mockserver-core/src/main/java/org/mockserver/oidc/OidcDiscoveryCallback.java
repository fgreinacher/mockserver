package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;

/**
 * Serves the OIDC discovery document ({@code /.well-known/openid-configuration}).
 *
 * <p>A class callback rather than a static response so the {@code issuer} — and every endpoint URL
 * derived from it — is resolved <em>per request</em> by {@link OidcIssuerResolver}. OIDC Discovery
 * §4.3 requires the advertised issuer to match the URL the relying party fetched the document from;
 * baking a fixed {@code http://localhost:1080} at generate time broke every client running the mock
 * on a random port (Testcontainers), which is the most common deployment.
 */
public class OidcDiscoveryCallback implements ExpectationResponseCallback {

    private static final String APPLICATION_JSON = "application/json; charset=utf-8";

    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);

    @Override
    public HttpResponse handle(HttpRequest request) {
        OidcAuthorizationStore.Provider provider = OidcAuthorizationStore.getInstance().latestProvider();
        if (provider == null) {
            return response()
                .withStatusCode(404)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody("{\"error\":\"no OIDC provider configured\"}");
        }

        OidcProviderConfiguration config = provider.getConfig();
        String issuer = OidcIssuerResolver.resolve(config, request);
        String signingAlg = provider.getTokenMinter().getJwsAlgorithm();

        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("issuer", issuer);
        discovery.put("authorization_endpoint", issuer + config.getAuthorizePath());
        discovery.put("token_endpoint", issuer + config.getTokenPath());
        discovery.put("userinfo_endpoint", issuer + config.getUserinfoPath());
        discovery.put("jwks_uri", issuer + config.getJwksPath());
        discovery.put("introspection_endpoint", issuer + config.getIntrospectPath());
        discovery.put("revocation_endpoint", issuer + config.getRevokePath());
        discovery.put("end_session_endpoint", issuer + OidcProviderGenerator.LOGOUT_PATH);
        discovery.put("device_authorization_endpoint", issuer + config.getDeviceAuthorizationPath());
        // Only the response types /authorize actually implements are advertised. The hybrid and
        // implicit flows ("token", "id_token", "code token", "code id_token") were advertised but
        // rejected by /authorize, so a conformant client that picked one from this list failed.
        discovery.put("response_types_supported", Arrays.asList("code"));
        discovery.put("response_modes_supported", Arrays.asList("query"));
        discovery.put("grant_types_supported", Arrays.asList(
            "authorization_code", "client_credentials", "refresh_token",
            "urn:ietf:params:oauth:grant-type:device_code"
        ));
        discovery.put("code_challenge_methods_supported", Arrays.asList("S256", "plain"));
        discovery.put("token_endpoint_auth_methods_supported",
            Arrays.asList("client_secret_basic", "client_secret_post", "none"));
        discovery.put("id_token_signing_alg_values_supported", Arrays.asList(signingAlg));
        discovery.put("scopes_supported", config.getScopes());
        discovery.put("subject_types_supported", Arrays.asList("public"));
        discovery.put("claims_supported", Arrays.asList(
            "iss", "sub", "aud", "exp", "iat", "nbf", "nonce", "at_hash",
            "name", "preferred_username", "email", "email_verified", "scope"
        ));

        return response()
            .withStatusCode(200)
            .withHeader("content-type", APPLICATION_JSON)
            .withBody(serializeToJson(discovery));
    }

    private String serializeToJson(Object value) {
        try {
            return OBJECT_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC discovery document to JSON", e);
        }
    }
}
