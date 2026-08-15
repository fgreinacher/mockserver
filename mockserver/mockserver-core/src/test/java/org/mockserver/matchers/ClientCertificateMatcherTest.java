package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.mock.Expectation;
import org.mockserver.model.ClientCertificate;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.model.X509Certificate;
import org.mockserver.socket.tls.PEMToFile;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.ClientCertificate.clientCertificate;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.NottableString.not;

/**
 * Focused tests for matching an expectation on the mutual-TLS client-certificate chain a request was
 * received with.
 *
 * @author jamesdbloom
 */
public class ClientCertificateMatcherTest {

    // fingerprint (SHA-256) of the test leaf certificate, lowercase hex with no separators.
    // Deliberately a literal rather than derived from the fixture, so these tests pin an exact expected
    // value instead of re-deriving whatever the production code happens to produce. It therefore goes
    // stale whenever the fixture is renewed - shouldHaveFingerprintConstantMatchingCheckedInFixture()
    // below turns that into an explicit instruction rather than a confusing "no match" failure.
    private static final String LEAF_FINGERPRINT = "fee41885fa2af13087841e38553624aa376560160ff0bd6b4fa461fae8c69b83";

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(ClientCertificateMatcherTest.class);

    private HttpRequestPropertiesMatcher matcher(RequestDefinition requestDefinition) {
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(new Expectation(requestDefinition));
        return httpRequestPropertiesMatcher;
    }

    /**
     * Build a request carrying the real test leaf certificate chain (subject/issuer DN, SANs and DER
     * bytes populated), exactly as the Netty TLS handshake path does.
     */
    /**
     * Guard against a renewed certificate fixture silently invalidating {@link #LEAF_FINGERPRINT}.
     * Renewing the mtls fixtures re-mints leaf-cert.pem, which changes its fingerprint and would
     * otherwise make every fingerprint assertion below fail as an unexplained mismatch. This fails
     * first and names the replacement value.
     */
    @Test
    public void shouldHaveFingerprintConstantMatchingCheckedInFixture() throws Exception {
        java.security.cert.X509Certificate leafCertificate =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem").get(0);
        StringBuilder actualFingerprint = new StringBuilder();
        for (byte encodedByte : java.security.MessageDigest.getInstance("SHA-256").digest(leafCertificate.getEncoded())) {
            actualFingerprint.append(String.format("%02x", encodedByte));
        }

        assertThat(
            "org/mockserver/authentication/mtls/leaf-cert.pem has been renewed - update LEAF_FINGERPRINT to "
                + actualFingerprint + " (see .opencode/skills/renew-test-certs)",
            actualFingerprint.toString(),
            is(LEAF_FINGERPRINT)
        );
    }

    private HttpRequest requestWithRealLeafCertificate() {
        return new JDKCertificateToMockServerX509Certificate(mockServerLogger).setClientCertificates(
            request(),
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem").toArray(new java.security.cert.X509Certificate[0])
        );
    }

    /**
     * Build a request whose leaf certificate is a bare model {@link X509Certificate} with only the
     * distinguished names populated (no underlying certificate / DER bytes) — useful for CN / DN
     * matching without a real certificate on the classpath.
     */
    private HttpRequest requestWithLeafDistinguishedNames(String subjectDn, String issuerDn) {
        List<X509Certificate> chain = Collections.singletonList(
            X509Certificate.x509Certificate()
                .withSubjectDistinguishedName(subjectDn)
                .withIssuerDistinguishedName(issuerDn)
        );
        return request().withClientCertificateChain(chain);
    }

    // MATCH BY SUBJECT

