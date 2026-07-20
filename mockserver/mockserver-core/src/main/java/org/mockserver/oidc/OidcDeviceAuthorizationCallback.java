package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC/OAuth2 device-authorization endpoint, implementing the OAuth 2.0 Device Authorization Grant
 * (RFC 8628 §3.2).
 *
 * <p>This is a mock — there is no device login UI. It issues an opaque {@code device_code} paired with a
 * short human-readable {@code user_code}, records them (with the requested {@code scope}) in
 * {@link OidcAuthorizationStore}, and returns the RFC 8628 device-authorization response:
 * {@code device_code, user_code, verification_uri, verification_uri_complete, expires_in, interval}.
 *
 * <p>The client then polls {@code /token} with
 * {@code grant_type=urn:ietf:params:oauth:grant-type:device_code} (handled by {@link OidcTokenCallback}),
 * which answers {@code authorization_pending} for the configured number of polls before minting tokens.
 */
public class OidcDeviceAuthorizationCallback implements ExpectationResponseCallback {

    /** RFC 8628 device-code grant type used when polling the token endpoint. */
    public static final String DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"; // unambiguous, no vowels
    private static final SecureRandom RANDOM = new SecureRandom();

    // ObjectWriter is immutable and thread-safe once configured, and the configuration here is
    // stateless (pretty=true, serialiseDefaultValues=false), so a single shared instance is reused
    // across all requests rather than re-created per callback construction.
    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);

    @Override
    public HttpResponse handle(HttpRequest request) {
        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();
        OidcAuthorizationStore.Provider provider =
            store.providerForDeviceAuthorizationPath(request.getPath().getValue());
        if (provider == null) {
            return response()
                .withStatusCode(404)
                .withHeader("content-type", JSON_CONTENT_TYPE)
                .withBody("{\"error\":\"server_error\",\"error_description\":\"no OIDC provider registered for this device endpoint\"}");
        }

        Map<String, String> form = parseFormBody(request.getBodyAsString());
        String scope = emptyToNull(form.get("scope"));

        String deviceCode = "mock-device-code-" + UUID.randomUUID();
        String userCode = generateUserCode();
        store.putDeviceCode(deviceCode, new OidcAuthorizationStore.DeviceCode(
            userCode, scope, Math.max(0, provider.config.getDeviceCodePendingPolls())));

        // Resolved per request from the Host header so the verification URI points at the address the
        // client actually reached, not a hardcoded one (see OidcIssuerResolver).
        String issuer = OidcIssuerResolver.resolve(provider.config, request);
        String verificationUri = issuer + "/device";
        String verificationUriComplete = verificationUri + "?user_code=" + userCode;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_code", deviceCode);
        body.put("user_code", userCode);
        body.put("verification_uri", verificationUri);
        body.put("verification_uri_complete", verificationUriComplete);
        body.put("expires_in", OidcAuthorizationStore.DEVICE_CODE_TTL_MILLIS / 1000L);
        body.put("interval", OidcAuthorizationStore.DEVICE_CODE_INTERVAL_SECONDS);

        return response()
            .withStatusCode(200)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withBody(serializeToJson(body));
    }

    private static String generateUserCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(USER_CODE_ALPHABET.charAt(RANDOM.nextInt(USER_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String serializeToJson(Object value) {
        try {
            return OBJECT_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC device-authorization response to JSON", e);
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                form.put(urlDecode(pair), "");
            } else {
                form.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return form;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
