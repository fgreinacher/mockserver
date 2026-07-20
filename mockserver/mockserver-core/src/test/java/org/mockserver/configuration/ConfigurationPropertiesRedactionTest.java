package org.mockserver.configuration;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockserver.configuration.ConfigurationProperties.REDACTED_VALUE;
import static org.mockserver.configuration.ConfigurationProperties.isSensitivePropertyName;

/**
 * Verifies that sensitive property names are detected and their values
 * are redacted in property-file log dumps.
 */
public class ConfigurationPropertiesRedactionTest {

    // --- isSensitivePropertyName: positive cases ---

    @Test
    public void shouldDetectPasswordProperty() {
        assertThat(isSensitivePropertyName("mockserver.forwardProxyAuthenticationPassword"), is(true));
        assertThat(isSensitivePropertyName("mockserver.proxyAuthenticationPassword"), is(true));
    }

    @Test
    public void shouldDetectSecretProperty() {
        assertThat(isSensitivePropertyName("mockserver.blobStoreSecretAccessKey"), is(true));
    }

    @Test
    public void shouldDetectAccessKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.blobStoreAccessKeyId"), is(true));
    }

    @Test
    public void shouldDetectApiKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.llmApiKey"), is(true));
    }

    @Test
    public void shouldDetectConnectionStringProperty() {
        assertThat(isSensitivePropertyName("mockserver.blobStoreConnectionString"), is(true));
    }

    @Test
    public void shouldDetectPrivateKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.forwardProxyPrivateKey"), is(true));
        assertThat(isSensitivePropertyName("mockserver.certificateAuthorityPrivateKey"), is(true));
        assertThat(isSensitivePropertyName("mockserver.privateKeyPath"), is(true));
        assertThat(isSensitivePropertyName("mockserver.controlPlanePrivateKeyPath"), is(true));
    }

    @Test
    public void shouldDetectTokenProperty() {
        assertThat(isSensitivePropertyName("mockserver.authToken"), is(true));
    }

    @Test
    public void shouldDetectCredentialProperty() {
        assertThat(isSensitivePropertyName("mockserver.serviceCredential"), is(true));
    }

    @Test
    public void shouldDetectPassphraseProperty() {
        assertThat(isSensitivePropertyName("mockserver.keyPassphrase"), is(true));
    }

    @Test
    public void shouldDetectAccessUnderscoreKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_access_key_id"), is(true));
    }

    @Test
    public void shouldDetectApiUnderscoreKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_api_key"), is(true));
    }

    @Test
    public void shouldDetectConnectionUnderscoreStringProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_connection_string"), is(true));
    }

    @Test
    public void shouldDetectPrivateUnderscoreKeyProperty() {
        assertThat(isSensitivePropertyName("mockserver.some_private_key"), is(true));
    }

    // --- isSensitivePropertyName: works without mockserver. prefix ---

    @Test
    public void shouldDetectSensitiveWithoutPrefix() {
        assertThat(isSensitivePropertyName("llmApiKey"), is(true));
        assertThat(isSensitivePropertyName("blobStoreSecretAccessKey"), is(true));
        assertThat(isSensitivePropertyName("proxyAuthenticationPassword"), is(true));
    }

    // --- isSensitivePropertyName: case insensitive ---

    @Test
    public void shouldBeCaseInsensitive() {
        assertThat(isSensitivePropertyName("mockserver.LLMAPIKEY"), is(true));
        assertThat(isSensitivePropertyName("mockserver.BlobStoreSecretAccessKey"), is(true));
        assertThat(isSensitivePropertyName("MOCKSERVER.PASSWORD"), is(true));
    }

    // --- isSensitivePropertyName: negative cases ---

    @Test
    public void shouldNotFlagNonSensitiveProperties() {
        assertThat(isSensitivePropertyName("mockserver.logLevel"), is(false));
        assertThat(isSensitivePropertyName("mockserver.maxExpectations"), is(false));
        assertThat(isSensitivePropertyName("mockserver.nioEventLoopThreadCount"), is(false));
        assertThat(isSensitivePropertyName("mockserver.blobStoreBucket"), is(false));
        assertThat(isSensitivePropertyName("mockserver.blobStoreRegion"), is(false));
        assertThat(isSensitivePropertyName("mockserver.forwardProxyAuthenticationUsername"), is(false));
        assertThat(isSensitivePropertyName("mockserver.enableCORSForAPI"), is(false));
    }

    @Test
    public void shouldHandleNull() {
        assertThat(isSensitivePropertyName(null), is(false));
    }

    @Test
    public void shouldHandleEmptyString() {
        assertThat(isSensitivePropertyName(""), is(false));
    }

    @Test
    public void shouldDetectDashboardAnalyticsKey() {
        // regression: the 13-substring list omitted bare "key", so this property — a credential sent to
        // the analytics endpoint — matched nothing and its value was logged in clear
        assertThat(isSensitivePropertyName("mockserver.dashboardAnalyticsKey"), is(true));
    }

    @Test
    public void shouldNotFlagPoolSizingPropertiesEndingInKey() {
        // the "ends in key" rule must not catch connection-pool sizing limits, where "key" means the
        // pool key rather than a secret
        assertThat(isSensitivePropertyName("mockserver.forwardConnectionPoolMaxIdlePerKey"), is(false));
        assertThat(isSensitivePropertyName("mockserver.forwardConnectionPoolMaxTotalPerKey"), is(false));
    }

    // --- reflection-driven guard over the REAL property surface ---
    //
    // The hand-picked cases above can only assert what someone thought to write down, which is exactly
    // how dashboardAnalyticsKey slipped through: it was never in the list. This guard instead enumerates
    // every property name ConfigurationProperties actually declares and asserts that each
    // credential-SHAPED name is redacted. A future property called anything ...Key / ...Token /
    // ...Secret / ...Password fails this test unless it is either redacted or explicitly documented as a
    // non-credential, so this class of leak cannot recur silently.

    /**
     * Property names (prefix stripped, lower-cased) that LOOK credential-shaped to the heuristic below
     * but genuinely are not secrets. Each must be justified; adding to this set is a deliberate act.
     */
    private static final Set<String> KNOWN_NON_CREDENTIALS = new HashSet<>(Arrays.asList(
        // pool sizing limits — "key" is the pool key, not a secret
        "forwardconnectionpoolmaxidleperkey",
        "forwardconnectionpoolmaxtotalperkey",
        // booleans that merely NAME the redaction/credential feature rather than carrying a credential
        "corsallowcredentials",
        "redactsecretsinlog",
        "redactsecretsinrecordedexpectations",
        // header NAME (not the value) for API-key data-plane authentication
        "dataplaneapikeyauthenticationheader"
    ));

    /**
     * The test's OWN independent notion of "this name looks like it carries a credential", deliberately
     * not sharing code with the production predicate — a guard that reuses the implementation it guards
     * cannot detect that implementation being wrong.
     */
    private static boolean looksLikeCredential(String bareLowerName) {
        return bareLowerName.endsWith("key")
            || bareLowerName.endsWith("token")
            || bareLowerName.endsWith("secret")
            || bareLowerName.endsWith("password")
            || bareLowerName.endsWith("passphrase")
            || bareLowerName.endsWith("credential")
            || bareLowerName.endsWith("credentials")
            || bareLowerName.contains("apikey")
            || bareLowerName.contains("accesskey")
            || bareLowerName.contains("privatekey")
            || bareLowerName.contains("connectionstring");
    }

    /**
     * Every {@code mockserver.*} property name declared by ConfigurationProperties, read reflectively from
     * its static final String constants — the real surface, not a hand-maintained sample.
     */
    private static List<String> declaredPropertyNames() throws Exception {
        List<String> names = new ArrayList<>();
        for (Field field : ConfigurationProperties.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && field.getType().equals(String.class)
                && !field.isSynthetic()) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String && ((String) value).startsWith("mockserver.")) {
                    names.add((String) value);
                }
            }
        }
        return names;
    }

    @Test
    public void shouldRedactEveryCredentialShapedPropertyOnTheRealSurface() throws Exception {
        List<String> propertyNames = declaredPropertyNames();

        // sanity: the guard must actually be enumerating a large surface, so a regression that stops
        // discovering property constants cannot pass vacuously
        assertThat("reflection should discover the real property surface",
            propertyNames.size(), greaterThan(100));

        List<String> unredacted = new ArrayList<>();
        for (String propertyName : propertyNames) {
            String bare = propertyName.substring("mockserver.".length()).toLowerCase(Locale.ROOT);
            if (KNOWN_NON_CREDENTIALS.contains(bare)) {
                continue;
            }
            if (looksLikeCredential(bare) && !isSensitivePropertyName(propertyName)) {
                unredacted.add(propertyName);
            }
        }

        assertThat("credential-shaped configuration properties whose values are NOT redacted in log "
                + "dumps — add the name shape to ConfigurationProperties.isSensitivePropertyName, or add "
                + "the property to KNOWN_NON_CREDENTIALS with a justification if it carries no secret: "
                + unredacted,
            unredacted, is(empty()));
    }

    // --- redaction constant ---

    @Test
    public void redactedValueShouldBeStars() {
        assertThat(REDACTED_VALUE, is("***REDACTED***"));
    }
}
