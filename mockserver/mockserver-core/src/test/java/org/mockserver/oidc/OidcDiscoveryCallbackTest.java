package org.mockserver.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Conformance tests for the mock OIDC discovery document.
 *
 * <p>OIDC Discovery §4.3 requires the advertised {@code issuer} to be identical to the URL the relying
 * party fetched the document from. Every conformant client library validates this, so a hardcoded
 * issuer breaks the single most common way people run a mock OIDC provider: a Testcontainers-mapped
 * random port.
 */
public class OidcDiscoveryCallbackTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OidcDiscoveryCallback discovery = new OidcDiscoveryCallback();

    @Before
    @After
    public void resetStore() {
        OidcAuthorizationStore.getInstance().reset();
    }

    @Test
    public void shouldDeriveIssuerFromHostHeaderSoRandomPortsWork() {
        new OidcProviderGenerator().generate(new OidcProviderConfiguration());

        // The port a Testcontainers-mapped mock actually listens on is not known when the provider is
        // configured, so the issuer cannot be baked in at generate time.
        JsonNode document = discover("localhost:49173", null);

        assertThat(document.path("issuer").asText(), is("http://localhost:49173"));
        assertThat(document.path("token_endpoint").asText(), is("http://localhost:49173/token"));
        assertThat(document.path("jwks_uri").asText(), is("http://localhost:49173/.well-known/jwks.json"));
    }

    @Test
    public void shouldDeriveIssuerPerRequestNotOncePerProvider() {
        new OidcProviderGenerator().generate(new OidcProviderConfiguration());

        assertThat(discover("first.example:8080", null).path("issuer").asText(),
            is("http://first.example:8080"));
        assertThat(discover("second.example:9090", null).path("issuer").asText(),
            is("http://second.example:9090"));
    }

    @Test
    public void shouldHonourExplicitlyConfiguredIssuer() {
        new OidcProviderGenerator().generate(
            new OidcProviderConfiguration().setIssuer("https://idp.test"));

        // An explicit issuer always wins, so a test needing a stable external issuer can pin one.
        assertThat(discover("localhost:49173", null).path("issuer").asText(), is("https://idp.test"));
    }

    @Test
    public void shouldUseForwardedProtoWhenBehindATlsTerminatingProxy() {
        new OidcProviderGenerator().generate(new OidcProviderConfiguration());

        assertThat(discover("idp.example.com", "https").path("issuer").asText(),
            is("https://idp.example.com"));
    }

    @Test
    public void shouldIgnoreNonHttpForwardedProtoSchemes() {
        new OidcProviderGenerator().generate(new OidcProviderConfiguration());

        // X-Forwarded-Proto is client-controlled and is spliced into the issuer URL, which a
        // browser-based relying party may navigate to. Only http/https are legitimate issuer schemes;
        // anything else must fall back to how the request actually arrived rather than being echoed.
        for (String hostile : new String[]{"javascript", "data", "file", "JAVASCRIPT", "ht tp", "https evil"}) {
            String issuer = discover("idp.example.com", hostile).path("issuer").asText();
            assertThat("must not echo a non-http(s) forwarded scheme: " + hostile,
                issuer, is("http://idp.example.com"));
        }

        // the legitimate values are still honoured
        assertThat(discover("idp.example.com", "https").path("issuer").asText(),
            is("https://idp.example.com"));
        assertThat(discover("idp.example.com", "http").path("issuer").asText(),
            is("http://idp.example.com"));
    }

    @Test
    public void shouldOnlyAdvertiseResponseTypesAuthorizeActuallyImplements() {
        new OidcProviderGenerator().generate(new OidcProviderConfiguration());

        JsonNode responseTypes = discover("localhost:1080", null).path("response_types_supported");
        // /authorize implements the authorization-code flow only. Advertising the implicit and hybrid
        // flows meant a conformant client could pick one from this list and then be rejected.
        assertThat(responseTypes.size(), is(1));
        assertThat(responseTypes.get(0).asText(), is("code"));
    }

    @Test
    public void shouldProduceDocumentParseableByIndependentOidcSdk() throws Exception {
        new OidcProviderGenerator().generate(new OidcProviderConfiguration());

        HttpResponse response = discoverResponse("localhost:49173", null);

        // Parsed and validated by the nimbus OIDC SDK — the same code path Spring Security and pac4j
        // use — rather than by MockServer's own model objects.
        OIDCProviderMetadata metadata = OIDCProviderMetadata.parse(response.getBodyAsString());

        assertThat(metadata.getIssuer().getValue(), is("http://localhost:49173"));
        assertThat(metadata.getTokenEndpointURI().toString(), is("http://localhost:49173/token"));
        assertThat(metadata.getJWKSetURI().toString(), is("http://localhost:49173/.well-known/jwks.json"));
        assertThat(metadata.getResponseTypes(), contains(new ResponseType("code")));
        assertThat(metadata.getSubjectTypes(), is(not(empty())));
    }

    // --- helpers ---

    private JsonNode discover(String host, String forwardedProto) {
        try {
            return OBJECT_MAPPER.readTree(discoverResponse(host, forwardedProto).getBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse discoverResponse(String host, String forwardedProto) {
        org.mockserver.model.HttpRequest httpRequest = request()
            .withMethod("GET")
            .withPath("/.well-known/openid-configuration")
            .withHeader("host", host);
        if (forwardedProto != null) {
            httpRequest = httpRequest.withHeader("x-forwarded-proto", forwardedProto);
        }
        return (HttpResponse) discovery.handle(httpRequest);
    }
}
