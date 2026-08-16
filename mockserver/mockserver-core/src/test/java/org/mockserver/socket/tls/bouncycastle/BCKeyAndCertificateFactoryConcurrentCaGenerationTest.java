package org.mockserver.socket.tls.bouncycastle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.socket.tls.PEMToFile.privateKeyFromPEMFile;
import static org.mockserver.socket.tls.PEMToFile.x509FromPEMFile;

/**
 * Proves the fix for the dynamic-CA generation race: two threads, each holding its OWN
 * {@link BCKeyAndCertificateFactory} but pointed at the SAME directoryToSaveDynamicSSLCertificate,
 * used to be able to both mint a fresh CA and interleave the private-key / certificate writes,
 * publishing a torn pair (leaf signed with one generation's CA key, verified against the other's CA
 * public key). That surfaced as {@code SignatureException: certificate does not verify with supplied
 * key} on the leaf build — and, once the mismatched CA was memoised, on every subsequent handshake.
 *
 * <p>Each iteration races the exact two paths the shipped bug exercised:
 * <ul>
 *   <li>the proxy-setup / startup path — {@link BCKeyAndCertificateFactory#writeCertificateAuthorityToDisk()};</li>
 *   <li>the first-handshake path — {@link BCKeyAndCertificateFactory#buildAndSavePrivateKeyAndX509Certificate()}.</li>
 * </ul>
 * With generation serialised in-JVM, every iteration must yield exactly ONE consistent CA on disk and
 * a leaf that verifies against it. The test loops so an intermittent race cannot pass by luck.
 */
public class BCKeyAndCertificateFactoryConcurrentCaGenerationTest {

    private static final int ITERATIONS = 60;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void concurrentDynamicCaGenerationYieldsConsistentKeyAndCertificatePair() throws Throwable {
        List<Throwable> allFailures = new ArrayList<>();

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            File directory = tempFolder.newFolder("ca-" + iteration);
            String directoryPath = directory.getAbsolutePath();

            // two independent factories with independent Configuration instances, exactly as the
            // startup thread and the SNI handshake thread each have in a running MockServer
            BCKeyAndCertificateFactory proxySetupFactory = newDynamicFactory(directoryPath);
            BCKeyAndCertificateFactory handshakeFactory = newDynamicFactory(directoryPath);

            CyclicBarrier startLine = new CyclicBarrier(2);
            List<Throwable> threadFailures = new CopyOnWriteArrayList<>();

            Thread proxySetup = new Thread(() -> {
                try {
                    startLine.await();
                    proxySetupFactory.writeCertificateAuthorityToDisk();
                } catch (Throwable t) {
                    threadFailures.add(t);
                }
            }, "proxy-setup-" + iteration);

            Thread handshake = new Thread(() -> {
                try {
                    startLine.await();
                    // internally verifies the freshly built leaf against the CA public key loaded from
                    // disk (BCKeyAndCertificateFactory.generateLeafCert) — a torn pair throws here
                    handshakeFactory.buildAndSavePrivateKeyAndX509Certificate();
                } catch (Throwable t) {
                    threadFailures.add(t);
                }
            }, "handshake-" + iteration);

            proxySetup.start();
            handshake.start();
            proxySetup.join();
            handshake.join();

            if (!threadFailures.isEmpty()) {
                allFailures.addAll(threadFailures);
                continue;
            }

            // both threads succeeded — assert a single, self-consistent CA on disk and a verifiable leaf
            try {
                assertConsistentCertificateAuthorityOnDisk(directoryPath, proxySetupFactory, handshakeFactory);
            } catch (Throwable t) {
                allFailures.add(t);
            }
        }

        if (!allFailures.isEmpty()) {
            AssertionError summary = new AssertionError(
                "concurrent dynamic CA generation produced a torn / inconsistent CA pair in "
                    + allFailures.size() + " of " + ITERATIONS + " iterations; first failure below");
            summary.initCause(allFailures.get(0));
            throw summary;
        }
        assertThat("no iteration should have failed", allFailures, empty());
    }

    private BCKeyAndCertificateFactory newDynamicFactory(String directoryPath) {
        Configuration configuration = configuration()
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(directoryPath);
        return new BCKeyAndCertificateFactory(configuration, new MockServerLogger());
    }

    private void assertConsistentCertificateAuthorityOnDisk(String directoryPath,
                                                            BCKeyAndCertificateFactory proxySetupFactory,
                                                            BCKeyAndCertificateFactory handshakeFactory) throws Exception {
        X509Certificate caCertificateOnDisk = x509FromPEMFile(new File(directoryPath, "CertificateAuthorityCertificate.pem").getAbsolutePath());
        PrivateKey caPrivateKeyOnDisk = privateKeyFromPEMFile(new File(directoryPath, "PKCS8CertificateAuthorityPrivateKey.pem").getAbsolutePath());
        assertThat(caCertificateOnDisk, notNullValue());
        assertThat(caPrivateKeyOnDisk, notNullValue());

        // 1. both factories agree on the same CA certificate — only one generation was published
        assertThat("both factories must observe the same CA certificate",
            proxySetupFactory.certificateAuthorityX509Certificate(), equalTo(caCertificateOnDisk));
        assertThat("both factories must observe the same CA certificate",
            handshakeFactory.certificateAuthorityX509Certificate(), equalTo(caCertificateOnDisk));

        // 2. the on-disk CA private key and CA certificate are a matched pair: the leaf minted by the
        //    handshake factory verifies against the CA certificate on disk (throws on a torn pair)
        X509Certificate leaf = handshakeFactory.x509Certificate();
        assertThat(leaf, notNullValue());
        leaf.verify(caCertificateOnDisk.getPublicKey());
    }
}
