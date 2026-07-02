package org.mockserver.socket.tls;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Verifies that the http2Enabled configuration property controls whether HTTP/2 is advertised via
 * ALPN - the lever that lets a user force HTTP/2 capable clients to fall back to HTTP/1.1 (#2260).
 * Also covers error paths for invalid TLS configuration.
 */
public class NettySslContextFactoryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void shouldAdvertiseHttp2ViaAlpnByDefault() {
        // given
        SslContext serverSslContext = new NettySslContextFactory(configuration(), new MockServerLogger(), true).createServerSslContext();

        // then
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), hasItem(ApplicationProtocolNames.HTTP_2));
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), hasItem(ApplicationProtocolNames.HTTP_1_1));
    }

    @Test
    public void shouldNotAdvertiseHttp2ViaAlpnWhenHttp2Disabled() {
        // given
        Configuration configuration = configuration().http2Enabled(false);

        // when
        SslContext serverSslContext = new NettySslContextFactory(configuration, new MockServerLogger(), true).createServerSslContext();

        // then - only http/1.1 is advertised so HTTP/2 capable clients negotiate HTTP/1.1
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), not(hasItem(ApplicationProtocolNames.HTTP_2)));
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), hasItem(ApplicationProtocolNames.HTTP_1_1));
    }

    @Test
    public void shouldNotAdvertiseHttp2ViaAlpnOnClientContextWhenHttp2Disabled() {
        // given
        Configuration configuration = configuration().http2Enabled(false);

        // when
        SslContext clientSslContext = new NettySslContextFactory(configuration, new MockServerLogger(), false).createClientSslContext(true, true);

        // then - even when the caller requests HTTP/2 the disabled property strips h2 from ALPN
        assertThat(clientSslContext.applicationProtocolNegotiator().protocols(), not(hasItem(ApplicationProtocolNames.HTTP_2)));
    }

    // ---- error-path tests ----

    @Test
    public void shouldThrowWhenServerSslContextCreatedWithInvalidPrivateKey() throws IOException {
        // given — valid cert but invalid key content
        File keyFile = createTempPemFile("key.pem", "not a valid pem key");
        File certFile = createTempPemFile("cert.pem", "not a valid pem cert");
        Configuration configuration = configuration();
        configuration.privateKeyPath(keyFile.getAbsolutePath());
        configuration.x509CertificatePath(certFile.getAbsolutePath());

        // when / then — createServerSslContext wraps the exception with descriptive message
        try {
            new NettySslContextFactory(configuration, new MockServerLogger(), true).createServerSslContext();
            fail("expected RuntimeException for invalid PEM files");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Exception creating SSL context for server"));
            assertThat(e.getMessage(), containsString(keyFile.getAbsolutePath()));
        }
    }

    @Test
    public void shouldIncludeCertificatePathInServerSslContextError() throws IOException {
        // given — invalid PEM content
        File keyFile = createTempPemFile("key.pem", "invalid key content");
        File certFile = createTempPemFile("cert.pem", "invalid cert content");
        Configuration configuration = configuration();
        configuration.privateKeyPath(keyFile.getAbsolutePath());
        configuration.x509CertificatePath(certFile.getAbsolutePath());

        // when / then — error message includes the configured paths for diagnostics
        try {
            new NettySslContextFactory(configuration, new MockServerLogger(), true).createServerSslContext();
            fail("expected RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("privateKeyPath=\"" + keyFile.getAbsolutePath() + "\""));
            assertThat(e.getMessage(), containsString("x509CertificatePath=\"" + certFile.getAbsolutePath() + "\""));
        }
    }

    @Test
    public void shouldThrowWhenClientSslContextCreatedWithInvalidForwardProxyKey() throws IOException {
        // given — point forwardProxyPrivateKey to a file with garbage content
        File keyFile = createTempPemFile("proxy-key.pem", "not a real private key");
        Configuration configuration = configuration();
        configuration.forwardProxyPrivateKey(keyFile.getAbsolutePath());

        // when / then — createClientSslContext wraps with descriptive message
        try {
            new NettySslContextFactory(configuration, new MockServerLogger(), false).createClientSslContext(true, false);
            fail("expected RuntimeException for invalid forward proxy key");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Exception creating SSL context for client"));
        }
    }

    // ---- per-host outbound mTLS cert/key resolver ----

    @Test
    public void shouldResolveNullPerHostCertificateForBlankHostOrMapping() {
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate("h=/c.pem;/k.pem", null), is(nullValue()));
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate("h=/c.pem;/k.pem", ""), is(nullValue()));
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate("", "h"), is(nullValue()));
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate(null, "h"), is(nullValue()));
    }

    @Test
    public void shouldResolvePerHostCertificateForMatchingHost() {
        String[] result = NettySslContextFactory.resolveForwardProxyClientCertificate("api.internal=/certs/c.pem;/certs/k.pem", "api.internal");
        assertThat(result, is(new String[]{"/certs/c.pem", "/certs/k.pem"}));
    }

    @Test
    public void shouldMatchHostCaseInsensitivelyAndTrimWhitespace() {
        String[] result = NettySslContextFactory.resolveForwardProxyClientCertificate("  API.Internal = /certs/c.pem ; /certs/k.pem  ", "api.internal");
        assertThat(result, is(new String[]{"/certs/c.pem", "/certs/k.pem"}));
    }

    @Test
    public void shouldReturnNullWhenNoHostMatches() {
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate("a=/c.pem;/k.pem", "b"), is(nullValue()));
    }

    @Test
    public void shouldSelectCorrectEntryFromMultipleAndSkipMalformedEntries() {
        String mapping = "noequals,blankcert==;/k.pem,nosemicolon=/only.pem,a=/ac.pem;/ak.pem,b=/bc.pem;/bk.pem";
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate(mapping, "a"), is(new String[]{"/ac.pem", "/ak.pem"}));
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate(mapping, "b"), is(new String[]{"/bc.pem", "/bk.pem"}));
        assertThat(NettySslContextFactory.resolveForwardProxyClientCertificate(mapping, "nosemicolon"), is(nullValue()));
    }

    // ---- per-host outbound mTLS context selection & caching ----

    @Test
    public void shouldShareClientSslContextAcrossUnmappedHosts() {
        // given — no per-host mapping configured
        NettySslContextFactory factory = new NettySslContextFactory(configuration(), new MockServerLogger(), false);

        // when — two different upstream hosts, neither mapped
        SslContext contextA = factory.createClientSslContext(true, false, "a.example.com");
        SslContext contextB = factory.createClientSslContext(true, false, "b.example.com");

        // then — both share the single global client context (cache does not grow per host)
        assertThat(contextA, sameInstance(contextB));
    }

    @Test
    public void shouldSelectPerHostForwardProxyCertificateOnlyForMappedHost() throws IOException {
        // given — a per-host mapping whose cert/key point at garbage PEM content
        File certFile = createTempPemFile("host-cert.pem", "not a real certificate");
        File keyFile = createTempPemFile("host-key.pem", "not a real private key");
        Configuration configuration = configuration()
            .forwardProxyClientCertificatesByHost("secure.host=" + certFile.getAbsolutePath() + ";" + keyFile.getAbsolutePath());
        NettySslContextFactory factory = new NettySslContextFactory(configuration, new MockServerLogger(), false);

        // when / then — the mapped host selects the (garbage) per-host key and fails to build,
        // proving per-host selection engaged
        try {
            factory.createClientSslContext(true, false, "secure.host");
            fail("expected RuntimeException for invalid per-host forward proxy key");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Exception creating SSL context for client"));
        }

        // and an unmapped host falls back to the valid global/default pair and builds successfully
        assertThat(factory.createClientSslContext(true, false, "unmapped.host"), is(notNullValue()));
    }

    private File createTempPemFile(String name, String content) throws IOException {
        File file = tempFolder.newFile(name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }
}
