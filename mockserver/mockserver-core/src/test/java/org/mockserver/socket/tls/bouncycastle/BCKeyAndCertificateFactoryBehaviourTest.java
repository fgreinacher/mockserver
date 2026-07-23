package org.mockserver.socket.tls.bouncycastle;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.io.FileWriter;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.socket.tls.KeyAndCertificateFactory.PROXY_SETUP_CA_CERTIFICATE_FILE_NAME;
import static org.mockserver.socket.tls.PEMToFile.certToPEM;
import static org.mockserver.socket.tls.PEMToFile.x509FromPEMFile;

public class BCKeyAndCertificateFactoryBehaviourTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Configuration configuration;
    private BCKeyAndCertificateFactory factory;

    @Before
    public void setUp() {
        configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(tempFolder.getRoot().getAbsolutePath());
        factory = new BCKeyAndCertificateFactory(configuration, new MockServerLogger());
    }

    // --- Key generation ---

    @Test
    public void shouldGenerateRSAPrivateKey() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        PrivateKey privateKey = factory.privateKey();
        assertThat(privateKey, notNullValue());
        assertThat(privateKey, instanceOf(RSAPrivateKey.class));
        assertThat(((RSAPrivateKey) privateKey).getModulus().bitLength(), greaterThanOrEqualTo(2048));
    }

    @Test
    public void shouldGenerateX509Certificate() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        assertThat(cert, notNullValue());
        assertThat(cert.getType(), equalTo("X.509"));
        assertThat(cert.getVersion(), equalTo(3));
    }

    @Test
    public void shouldGenerateCACertificate() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate caCert = factory.certificateAuthorityX509Certificate();
        assertThat(caCert, notNullValue());
        assertThat(caCert.getType(), equalTo("X.509"));
        // CA cert should have basicConstraints with CA=true
        assertThat(caCert.getBasicConstraints(), greaterThanOrEqualTo(0));
    }

    // --- SAN handling ---

    @Test
    public void shouldIncludeDefaultLocalhostInSAN() {
        // given - default configuration includes localhost as domain name
        configuration.sslCertificateDomainName("localhost");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> dnsNames = extractDnsNames(sans);
        assertThat(dnsNames, hasItem("localhost"));
    }

    @Test
    public void shouldIncludeCustomDomainNamesInSAN() {
        // given
        configuration.sslCertificateDomainName("myapp.example.com");
        configuration.sslSubjectAlternativeNameDomains("api.example.com", "www.example.com");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> dnsNames = extractDnsNames(sans);
        assertThat(dnsNames, hasItem("myapp.example.com"));
        assertThat(dnsNames, hasItem("api.example.com"));
        assertThat(dnsNames, hasItem("www.example.com"));
    }

    @Test
    public void shouldIncludeIPAddressesInSAN() {
        // given
        configuration.sslCertificateDomainName("localhost");
        configuration.sslSubjectAlternativeNameIps("127.0.0.1", "192.168.1.1");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> ipAddresses = extractIPAddresses(sans);
        assertThat(ipAddresses, hasItem("127.0.0.1"));
        assertThat(ipAddresses, hasItem("192.168.1.1"));
    }

    @Test
    public void shouldIncludeIPv6AddressesInSAN() throws Exception {
        // given - exercise the IPv6 branch of the SAN-IP handling alongside an IPv4 literal,
        // so this proves the IPv6 branch works AND that IPv4 still works in the same certificate
        configuration.sslCertificateDomainName("localhost");
        configuration.sslSubjectAlternativeNameIps("127.0.0.1", "::1", "2001:db8::1");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        // compare via InetAddress so the assertion is independent of the JDK's canonical
        // string form for IPv6 (e.g. "::1" -> "0:0:0:0:0:0:0:1") - equality is on address bytes
        Set<java.net.InetAddress> ipAddresses = extractIPAddresses(sans).stream()
            .map(ip -> {
                try {
                    return java.net.InetAddress.getByName(ip);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse SAN IP address: " + ip, e);
                }
            })
            .collect(Collectors.toSet());

        assertThat(ipAddresses, hasItem(java.net.InetAddress.getByName("::1")));
        assertThat(ipAddresses, hasItem(java.net.InetAddress.getByName("2001:db8::1")));
        // IPv4 still included in the same certificate
        assertThat(ipAddresses, hasItem(java.net.InetAddress.getByName("127.0.0.1")));
    }

    @Test
    public void shouldIncludeBothDomainsAndIPsInSAN() {
        // given
        configuration.sslCertificateDomainName("myhost.local");
        configuration.sslSubjectAlternativeNameDomains("extra.local");
        configuration.sslSubjectAlternativeNameIps("10.0.0.1");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        Collection<List<?>> sans = getSANs(cert);
        assertThat(sans, notNullValue());

        Set<String> dnsNames = extractDnsNames(sans);
        Set<String> ipAddresses = extractIPAddresses(sans);

        assertThat(dnsNames, hasItem("myhost.local"));
        assertThat(dnsNames, hasItem("extra.local"));
        assertThat(ipAddresses, hasItem("10.0.0.1"));
    }

    // --- Certificate chain ---

    @Test
    public void shouldReturnCertificateChainWithLeafAndCA() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        List<X509Certificate> chain = factory.certificateChain();
        assertThat(chain, notNullValue());
        assertThat(chain.size(), equalTo(2));
        // first is the leaf cert
        assertThat(chain.get(0), equalTo(factory.x509Certificate()));
        // second is the CA cert
        assertThat(chain.get(1), equalTo(factory.certificateAuthorityX509Certificate()));
    }

    @Test
    public void shouldNotDuplicateCAWhenCertificatePemAlreadyContainsChain() throws Exception {
        // given - generate a real CA + leaf using the dynamic factory
        factory.buildAndSavePrivateKeyAndX509Certificate();
        X509Certificate leaf = factory.x509Certificate();
        X509Certificate ca = factory.certificateAuthorityX509Certificate();

        // write a combined PEM that already ends with the CA (i.e. a full leaf+CA chain)
        File leafAndCaPem = tempFolder.newFile("leafAndCa.pem");
        try (FileWriter writer = new FileWriter(leafAndCaPem)) {
            writer.write(certToPEM(leaf, ca));
        }

        // configure a second factory that loads the supplied leaf+CA chain (no dynamic generation)
        // and whose CA matches the CA already appended to that chain - the leaf+CA chain branch is
        // only taken when both privateKeyPath and x509CertificatePath are non-blank; certificateChain()
        // never reads the private key file, so any non-blank path satisfies that branch condition
        Configuration suppliedChainConfiguration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .x509CertificatePath(leafAndCaPem.getAbsolutePath())
            .privateKeyPath(leafAndCaPem.getAbsolutePath())
            .certificateAuthorityCertificate(configuration.certificateAuthorityCertificate());
        BCKeyAndCertificateFactory suppliedChainFactory =
            new BCKeyAndCertificateFactory(suppliedChainConfiguration, new MockServerLogger());

        // when
        List<X509Certificate> chain = suppliedChainFactory.certificateChain();

        // then - the CA must not be duplicated: exactly [leaf, CA], not [leaf, CA, CA]
        assertThat(chain, notNullValue());
        assertThat(chain.size(), equalTo(2));
        assertThat(chain.get(0), equalTo(leaf));
        assertThat(chain.get(1), equalTo(ca));
    }

    // --- Leaf cert signed by CA ---

    @Test
    public void shouldSignLeafCertificateWithCA() throws Exception {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate leaf = factory.x509Certificate();
        X509Certificate ca = factory.certificateAuthorityX509Certificate();
        // verify leaf cert is signed by CA - this will throw if verification fails
        leaf.verify(ca.getPublicKey());
    }

    // --- Certificate not yet created check ---

    @Test
    public void shouldReportCertificateNotYetCreatedBeforeGeneration() {
        // when / then - before generating, certificate is not yet created
        assertTrue(factory.certificateNotYetCreated());
    }

    @Test
    public void shouldReportCertificateCreatedAfterGeneration() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        assertFalse(factory.certificateNotYetCreated());
    }

    // --- Subject DN ---

    @Test
    public void shouldSetCorrectSubjectDNForLeafCert() {
        // given
        configuration.sslCertificateDomainName("test.mock-server.com");

        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate cert = factory.x509Certificate();
        String subjectDN = cert.getSubjectX500Principal().getName();
        assertThat(subjectDN, org.hamcrest.Matchers.containsString("CN=test.mock-server.com"));
        assertThat(subjectDN, org.hamcrest.Matchers.containsString("O=MockServer"));
    }

    @Test
    public void shouldSetCorrectIssuerDNForCACert() {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate caCert = factory.certificateAuthorityX509Certificate();
        String issuerDN = caCert.getIssuerX500Principal().getName();
        assertThat(issuerDN, org.hamcrest.Matchers.containsString("CN=www.mockserver.com"));
        assertThat(issuerDN, org.hamcrest.Matchers.containsString("O=MockServer"));
    }

    // --- CA cert is self-signed ---

    @Test
    public void shouldGenerateSelfSignedCACert() throws Exception {
        // when
        factory.buildAndSavePrivateKeyAndX509Certificate();

        // then
        X509Certificate caCert = factory.certificateAuthorityX509Certificate();
        // self-signed means issuer = subject
        assertThat(caCert.getIssuerX500Principal(), equalTo(caCert.getSubjectX500Principal()));
        // and it can verify itself
        caCert.verify(caCert.getPublicKey());
    }

    // --- Materialising the active CA to a stable file (proxy setup) ---

    @Test
    public void shouldWriteDynamicCaToDiskWithStableFileName() throws Exception {
        // when - the dynamic CA is materialised to the stable mockserver-ca.pem filename
        String path = factory.writeCertificateAuthorityToDisk();

        // then
        assertThat(path, endsWith(PROXY_SETUP_CA_CERTIFICATE_FILE_NAME));
        File caFile = new File(path);
        assertTrue(caFile.exists());

        // the written file is the public CA certificate (never the private key)
        String pem = new String(java.nio.file.Files.readAllBytes(caFile.toPath()));
        assertThat(pem, containsString("BEGIN CERTIFICATE"));
        assertThat(pem, not(containsString("PRIVATE KEY")));

        // and it round-trips to the same certificate the factory serves
        X509Certificate written = x509FromPEMFile(path);
        assertThat(written, equalTo(factory.certificateAuthorityX509Certificate()));
    }

    @Test
    public void shouldWriteBakedInCaToDiskWithStableFileName() throws Exception {
        // given - a factory using the baked-in (public) CA, no dynamic generation
        Configuration bakedInConfiguration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(false)
            .certificateAuthorityCertificate(org.mockserver.configuration.ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE)
            .directoryToSaveDynamicSSLCertificate(tempFolder.getRoot().getAbsolutePath());
        BCKeyAndCertificateFactory bakedInFactory =
            new BCKeyAndCertificateFactory(bakedInConfiguration, new MockServerLogger());

        // when
        String path = bakedInFactory.writeCertificateAuthorityToDisk();

        // then - the written file matches the baked-in CA certificate loaded from the classpath
        assertThat(path, endsWith(PROXY_SETUP_CA_CERTIFICATE_FILE_NAME));
        assertTrue(new File(path).exists());
        X509Certificate written = x509FromPEMFile(path);
        assertThat(written, equalTo(bakedInFactory.certificateAuthorityX509Certificate()));
    }

    // --- Helper methods ---

    private Collection<List<?>> getSANs(X509Certificate cert) {
        try {
            return cert.getSubjectAlternativeNames();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get SANs from certificate", e);
        }
    }

    /**
     * DNS names have GeneralName type 2
     */
    private Set<String> extractDnsNames(Collection<List<?>> sans) {
        return sans.stream()
            .filter(san -> Integer.valueOf(2).equals(san.get(0)))
            .map(san -> (String) san.get(1))
            .collect(Collectors.toSet());
    }

    /**
     * IP addresses have GeneralName type 7
     */
    private Set<String> extractIPAddresses(Collection<List<?>> sans) {
        return sans.stream()
            .filter(san -> Integer.valueOf(7).equals(san.get(0)))
            .map(san -> (String) san.get(1))
            .collect(Collectors.toSet());
    }
}
