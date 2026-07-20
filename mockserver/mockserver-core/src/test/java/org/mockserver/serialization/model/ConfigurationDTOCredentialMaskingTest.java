package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ConfigurationSerializer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Security guard for WRITE-ONLY credential configuration properties.
 *
 * <p>These properties are fully settable — over the {@code Configuration} instance and over
 * {@code PUT /mockserver/configuration} — but must never be readable: {@code GET /mockserver/configuration}
 * returns {@link ConfigurationProperties#REDACTED_VALUE} in their place.
 *
 * <p>Three distinct failures are asserted, because masking a secret is easy to get half-right:
 * <ol>
 *   <li><strong>Leak</strong> — the real secret appearing anywhere in the serialized configuration.</li>
 *   <li><strong>Round-trip destruction</strong> — a client doing GET-then-PUT of the whole blob writing
 *       the literal mask back over a working credential. This is the classic bug in the masking pattern:
 *       the secret does not leak, it is silently destroyed instead.</li>
 *   <li><strong>Broken functionality</strong> — masking that also blocks the write path, leaving the
 *       credential unsettable.</li>
 * </ol>
 *
 * <p>Enumeration is reflective over {@link #WRITE_ONLY_CREDENTIALS} so adding a credential to the list
 * without masking it fails here, and masking one without listing it fails in
 * {@link ConfigurationEnforcementClassificationTest#shouldKeepTheWriteOnlyCredentialListInSyncWithTheMaskingGuard()}.
 */
public class ConfigurationDTOCredentialMaskingTest {

    /**
     * Every configuration property that is settable but must be masked on read. Kept in sync with the
     * copy in {@link ConfigurationEnforcementClassificationTest} by a test in that class.
     */
    static final Set<String> WRITE_ONLY_CREDENTIALS = new LinkedHashSet<>(Arrays.asList(
        "llmApiKey",
        "prometheusRemoteWriteBearerToken",
        "prometheusRemoteWriteBasicAuthPassword"
    ));

    private static final String REAL_SECRET_PREFIX = "sk-real-secret-do-not-leak-";

    private final ConfigurationSerializer serializer = new ConfigurationSerializer(new MockServerLogger());

    // ---------------------------------------------------------------------------------------------
    // 1. the real secret must never reach the wire
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotSerializeAnyRealCredentialValue() throws Exception {
        Configuration configuration = configurationWithEveryCredentialSet();

        String json = serializer.serialize(configuration);

        List<String> leaked = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            if (json.contains(realSecretFor(credential))) {
                leaked.add(credential);
            }
        }

        assertThat("write-only credentials whose REAL value appears in the serialized configuration — "
                + "GET /mockserver/configuration would hand the secret to any reader: " + leaked,
            leaked, is(empty()));
    }

    @Test
    public void shouldSerializeTheSharedRedactionMaskInPlaceOfEachCredential() throws Exception {
        Configuration configuration = configurationWithEveryCredentialSet();

        String json = serializer.serialize(configuration);

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            assertThat("credential " + credential + " should be present in the configuration JSON",
                json, containsString(credential));
        }
        assertThat("the mask must be the shared ConfigurationProperties.REDACTED_VALUE, not a locally "
                + "invented token", json, containsString(ConfigurationProperties.REDACTED_VALUE));
    }

    @Test
    public void shouldMaskEachCredentialOnTheDtoGetterThatJacksonSerializes() throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO(configurationWithEveryCredentialSet());

        List<String> unmasked = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = dtoGetter(credential).invoke(dto);
            if (!ConfigurationProperties.REDACTED_VALUE.equals(value)) {
                unmasked.add(credential + " (getter returned <" + value + ">)");
            }
        }

        assertThat("write-only credential getters that did NOT return the redaction mask — Jackson "
                + "serializes exactly these getters, so an unmasked one is a live secret leak: " + unmasked,
            unmasked, is(empty()));
    }

    @Test
    public void shouldNotAdvertiseAMaskForACredentialThatIsNotSet() throws Exception {
        // an unset credential must serialize as absent, not as "***REDACTED***" — otherwise the config
        // dump claims a secret exists where none does, and a GET-then-PUT would look like a real write
        Configuration configuration = configuration();
        ConfigurationDTO dto = new ConfigurationDTO(configuration);

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object effective = configurationGetter(credential).invoke(configuration);
            if (effective != null && !"".equals(effective)) {
                // the ambient environment (a property file / env var / system property) supplies a real
                // value for this credential, so "unset" cannot be observed here — masking it IS correct
                continue;
            }
            assertThat("unset credential " + credential + " should mask to null, not to the mask literal",
                dtoGetter(credential).invoke(dto), is(nullValue()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. the round-trip guard: a masked GET echoed back by a PUT must not destroy the credential
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotOverwriteRealCredentialsWhenAMaskedConfigurationIsAppliedBack() throws Exception {
        Configuration live = configurationWithEveryCredentialSet();

        // GET: serialize the live configuration — every credential comes back masked
        String maskedJson = serializer.serialize(live);
        assertThat(maskedJson, containsString(ConfigurationProperties.REDACTED_VALUE));

        // PUT: apply that very same body straight back, exactly as a GET-then-PUT client would
        applyJsonTo(maskedJson, live);

        List<String> destroyed = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(live);
            if (!realSecretFor(credential).equals(value)) {
                destroyed.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("write-only credentials clobbered by applying a masked configuration body back — the "
                + "secret did not leak, it was silently DESTROYED, which breaks outbound auth: " + destroyed,
            destroyed, is(empty()));
    }

    @Test
    public void shouldNotBuildAConfigurationCarryingTheMaskLiteralAsACredential() throws Exception {
        // the buildObject() path (deserialize of a masked body) must likewise refuse the mask literal
        Configuration live = configurationWithEveryCredentialSet();
        String maskedJson = serializer.serialize(live);

        Configuration rebuilt = serializer.deserialize(maskedJson);

        List<String> poisoned = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(rebuilt);
            if (ConfigurationProperties.REDACTED_VALUE.equals(value)) {
                poisoned.add(credential);
            }
        }

        assertThat("configuration rebuilt from a masked body carries the literal mask as a credential — "
                + "outbound auth would send \"" + ConfigurationProperties.REDACTED_VALUE + "\": " + poisoned,
            poisoned, is(empty()));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. masking must not break the write path
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldStillApplyRealCredentialValuesSuppliedOverTheControlPlane() throws Exception {
        Configuration target = configuration();

        // a PUT body carrying REAL credential values (not the mask) must be applied in full
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(credential).append("\":\"").append(realSecretFor(credential)).append("\"");
            first = false;
        }
        json.append("}");

        applyJsonTo(json.toString(), target);

        List<String> notApplied = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(target);
            if (!realSecretFor(credential).equals(value)) {
                notApplied.add(credential + " (was <" + value + ">)");
            }
        }

        assertThat("write-only credentials NOT applied from a control-plane body carrying real values — "
                + "masking has broken the write path, leaving them unsettable: " + notApplied,
            notApplied, is(empty()));
    }

    @Test
    public void shouldHonourCredentialsSetDirectlyOnTheConfigurationInstance() throws Exception {
        Configuration configuration = configurationWithEveryCredentialSet();

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            assertThat("credential " + credential + " set on the Configuration instance should be readable "
                    + "in-process (masking is a wire concern, not an in-process one)",
                configuration.getClass().getMethod(credential).invoke(configuration),
                is(realSecretFor(credential)));
        }
    }

    @Test
    public void shouldExposeRealCredentialValuesOnlyThroughJsonIgnoredRawAccessors() throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO(configurationWithEveryCredentialSet());

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Method raw = ConfigurationDTO.class.getMethod("get"
                + Character.toUpperCase(credential.charAt(0)) + credential.substring(1) + "RawValue");
            assertThat("the raw accessor must return the real value so the functional paths keep working",
                raw.invoke(dto), is(realSecretFor(credential)));
            assertThat("the raw accessor must be @JsonIgnore-d so it cannot leak through serialization",
                raw.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class), is(not(nullValue())));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. KNOWN GAP — value-embedded credentials are NOT masked. This test PINS the current
    //    (unmasked, disclosed-on-read) behaviour so it is visible and cannot silently regress to
    //    "assumed masked". It is a characterisation test of a documented gap, not an endorsement:
    //    when per-field/per-header redaction lands, flip these assertions to expect the mask and
    //    move the two properties into WRITE_ONLY_CREDENTIALS. See docs/plans and the KNOWN GAP note
    //    on ConfigurationEnforcementClassificationTest.WRITE_ONLY_CREDENTIALS.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void embeddedValueCredentialsAreNotYetMaskedOnRead() throws Exception {
        Configuration configuration = configuration()
            // a secret nested inside a JSON backends document
            .llmBackendsConfig("[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "llmBackends\"}]")
            // a secret nested inside an arbitrary header list
            .prometheusRemoteWriteHeaders("Authorization=Bearer " + REAL_SECRET_PREFIX + "promHeaders");

        String json = serializer.serialize(configuration);

        // whole-property-name masking does not reach a secret embedded in a value, so GET discloses
        // both in clear. Asserting the leak explicitly is what makes the gap impossible to overlook.
        assertThat("EXPECTED (documented gap): the apiKey embedded in llmBackendsConfig is disclosed by "
                + "GET /mockserver/configuration — if this now fails because the value is masked, delete "
                + "this assertion and add llmBackendsConfig to WRITE_ONLY_CREDENTIALS",
            json, containsString(REAL_SECRET_PREFIX + "llmBackends"));
        assertThat("EXPECTED (documented gap): the credential embedded in prometheusRemoteWriteHeaders is "
                + "disclosed by GET /mockserver/configuration — if this now fails because the value is "
                + "masked, delete this assertion and add prometheusRemoteWriteHeaders to WRITE_ONLY_CREDENTIALS",
            json, containsString(REAL_SECRET_PREFIX + "promHeaders"));
    }

    // ---------------------------------------------------------------------------------------------

    private Configuration configurationWithEveryCredentialSet() throws Exception {
        Configuration configuration = configuration();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Configuration.class
                .getMethod(credential, String.class)
                .invoke(configuration, realSecretFor(credential));
        }
        return configuration;
    }

    /**
     * Apply a JSON configuration body onto a target exactly as the control plane does: bind it to a
     * {@link ConfigurationDTO} and call {@code applyTo}.
     */
    private void applyJsonTo(String json, Configuration target) throws Exception {
        ConfigurationDTO dto = org.mockserver.serialization.ObjectMapperFactory
            .createObjectMapper()
            .readValue(json, ConfigurationDTO.class);
        dto.applyTo(target);
    }

    private static String realSecretFor(String credential) {
        return REAL_SECRET_PREFIX + credential;
    }

    private static Method configurationGetter(String credential) throws Exception {
        return Configuration.class.getMethod(credential);
    }

    private static Method dtoGetter(String credential) throws Exception {
        return ConfigurationDTO.class.getMethod("get"
            + Character.toUpperCase(credential.charAt(0)) + credential.substring(1));
    }
}
