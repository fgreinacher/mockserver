package org.mockserver.socket.tls.bouncycastle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.lang.reflect.Field;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Verifies that {@link BCKeyAndCertificateFactory} defers registration of the BouncyCastle JCE
 * provider off the startup path: constructing the factory must NOT register the provider (so a
 * MockServer that never uses TLS never pays the ~460-class BouncyCastle load), and the first actual
 * key/cert operation must register it lazily and still succeed.
 *
 * <p>This test mutates JVM-global {@link java.security.Security} provider state (it removes the "BC"
 * provider and resets the factory's static registration guard), so it MUST run in the sequential
 * Surefire phase — it is listed in BOTH the parallel {@code <excludes>} and the sequential
 * {@code <includes>} of {@code mockserver-core/pom.xml}. It restores the original provider state and
 * guard flag in {@link #tearDown()} so subsequent sequential tests are unaffected.
 */
public class BCKeyAndCertificateFactoryLazyProviderTest {

    private static final String PROVIDER_NAME = "BC";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Provider originalProvider;

    @Before
    public void setUp() throws Exception {
        // Remember whether BC was already registered so we can restore it afterwards.
        originalProvider = Security.getProvider(PROVIDER_NAME);
        // Force a clean slate: remove the provider and reset the factory's registration guard so the
        // lazy path is exercised from scratch regardless of what earlier tests loaded.
        Security.removeProvider(PROVIDER_NAME);
        setProviderRegisteredFlag(false);
        assertThat("precondition: BC provider must be unregistered before the test",
            Security.getProvider(PROVIDER_NAME), is(nullValue()));
    }

    @After
    public void tearDown() throws Exception {
        // Restore the original global state so we do not disturb other sequential tests: re-register
        // BC if it was present when we started, and mark the guard as registered to match.
        if (originalProvider != null && Security.getProvider(PROVIDER_NAME) == null) {
            Security.addProvider(originalProvider);
        }
        setProviderRegisteredFlag(Security.getProvider(PROVIDER_NAME) != null);
    }

    @Test
    public void shouldNotRegisterProviderOnConstructionButRegisterOnFirstOperation() {
        // given
        Configuration configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(tempFolder.getRoot().getAbsolutePath());

        // when - the factory is constructed
        BCKeyAndCertificateFactory factory = new BCKeyAndCertificateFactory(configuration, new MockServerLogger());

        // then - construction alone must NOT register the BouncyCastle provider (lazy startup)
        assertThat("constructing the factory must not eagerly register the BouncyCastle provider",
            Security.getProvider(PROVIDER_NAME), is(nullValue()));

        // when - the first key/cert operation runs
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then - the provider is now registered, and the operation produced a valid certificate
        assertThat("first key/cert operation must lazily register the BouncyCastle provider",
            Security.getProvider(PROVIDER_NAME), is(notNullValue()));
        X509Certificate certificate = factory.x509Certificate();
        assertThat(certificate, is(notNullValue()));
        assertThat(certificate.getType(), equalTo("X.509"));
    }

    private static void setProviderRegisteredFlag(boolean value) throws Exception {
        Field field = BCKeyAndCertificateFactory.class.getDeclaredField("providerRegistered");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }
}
