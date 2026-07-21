package org.mockserver.serialization.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ConfigurationSerializer;
import org.mockserver.serialization.ObjectMapperFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * {@code PUT /mockserver/configuration} — but their credential content must never be readable:
 * {@code GET /mockserver/configuration} returns {@link ConfigurationProperties#REDACTED_VALUE} in
 * its place.
 *
 * <p>Two shapes are covered, and both are enumerated from {@link #WRITE_ONLY_CREDENTIALS}:
 * <ul>
 *   <li><strong>whole-value</strong> credentials, whose entire value is the secret, mask to the bare
 *       {@link ConfigurationProperties#REDACTED_VALUE};</li>
 *   <li><strong>value-embedded</strong> credentials, which carry a secret inside a structured value
 *       (a {@code k=v} header list, a JSON document), mask <em>per header / per field</em> — the
 *       surrounding, non-secret configuration must survive intact.</li>
 * </ul>
 *
 * <p>Four distinct failures are asserted, because masking a secret is easy to get half-right:
 * <ol>
 *   <li><strong>Leak</strong> — the real secret appearing anywhere in the serialized configuration.</li>
 *   <li><strong>Over-masking</strong> — a partial mask swallowing the non-secret configuration around
 *       it, so an operator can no longer read back the headers/backends they configured.</li>
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
     * Every configuration property that is settable but whose credential content must be masked on
     * read. Kept in sync with the copy in {@link ConfigurationEnforcementClassificationTest} by a test
     * in that class.
     */
    static final Set<String> WRITE_ONLY_CREDENTIALS = new LinkedHashSet<>(Arrays.asList(
        "llmApiKey",
        "llmBackendsConfig",
        "prometheusRemoteWriteBearerToken",
        "prometheusRemoteWriteBasicAuthPassword",
        "prometheusRemoteWriteHeaders"
    ));

    private static final String REAL_SECRET_PREFIX = "sk-real-secret-do-not-leak-";

    private static final String MASK = ConfigurationProperties.REDACTED_VALUE;

    /**
     * The value-embedded credentials, each mapped to a realistic structured value that carries BOTH a
     * secret and ordinary configuration, and to the exact form {@code GET} must return for it. Asserting
     * the exact masked form (rather than "contains the mask") is what pins partial masking: an
     * implementation that redacted the whole value, dropped the other headers, or reordered the
     * document would fail.
     */
    private static final Map<String, String> EMBEDDED_REAL_VALUES = new LinkedHashMap<>();
    private static final Map<String, String> EMBEDDED_MASKED_VALUES = new LinkedHashMap<>();

    static {
        EMBEDDED_REAL_VALUES.put("llmBackendsConfig",
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "llmBackendsConfig\"},"
                + "{\"name\":\"ollama\",\"provider\":\"OLLAMA\",\"baseUrl\":\"http://localhost:11434\"}]");
        EMBEDDED_MASKED_VALUES.put("llmBackendsConfig",
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + MASK + "\"},"
                + "{\"name\":\"ollama\",\"provider\":\"OLLAMA\",\"baseUrl\":\"http://localhost:11434\"}]");
        EMBEDDED_REAL_VALUES.put("prometheusRemoteWriteHeaders",
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "prometheusRemoteWriteHeaders,X-Scope-OrgID=tenant-a");
        EMBEDDED_MASKED_VALUES.put("prometheusRemoteWriteHeaders",
            "Authorization=" + MASK + ",X-Scope-OrgID=tenant-a");
    }

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

        assertThat("write-only credentials whose REAL secret appears in the serialized configuration — "
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
                + "invented token", json, containsString(MASK));
    }

    @Test
    public void shouldMaskEachCredentialOnTheDtoGetterThatJacksonSerializes() throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO(configurationWithEveryCredentialSet());

        List<String> wrong = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = dtoGetter(credential).invoke(dto);
            if (!expectedMaskFor(credential).equals(value)) {
                wrong.add(credential + " (getter returned <" + value + "> but should return <"
                    + expectedMaskFor(credential) + ">)");
            }
        }

        assertThat("write-only credential getters that did NOT return the expected masked form — Jackson "
                + "serializes exactly these getters, so an unmasked one is a live secret leak and an "
                + "over-masked one destroys readable configuration: " + wrong,
            wrong, is(empty()));
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
            Object masked = dtoGetter(credential).invoke(dto);
            if (isEmbeddedValueCredential(credential)) {
                assertThat("an unset structured value carries no embedded secret, so " + credential
                        + " must serialize unchanged rather than claim a secret exists", masked, is(effective));
            } else {
                assertThat("unset credential " + credential + " should mask to null, not to the mask literal",
                    masked, is(nullValue()));
            }
        }
    }

    @Test
    public void embeddedValueCredentialsAreRedactedFieldByFieldOnRead() throws Exception {
        Configuration configuration = configuration()
            .llmBackendsConfig(EMBEDDED_REAL_VALUES.get("llmBackendsConfig"))
            .prometheusRemoteWriteHeaders(EMBEDDED_REAL_VALUES.get("prometheusRemoteWriteHeaders"));

        String json = serializer.serialize(configuration);

        assertThat("the apiKey embedded in llmBackendsConfig must not be disclosed by GET /mockserver/configuration",
            json, not(containsString(REAL_SECRET_PREFIX + "llmBackendsConfig")));
        assertThat("the credential embedded in prometheusRemoteWriteHeaders must not be disclosed by "
                + "GET /mockserver/configuration", json, not(containsString(REAL_SECRET_PREFIX + "prometheusRemoteWriteHeaders")));

        // only the secret is removed — the rest of each value is ordinary configuration an operator
        // must still be able to read back
        assertThat("the non-secret backends configuration must survive redaction",
            json, containsString("http://localhost:11434"));
        assertThat("headers that are not credentials must survive redaction",
            json, containsString("X-Scope-OrgID=tenant-a"));
    }

    @Test
    public void shouldNotCorruptTheStoredValueWhenMaskingEmbeddedSecretsOnRead() throws Exception {
        // redaction is a READ-side transform: it rewrites the value handed to the reader and must never
        // write the masked form back onto the Configuration it read from. The round-trip test below
        // would still pass if it did (it re-reads through the same masking getter), so assert the
        // stored value directly.
        Configuration configuration = configuration()
            .llmBackendsConfig(EMBEDDED_REAL_VALUES.get("llmBackendsConfig"))
            .prometheusRemoteWriteHeaders(EMBEDDED_REAL_VALUES.get("prometheusRemoteWriteHeaders"));

        serializer.serialize(configuration);

        assertThat("serializing must not mutate the llmBackendsConfig held by the Configuration",
            configuration.llmBackendsConfig(), is(EMBEDDED_REAL_VALUES.get("llmBackendsConfig")));
        assertThat("serializing must not mutate the prometheusRemoteWriteHeaders held by the Configuration",
            configuration.prometheusRemoteWriteHeaders(), is(EMBEDDED_REAL_VALUES.get("prometheusRemoteWriteHeaders")));
        assertThat("the real backend key must still be usable in-process after a read",
            configuration.llmBackendsConfig(), containsString(REAL_SECRET_PREFIX + "llmBackendsConfig"));
        assertThat("the real header credential must still be usable in-process after a read",
            configuration.prometheusRemoteWriteHeaders(), containsString(REAL_SECRET_PREFIX + "prometheusRemoteWriteHeaders"));
    }

    // ---------------------------------------------------------------------------------------------
    // 2. the round-trip guard: a masked GET echoed back by a PUT must not destroy the credential
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotOverwriteRealCredentialsWhenAMaskedConfigurationIsAppliedBack() throws Exception {
        Configuration live = configurationWithEveryCredentialSet();

        // GET: serialize the live configuration — every credential comes back masked
        String maskedJson = serializer.serialize(live);
        assertThat(maskedJson, containsString(MASK));

        // PUT: apply that very same body straight back, exactly as a GET-then-PUT client would
        applyJsonTo(maskedJson, live);

        List<String> destroyed = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(live);
            if (!realValueFor(credential).equals(value)) {
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
            if (value != null && String.valueOf(value).contains(MASK)) {
                poisoned.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("configuration rebuilt from a masked body carries the literal mask as a credential — "
                + "outbound auth would send \"" + MASK + "\": " + poisoned,
            poisoned, is(empty()));
    }

    // ---------------------------------------------------------------------------------------------
    // 2b. the mixed case: only SOME fields of a structured value are masked. The masked ones must keep
    //     their real values and the edited ones must actually be written — getting either half wrong
    //     is invisible until outbound auth breaks or an edit is silently ignored.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldRestoreOnlyTheMaskedHeaderAndStillApplyTheEditedOnes() throws Exception {
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-Scope-OrgID=tenant-a");

        // the operator edited one header and added another, leaving the masked one exactly as GET showed it
        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Authorization=" + MASK
            + ",X-Scope-OrgID=tenant-b,X-Custom=abc\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            is("Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-Scope-OrgID=tenant-b,X-Custom=abc"));
    }

    @Test
    public void shouldRefuseTheWholeListWhenAMaskedHeaderHasNoRealValueHeld() throws Exception {
        // dropping just the unresolvable header (what this did before) writes a list whose credential is
        // simply GONE — the mask does not leak, but outbound auth breaks and the PUT answers 200 OK.
        // Refusing the whole value keeps whatever is held and tells the operator.
        Configuration live = configuration().prometheusRemoteWriteHeaders("");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",X-Tenant=acme\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is(""));
    }

    @Test
    public void shouldResolveAMaskedHeaderWhoseNameTheOperatorReCased() throws Exception {
        // HTTP header names are case-insensitive, so re-casing one is a legitimate edit. Looking the
        // held value up case-sensitively found nothing, and the header — the credential — was dropped
        // from a list that was then written, with no warning.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=2");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"authorization=" + MASK + ",X-B=3\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            containsString(REAL_SECRET_PREFIX + "held"));
        assertThat(live.prometheusRemoteWriteHeaders(),
            is("authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=3"));
    }

    @Test
    public void shouldKeepBothCredentialsWhenTwoHeldHeaderNamesDifferOnlyInCase() throws Exception {
        // the consumer (PrometheusRemoteWriteExporter#parseHeaders) is case-SENSITIVE and applies its
        // result additively, so these are two headers and both are sent. Folding them together to
        // resolve the mask case-insensitively would restore one credential onto both names and destroy
        // the other — silently, with the PUT answering 200 OK.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"X-Api-Key=" + MASK + ",x-api-key=" + MASK
            + ",X-Env=prod\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            is("X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B,X-Env=prod"));
        assertThat("the credential held under the upper-cased name must survive",
            live.prometheusRemoteWriteHeaders(), containsString(REAL_SECRET_PREFIX + "A"));
    }

    @Test
    public void shouldResolveEachCaseSpellingSeparatelyWhenOnlyOneOfThemIsSentBack() throws Exception {
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"x-api-key=" + MASK + ",X-Env=prod\"}", live);

        assertThat("the exact name must select ITS value, not the other spelling's",
            live.prometheusRemoteWriteHeaders(), is("x-api-key=" + REAL_SECRET_PREFIX + "B,X-Env=prod"));
    }

    @Test
    public void shouldRefuseWhenAMaskedHeaderNameMatchesTwoHeldNamesDifferingOnlyInCase() throws Exception {
        String held = "X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B";
        Configuration live = configuration().prometheusRemoteWriteHeaders(held);

        // a THIRD casing matches neither exactly, and the mask could stand for either held value
        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"X-API-KEY=" + MASK + ",X-Env=prod\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is(held));
    }

    @Test
    public void shouldRefuseWhenTwoIncomingMaskedHeadersWouldResolveToTheSameHeldValue() throws Exception {
        // the inverse: only one spelling is held, so a case-insensitive fallback would hand the SAME
        // credential to both names — fabricating a second credential header the operator never had
        String held = "X-Api-Key=" + REAL_SECRET_PREFIX + "A";
        Configuration live = configuration().prometheusRemoteWriteHeaders(held);

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"X-Api-Key=" + MASK + ",x-api-key=" + MASK + "\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is(held));
    }

    @Test
    public void shouldRefuseANewCredentialTypedOverTheMaskRatherThanWeldItToTheOldOne() throws Exception {
        // "***REDACTED***-my-new-key" reads as an operator typing a new credential over the mask.
        // Splitting it as "mask + appended text" persists OLD-REAL-my-new-key — neither the credential
        // that was held nor the one that was intended.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Api-Key=" + REAL_SECRET_PREFIX + "held");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + "-my-new-key\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is("Api-Key=" + REAL_SECRET_PREFIX + "held"));
        assertThat(live.prometheusRemoteWriteHeaders(), not(containsString("my-new-key")));
        assertThat(live.prometheusRemoteWriteHeaders(), not(containsString(MASK)));
    }

    @Test
    public void shouldRestoreOnlyTheMaskedBackendApiKeyAndStillApplyTheEditedOnes() throws Exception {
        Configuration live = configuration().llmBackendsConfig(
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"},"
                + "{\"name\":\"azure\",\"provider\":\"OPENAI\",\"apiKey\":\"old-azure-key\"}]");

        // openai's key was only ever seen masked; azure's is rotated to a real new value
        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"},{\\\"name\\\":\\\"azure\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"new-azure-key\\\"}]\"}", live);

        assertThat("the masked backend key must keep its held value and the rotated one must be applied",
            live.llmBackendsConfig(),
            is("[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"},"
                + "{\"name\":\"azure\",\"provider\":\"OPENAI\",\"apiKey\":\"new-azure-key\"}]"));
    }

    @Test
    public void shouldLeaveTheHeldHeaderListUntouchedWhenNoMaskedHeaderCanBeResolved() throws Exception {
        // every incoming header is masked and none is held, so nothing survives the merge. Writing the
        // empty result would PIN "" on the instance, whose getter then prefers it over the static
        // store — silently suppressing a property-file or environment value. Leave it unset instead.
        Configuration live = configuration();

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",Authorization=" + MASK + "\"}", live);

        assertThat(instanceField(live, "prometheusRemoteWriteHeaders"), is(nullValue()));
    }

    @Test
    public void shouldRestoreTheCredentialTheConsumerWouldActuallyHaveUsedWhenAHeaderNameRepeats() throws Exception {
        // PrometheusRemoteWriteExporter#parseHeaders is last-wins, so "second" is the live credential;
        // restoring "first" would leave the secret un-leaked but the outbound auth silently wrong
        Configuration live = configuration().prometheusRemoteWriteHeaders("Api-Key=first,Api-Key=second");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",X-Tenant=acme\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is("Api-Key=second,X-Tenant=acme"));
    }

    @Test
    public void shouldMaskTheWholeOfAHeaderValueContainingAComma() throws Exception {
        // the k=v,k2=v2 format cannot escape a comma, so the tail reads as a separate entry — masking
        // only up to the comma would publish the rest of the secret
        String headers = "Authorization=Bearer " + REAL_SECRET_PREFIX + "head,TAIL-OF-THE-SECRET,X-Tenant=acme";
        Configuration live = configuration().prometheusRemoteWriteHeaders(headers);

        assertThat(new ConfigurationDTO(live).getPrometheusRemoteWriteHeaders(),
            is("Authorization=" + MASK + ",X-Tenant=acme"));
        assertThat(serializer.serialize(live), not(containsString("TAIL-OF-THE-SECRET")));

        // and the whole value, comma included, survives an unedited round trip
        applyJsonTo(serializer.serialize(live), live);
        assertThat(live.prometheusRemoteWriteHeaders(), is(headers));
    }

    @Test
    public void shouldNotWriteTheMaskAsAHeaderValueWhenAMaskedHeaderCarriesACommaTail() throws Exception {
        // an =-less segment is read as the tail of the preceding header value (the format cannot escape
        // a comma), so the incoming entry reads as Api-Key=***REDACTED***,junk — which is NOT equal to
        // the mask. Writing it verbatim would destroy the real key AND make the literal mask the
        // outbound credential: outbound auth breaks and a fake secret is persisted.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Api-Key=" + REAL_SECRET_PREFIX + "held,X-B=2");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",junk,X-B=2\"}", live);

        assertThat("the real credential must survive a masked header carrying a comma tail",
            live.prometheusRemoteWriteHeaders(), containsString(REAL_SECRET_PREFIX + "held"));
        assertThat("the literal mask must never be persisted as the outbound credential",
            live.prometheusRemoteWriteHeaders(), not(containsString(MASK)));
        assertThat(live.prometheusRemoteWriteHeaders(),
            is("Api-Key=" + REAL_SECRET_PREFIX + "held,junk,X-B=2"));
    }

    @Test
    public void shouldLeaveTheHeldHeaderListUntouchedWhenAMaskIsBuriedInsideAHeaderValue() throws Exception {
        // "Bearer ***REDACTED***" is not a value redaction ever produced (a masked header value is
        // replaced WHOLE), so there is nothing it can be resolved against. Writing it would persist the
        // mask; dropping just that header would destroy the credential — leave the held value alone.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=2");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Authorization=Bearer " + MASK + ",X-B=3\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            is("Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=2"));
        assertThat(live.prometheusRemoteWriteHeaders(), not(containsString(MASK)));
    }

    @Test
    public void shouldLeaveTheHeldBackendsDocumentUntouchedWhenAFieldMerelyContainsTheMask() throws Exception {
        // the JSON-side twin of the header case: restore matches a field value EQUAL to the mask, so a
        // value that merely contains it fell through and was written as supplied
        String held = "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"sk-" + MASK + "\\\"}]\"}", live);

        assertThat("the real backend key must survive a field that merely contains the mask",
            live.llmBackendsConfig(), containsString(REAL_SECRET_PREFIX + "held"));
        assertThat("the literal mask must never be persisted as a backend credential",
            live.llmBackendsConfig(), not(containsString(MASK)));
        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldLeaveTheHeldBackendsDocumentUntouchedWhenTheMaskLandsInANonCredentialField() throws Exception {
        // a mask outside a credential-named field is never restored, so without a fail-closed check on
        // the merged document it would be written straight through
        String held = "[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"baseUrl\\\":\\\"https://"
            + MASK + "/v1\\\",\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat(live.llmBackendsConfig(), not(containsString(MASK)));
        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldMaskACredentialFieldWhateverItsJsonType() throws Exception {
        // a secret is not always a string: recursing past a credential-named field would publish an
        // array of tokens or a numeric key untouched
        ConfigurationDTO dto = new ConfigurationDTO(configuration().llmBackendsConfig(
            "{\"tokens\":[\"" + REAL_SECRET_PREFIX + "a\",\"" + REAL_SECRET_PREFIX + "b\"],"
                + "\"apiKey\":9876543210,\"model\":\"gpt-4\"}"));

        assertThat(dto.getLlmBackendsConfig(),
            is("{\"tokens\":\"" + MASK + "\",\"apiKey\":\"" + MASK + "\",\"model\":\"gpt-4\"}"));
    }

    @Test
    public void shouldLeaveAPartiallyMaskedValueUnsetWhenBuildingAFreshConfiguration() throws Exception {
        // buildObject() has no held configuration to restore the masked header from. Storing the
        // reduced value would PIN it, shadowing ConfigurationProperties — whereas a whole-value
        // credential is left unset so the static store resolves. Both must behave the same way.
        ConfigurationDTO dto = ObjectMapperFactory.createObjectMapper().readValue(
            "{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",X-Tenant=acme\","
                + "\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}",
            ConfigurationDTO.class);

        Configuration built = dto.buildObject();

        // assert the INSTANCE FIELD is unset, not the effective getter: the getter falls back to the
        // static store, so comparing it against ConfigurationProperties would read shared global state
        // twice and could false-negative if another (parallel) test mutated it in between
        assertThat("a partially-masked header list must be left unset, not pinned to its reduced form",
            instanceField(built, "prometheusRemoteWriteHeaders"), is(nullValue()));
        assertThat("a partially-masked backends document must be left unset, not pinned to its reduced form",
            instanceField(built, "llmBackendsConfig"), is(nullValue()));
    }

    /**
     * The raw value held on the {@link Configuration} instance, {@code null} when the property was
     * never set on it (so its getter falls back to {@link ConfigurationProperties}). Read reflectively
     * because {@code Configuration} exposes only the effective getter.
     */
    private static Object instanceField(Configuration configuration, String name) throws Exception {
        java.lang.reflect.Field field = Configuration.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(configuration);
    }

    @Test
    public void shouldRefuseTheWholeDocumentWhenAMaskedBackendKeyHasNoRealValueHeld() throws Exception {
        Configuration live = configuration().llmBackendsConfig("");

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat("removing the unresolvable key writes a document with the credential DELETED, which "
                + "breaks the backend as surely as writing the mask would",
            live.llmBackendsConfig(), is(""));
    }

    @Test
    public void shouldRefuseTheWholeDocumentWhenAMaskedBackendIsRenamed() throws Exception {
        // renaming a backend leaves its masked key with no counterpart to restore from. Silently
        // removing the field wrote a document in which openai-2 has no credential at all.
        String held = "[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai-2\\\",\\\"apiKey\\\":\\\""
            + MASK + "\\\"}]\"}", live);

        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldRefuseTheWholeDocumentWhenAnUnnamedBackendHasNoHeldCounterpart() throws Exception {
        // matched by INDEX when there is no name: a second unnamed backend has no held element, so its
        // masked key resolved to nothing and was written out as an empty object
        String held = "[{\"apiKey\":\"" + REAL_SECRET_PREFIX + "a\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"apiKey\\\":\\\"" + MASK + "\\\"},"
            + "{\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldNotTransplantAHeldKeyOntoADifferentBackend() throws Exception {
        Configuration live = configuration().llmBackendsConfig(
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "openai\"}]");

        // a NEW backend is prepended, so index 0 no longer refers to the held one — matching by
        // position here would hand openai's key to a backend the operator never gave it to. The new
        // backend's masked key cannot be resolved either, so the whole document is refused.
        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"anthropic\\\",\\\"provider\\\":\\\"ANTHROPIC\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"},{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat("openai's key must never appear under anthropic",
            live.llmBackendsConfig(), not(containsString("\"anthropic\",\"provider\":\"ANTHROPIC\",\"apiKey\"")));
        assertThat(live.llmBackendsConfig(),
            is("[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "openai\"}]"));
    }

    // ---------------------------------------------------------------------------------------------
    // 2c. values with nothing to redact must be untouched — masking must not reformat, reorder or
    //     swallow ordinary configuration, and must never throw on a value it cannot parse
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldLeaveABackendsFilePathExactlyAsConfigured() throws Exception {
        // the DOCUMENTED shape of llmBackendsConfig is a path to a JSON file — the secrets live in that
        // file, which the configuration API never returns, so the path itself must not be masked
        String path = "/etc/mockserver/llm-backends.json";
        ConfigurationDTO dto = new ConfigurationDTO(configuration().llmBackendsConfig(path));

        assertThat(dto.getLlmBackendsConfig(), is(path));
    }

    @Test
    public void shouldLeaveAHeaderListWithoutCredentialsExactlyAsConfigured() throws Exception {
        String headers = "X-Scope-OrgID=tenant-a, X-Custom=abc";
        ConfigurationDTO dto = new ConfigurationDTO(configuration().prometheusRemoteWriteHeaders(headers));

        assertThat("a header list with no credential-bearing header must be byte-identical, spacing included",
            dto.getPrometheusRemoteWriteHeaders(), is(headers));
    }

    @Test
    public void shouldFailClosedOnAMalformedBackendsDocumentRatherThanThrowOrDisclose() throws Exception {
        // truncated JSON: it cannot be parsed, but it plainly still contains a secret
        String malformed = "[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "malformed\"";
        Configuration live = configuration().llmBackendsConfig(malformed);

        String json = serializer.serialize(live);

        assertThat("an unparseable backends document must be redacted whole, not disclosed",
            json, not(containsString(REAL_SECRET_PREFIX + "malformed")));
        assertThat(new ConfigurationDTO(live).getLlmBackendsConfig(), is(MASK));

        // and applying that fully-masked value back must leave the real one alone
        applyJsonTo(json, live);
        assertThat(live.llmBackendsConfig(), is(malformed));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. masking must not break the write path
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldStillApplyRealCredentialValuesSuppliedOverTheControlPlane() throws Exception {
        Configuration target = configuration();

        // a PUT body carrying REAL credential values (not the mask) must be applied in full
        ObjectNode body = ObjectMapperFactory.createObjectMapper().createObjectNode();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            body.put(credential, realValueFor(credential));
        }

        applyJsonTo(body.toString(), target);

        List<String> notApplied = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(target);
            if (!realValueFor(credential).equals(value)) {
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
                is(realValueFor(credential)));
        }
    }

    @Test
    public void shouldExposeRealCredentialValuesOnlyThroughJsonIgnoredRawAccessors() throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO(configurationWithEveryCredentialSet());

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Method raw = ConfigurationDTO.class.getMethod("get"
                + Character.toUpperCase(credential.charAt(0)) + credential.substring(1) + "RawValue");
            assertThat("the raw accessor must return the real value so the functional paths keep working",
                raw.invoke(dto), is(realValueFor(credential)));
            assertThat("the raw accessor must be @JsonIgnore-d so it cannot leak through serialization",
                raw.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class), is(not(nullValue())));
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Configuration configurationWithEveryCredentialSet() throws Exception {
        Configuration configuration = configuration();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Configuration.class
                .getMethod(credential, String.class)
                .invoke(configuration, realValueFor(credential));
        }
        return configuration;
    }

    /**
     * Apply a JSON configuration body onto a target exactly as the control plane does: bind it to a
     * {@link ConfigurationDTO} and call {@code applyTo}.
     */
    private void applyJsonTo(String json, Configuration target) throws Exception {
        ConfigurationDTO dto = ObjectMapperFactory
            .createObjectMapper()
            .readValue(json, ConfigurationDTO.class);
        dto.applyTo(target);
    }

    private static boolean isEmbeddedValueCredential(String credential) {
        return EMBEDDED_REAL_VALUES.containsKey(credential);
    }

    /** The secret itself — the string that must never appear on the wire. */
    private static String realSecretFor(String credential) {
        return REAL_SECRET_PREFIX + credential;
    }

    /** The value the property is set to: the bare secret, or a structured value that embeds it. */
    private static String realValueFor(String credential) {
        String embedded = EMBEDDED_REAL_VALUES.get(credential);
        return embedded != null ? embedded : realSecretFor(credential);
    }

    /** The exact value {@code GET /mockserver/configuration} must return for that value. */
    private static String expectedMaskFor(String credential) {
        String embedded = EMBEDDED_MASKED_VALUES.get(credential);
        return embedded != null ? embedded : MASK;
    }

    private static Method configurationGetter(String credential) throws Exception {
        return Configuration.class.getMethod(credential);
    }

    private static Method dtoGetter(String credential) throws Exception {
        return ConfigurationDTO.class.getMethod("get"
            + Character.toUpperCase(credential.charAt(0)) + credential.substring(1));
    }
}