    @Test
    public void shouldMatchBySubjectCommonName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject("my-client")))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client,OU=test,O=MockServer", "CN=My CA")), is(true));
    }

    @Test
    public void shouldMatchBySubjectAlternativeName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject("example.com")))
            .matches(null, requestWithRealLeafCertificate()), is(true));
    }

    @Test
    public void shouldMatchBySubjectRegexCommonName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject("my-cl.*")))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client,OU=test,O=MockServer", "CN=My CA")), is(true));
    }

    @Test
    public void shouldNotMatchByDifferentSubjectCommonName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject("other-client")))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client,OU=test,O=MockServer", "CN=My CA")), is(false));
    }

    @Test
    public void shouldMatchByNegatedSubjectCommonName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject(not("other-client"))))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client,OU=test,O=MockServer", "CN=My CA")), is(true));
    }

    @Test
    public void shouldNotMatchNegatedSubjectWhenCommonNameEquals() {
        // De Morgan: "!my-client" must NOT match a certificate whose CN is my-client, even though the
        // full subject DN candidate differs from the bare value
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject(not("my-client"))))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client,OU=test,O=MockServer", "CN=My CA")), is(false));
    }

    @Test
    public void shouldNotMatchNegatedSubjectWhenSubjectAlternativeNameEquals() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject(not("example.com"))))
            .matches(null, requestWithRealLeafCertificate()), is(false));
    }

    // MATCH BY ISSUER

    @Test
    public void shouldMatchByIssuerCommonName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withIssuer("My CA")))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client", "CN=My CA,O=MockServer")), is(true));
    }

    @Test
    public void shouldMatchByIssuerRegexDistinguishedName() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withIssuer(".*MockServer.*")))
            .matches(null, requestWithRealLeafCertificate()), is(true));
    }

    @Test
    public void shouldNotMatchByDifferentIssuer() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withIssuer("Some Other CA")))
            .matches(null, requestWithLeafDistinguishedNames("CN=my-client", "CN=My CA,O=MockServer")), is(false));
    }

    // MATCH BY FINGERPRINT

    @Test
    public void shouldMatchByFingerprint() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withFingerprintSha256(LEAF_FINGERPRINT)))
            .matches(null, requestWithRealLeafCertificate()), is(true));
    }

    /**
     * The value under test here is the <em>formatting</em> (colon-separated, upper case), not the
     * fingerprint itself - so it is derived from {@link #LEAF_FINGERPRINT} rather than written out a
     * second time. A second literal would silently go stale on the next certificate renewal.
     */
    @Test
    public void shouldMatchByFingerprintWithColonsAndUpperCase() {
        StringBuilder colonSeparatedUpperCase = new StringBuilder();
        for (int i = 0; i < LEAF_FINGERPRINT.length(); i += 2) {
            if (i > 0) {
                colonSeparatedUpperCase.append(':');
            }
            colonSeparatedUpperCase.append(LEAF_FINGERPRINT, i, i + 2);
        }

        assertThat(matcher(request().withClientCertificate(clientCertificate().withFingerprintSha256(
                colonSeparatedUpperCase.toString().toUpperCase())))
            .matches(null, requestWithRealLeafCertificate()), is(true));
    }

    @Test
    public void shouldNotMatchByDifferentFingerprint() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withFingerprintSha256(
                "0000000000000000000000000000000000000000000000000000000000000000")))
            .matches(null, requestWithRealLeafCertificate()), is(false));
    }

    // COMBINED CRITERIA

    @Test
    public void shouldMatchByCombinedSubjectAndFingerprint() {
        assertThat(matcher(request().withClientCertificate(clientCertificate()
                .withSubject("example.com")
                .withFingerprintSha256(LEAF_FINGERPRINT)))
            .matches(null, requestWithRealLeafCertificate()), is(true));
    }

    @Test
    public void shouldNotMatchWhenOneCombinedCriterionFails() {
        assertThat(matcher(request().withClientCertificate(clientCertificate()
                .withSubject("example.com")
                .withFingerprintSha256("0000000000000000000000000000000000000000000000000000000000000000")))
            .matches(null, requestWithRealLeafCertificate()), is(false));
    }

    // NO CERTIFICATE CHAIN PRESENT

    @Test
    public void shouldNotMatchWhenRequestHasNoCertificateChain() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withSubject("my-client")))
            .matches(null, request()), is(false));
    }

    @Test
    public void shouldNotMatchFingerprintWhenRequestHasNoCertificateChain() {
        assertThat(matcher(request().withClientCertificate(clientCertificate().withFingerprintSha256(LEAF_FINGERPRINT)))
            .matches(null, request()), is(false));
    }

    // BLANK / ABSENT CRITERIA ARE NON-BREAKING

    @Test
    public void shouldMatchWhenNoClientCertificateCriterionSpecified() {
        assertThat(matcher(request().withPath("/some/path"))
            .matches(null, requestWithRealLeafCertificate().withPath("/some/path")), is(true));
    }

    @Test
    public void shouldMatchWhenBlankClientCertificateCriterionAgainstRequestWithNoCertificate() {
        assertThat(matcher(request().withClientCertificate(clientCertificate()))
            .matches(null, request()), is(true));
    }
}
