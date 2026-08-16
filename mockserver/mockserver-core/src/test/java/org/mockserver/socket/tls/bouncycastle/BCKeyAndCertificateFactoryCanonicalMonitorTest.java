package org.mockserver.socket.tls.bouncycastle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.lang.reflect.Method;
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
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.socket.tls.PEMToFile.privateKeyFromPEMFile;
import static org.mockserver.socket.tls.PEMToFile.x509FromPEMFile;

/**
 * Proves that the in-JVM dynamic-CA generation monitor is keyed by the CANONICAL
 * directoryToSaveDynamicSSLCertificate (see {@link BCKeyAndCertificateFactory#certificateAuthorityGenerationMonitor()}),
 * so two {@link Configuration} instances that spell the SAME physical directory differently — here
 * {@code dir} versus {@code dir/.} — resolve to ONE monitor and therefore cannot both mint a fresh CA
 * concurrently. Both spellings map to the same {@code .mockserver-ca.lock}; if the monitor were keyed on
 * the merely-absolute path the two would hold DIFFERENT monitors, the second {@code FileChannel.lock()}
 * would throw {@code OverlappingFileLockException}, degrade to a no-op, and both threads would generate at
 * once — the torn-CA outcome this guard exists to prevent.
 *
 * <p>Degrade-and-confirm-red evidence: reverting the keying in {@code certificateAuthorityGenerationMonitor()}
 * / {@code acquireCertificateAuthorityLock()} to {@code getAbsolutePath()} makes
 * {@link #differentlySpelledDirectoriesShareOneGenerationMonitor()} fail deterministically (the two
 * monitors are distinct instances).
 */
public class BCKeyAndCertificateFactoryCanonicalMonitorTest {

    private static final int ITERATIONS = 60;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * Deterministic proof: two factories whose directories differ only in spelling ("dir" vs "dir/.")
     * return the very same monitor object. Fails without canonicalisation because getAbsolutePath()
     * preserves the trailing "/." and yields two distinct map keys.
     */
    @Test
    public void differentlySpelledDirectoriesShareOneGenerationMonitor() throws Exception {
        File directory = tempFolder.newFolder("shared-ca");
        String plainSpelling = directory.getAbsolutePath();
        String dottedSpelling = plainSpelling + File.separator + ".";

        BCKeyAndCertificateFactory factoryA = newDynamicFactory(plainSpelling);
        BCKeyAndCertificateFactory factoryB = newDynamicFactory(dottedSpelling);

        // sanity: the two spellings really are different strings but the same physical directory
        assertThat("the two spellings must differ as strings for this test to be meaningful",
            plainSpelling.equals(dottedSpelling), equalTo(false));

        Object monitorA = invokeGenerationMonitor(factoryA);
        Object monitorB = invokeGenerationMonitor(factoryB);

        assertThat("both factories must share ONE generation monitor for the same physical directory",
            monitorB, sameInstance(monitorA));
    }

    /**
     * Behavioural proof: race the two differently-spelled factories through the exact startup /
     * first-handshake paths the shipped bug exercised. With one shared monitor every iteration yields a
     * single, self-consistent CA and a verifiable leaf. Without canonicalisation the two monitors differ,
     * the OS lock overlaps and degrades to a no-op, and iterations intermittently mint a torn pair.
     */
    @Test
    public void concurrentGenerationAcrossDifferentSpellingsYieldsConsistentPair() throws Throwable {
        List<Throwable> allFailures = new ArrayList<>();

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            File directory = tempFolder.newFolder("race-ca-" + iteration);
            String plainSpelling = directory.getAbsolutePath();
            String dottedSpelling = plainSpelling + File.separator + ".";

            BCKeyAndCertificateFactory proxySetupFactory = newDynamicFactory(plainSpelling);
            BCKeyAndCertificateFactory handshakeFactory = newDynamicFactory(dottedSpelling);

            CyclicBarrier startLine = new CyclicBarrier(2);
            List<Throwable> threadFailures = new CopyOnWriteArrayList<>();

            Thread proxySetup = new Thread(() -> {
                try {
                    startLine.await();
                    proxySetupFactory.writeCertificateAuthorityToDisk();
                } catch (Throwable t) {
                    threadFailures.add(t);
                }
            }, "canonical-proxy-setup-" + iteration);

            Thread handshake = new Thread(() -> {
                try {
                    startLine.await();
                    handshakeFactory.buildAndSavePrivateKeyAndX509Certificate();
                } catch (Throwable t) {
                    threadFailures.add(t);
                }
            }, "canonical-handshake-" + iteration);

            proxySetup.start();
            handshake.start();
            proxySetup.join();
            handshake.join();

            if (!threadFailures.isEmpty()) {
                allFailures.addAll(threadFailures);
                continue;
            }

            try {
                assertConsistentCertificateAuthorityOnDisk(plainSpelling, proxySetupFactory, handshakeFactory);
            } catch (Throwable t) {
                allFailures.add(t);
            }
        }

        if (!allFailures.isEmpty()) {
            AssertionError summary = new AssertionError(
                "concurrent dynamic CA generation across differently-spelled directories produced a torn / "
                    + "inconsistent CA pair in " + allFailures.size() + " of " + ITERATIONS + " iterations; first failure below");
            summary.initCause(allFailures.get(0));
            throw summary;
        }
        assertThat("no iteration should have failed", allFailures, empty());
    }

    private Object invokeGenerationMonitor(BCKeyAndCertificateFactory factory) throws Exception {
        Method method = BCKeyAndCertificateFactory.class.getDeclaredMethod("certificateAuthorityGenerationMonitor");
        method.setAccessible(true);
        return method.invoke(factory);
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

        assertThat("both factories must observe the same CA certificate",
            proxySetupFactory.certificateAuthorityX509Certificate(), equalTo(caCertificateOnDisk));
        assertThat("both factories must observe the same CA certificate",
            handshakeFactory.certificateAuthorityX509Certificate(), equalTo(caCertificateOnDisk));

        X509Certificate leaf = handshakeFactory.x509Certificate();
        assertThat(leaf, notNullValue());
        leaf.verify(caCertificateOnDisk.getPublicKey());
    }
}
