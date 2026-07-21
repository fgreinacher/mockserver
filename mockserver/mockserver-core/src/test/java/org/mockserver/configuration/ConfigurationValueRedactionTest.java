package org.mockserver.configuration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.mockserver.configuration.ConfigurationProperties.REDACTED_VALUE;
import static org.mockserver.configuration.ConfigurationProperties.redactSensitiveValue;
import static org.mockserver.configuration.ConfigurationProperties.restoreRedactedValue;

/**
 * The single redaction rule shared by every surface that discloses a configuration value:
 * {@code GET /mockserver/configuration} (via {@code ConfigurationDTO}), {@code GET /mockserver/config}
 * and {@code --print-config} (via {@code effectiveConfiguration()}), and the startup property-file log
 * dump — which calls exactly this and nothing else, so these cases are what that dump prints.
 *
 * <p>Pure: reads and mutates no global state, so it is safe in the parallel Surefire phase.
 */
public class ConfigurationValueRedactionTest {

    @Test
    public void masksAWholeValueCredentialByPropertyName() {
        assertThat(redactSensitiveValue("mockserver.llmApiKey", "sk-secret"), is(REDACTED_VALUE));
        assertThat(redactSensitiveValue("llmApiKey", "sk-secret"), is(REDACTED_VALUE));
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteBearerToken", "t"), is(REDACTED_VALUE));
    }

    @Test
    public void redactsOnlyTheCredentialHeadersOfAHeaderListProperty() {
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders",
                "Api-Key=NRAK-secret,X-Scope-OrgID=tenant-a"),
            is("Api-Key=" + REDACTED_VALUE + ",X-Scope-OrgID=tenant-a"));
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders",
                "Authorization=Bearer secret,X-Custom=abc"),
            is("Authorization=" + REDACTED_VALUE + ",X-Custom=abc"));
    }

    @Test
    public void leavesAHeaderListWithoutCredentialsByteIdentical() {
        String headers = "X-Scope-OrgID=tenant-a, X-Custom=abc";
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders", headers), is(headers));
    }

    @Test
    public void leavesABackendsFilePathUnchangedButRedactsAnInlineDocument() {
        // the DOCUMENTED shape is a path — the secrets live in the file, which no endpoint returns
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig", "/etc/mockserver/backends.json"),
            is("/etc/mockserver/backends.json"));
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig",
                "[{\"name\":\"openai\",\"apiKey\":\"sk-secret\"}]"),
            is("[{\"name\":\"openai\",\"apiKey\":\"" + REDACTED_VALUE + "\"}]"));
    }

    @Test
    public void failsClosedOnAnUnparseableDocumentAndOnNonStringSecrets() {
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig", "[{\"apiKey\":\"sk-secret\""),
            is(REDACTED_VALUE));
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig", "{\"tokens\":[\"a-secret\",\"b-secret\"]}"),
            not(containsString("secret")));
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig", "{\"apiKey\":9876543210}"),
            not(containsString("9876543210")));
    }

    @Test
    public void treatsTheAbbreviatedCredentialNamePasswdAsSensitive() {
        // "passwd" is not a substring of "password", and it does not end in "key", so neither existing
        // rule reached it — a property or header named this way was disclosed in clear
        assertThat(redactSensitiveValue("mockserver.upstreamPasswd", "hunter2"), is(REDACTED_VALUE));
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders",
                "X-Passwd=hunter2,X-Scope-OrgID=tenant-a"),
            is("X-Passwd=" + REDACTED_VALUE + ",X-Scope-OrgID=tenant-a"));
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig", "{\"passwd\":\"hunter2\"}"),
            is("{\"passwd\":\"" + REDACTED_VALUE + "\"}"));
    }

    @Test
    public void masksAValueWhoseSECONDJsonDocumentCarriesTheSecret() {
        // Jackson parses the FIRST document and discards the rest unless FAIL_ON_TRAILING_TOKENS is
        // enabled. With it off, a first document holding no credential-named field meant "nothing was
        // redacted", so the ENTIRE original string — trailing document and all — was returned in clear
        // each of these parses as a COMPLETE first document with the secret in what FOLLOWS it, which
        // is what makes them discriminating: wrapping them in an extra [...] would make them malformed
        // regardless of the flag, so they would pass without it and prove nothing. Collected rather
        // than asserted one by one so a regression names every form that leaks, not just the first.
        List<String> leaked = new ArrayList<>();
        for (String value : new String[]{
            "{\"a\":1}{\"apiKey\":\"sk-LEAK0\"}",
            "{\"a\":1}{\"name\":\"o\",\"apiKey\":\"sk-LEAK1\"}",
            "{\"version\":1}\n[{\"name\":\"o\",\"apiKey\":\"sk-LEAK2\"}]",
            "[{\"a\":1}] [{\"apiKey\":\"sk-LEAK3\"}]"
        }) {
            String redacted = redactSensitiveValue("mockserver.llmBackendsConfig", value);
            if (redacted.contains("sk-LEAK")) {
                leaked.add(value + " -> " + redacted);
            }
        }

        assertThat("values whose trailing document was parsed away and then returned in clear: " + leaked,
            leaked, is(empty()));
        assertThat("and such a value is masked WHOLE, not partially",
            redactSensitiveValue("mockserver.llmBackendsConfig", "{\"a\":1}{\"apiKey\":\"sk-x\"}"),
            is(REDACTED_VALUE));
    }

    @Test
    public void treatsCredentialBearingHeaderNamesTheSubstringRulesMissAsSensitive() {
        for (String header : new String[]{
            "X-Signature", "X-Hub-Signature-256", "X-Hmac", "Authentication", "X-JWT",
            "X-Session-Id", "X-Otp", "X-Auth", "pwd", "salt", "X-Bearer"
        }) {
            assertThat(header + " carries a credential and must be masked",
                redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders",
                    header + "=s3cret,X-Scope-OrgID=tenant-a"),
                is(header + "=" + REDACTED_VALUE + ",X-Scope-OrgID=tenant-a"));
        }
    }

    @Test
    public void keepsNonSecretAuthenticationAndJwtPropertyNamesReadable() {
        // these words are credential-bearing as HEADER names but describe ordinary settings as PROPERTY
        // names, so they live in the header/field rule only — masking them would hide the very
        // configuration an operator debugging authentication needs to read
        assertThat(redactSensitiveValue("mockserver.tlsMutualAuthenticationRequired", "true"), is("true"));
        assertThat(redactSensitiveValue("mockserver.proxyAuthenticationUsername", "admin"), is("admin"));
        assertThat(redactSensitiveValue("mockserver.dataPlaneBasicAuthenticationRealm", "MockServer"),
            is("MockServer"));
        assertThat(redactSensitiveValue("mockserver.controlPlaneJWTAuthenticationRequired", "false"),
            is("false"));
        assertThat(redactSensitiveValue("mockserver.controlPlaneJWTAuthenticationJWKSource",
            "https://issuer/.well-known/jwks.json"), is("https://issuer/.well-known/jwks.json"));
    }

    @Test
    public void masksAValueThatEmbedsAJsonDocumentWithoutStartingWithOne() {
        // the detection used to require a leading { or [, so a JSON document behind a prefix — or behind
        // a byte-order mark, which String.trim() does not strip — was disclosed in clear
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig",
                "backends=[{\"name\":\"openai\",\"apiKey\":\"sk-leak\"}]"),
            not(containsString("sk-leak")));
        assertThat(redactSensitiveValue("mockserver.llmBackendsConfig",
                "\uFEFF[{\"name\":\"openai\",\"apiKey\":\"sk-leak\"}]"),
            not(containsString("sk-leak")));
    }

    @Test
    public void stillLeavesTheDocumentedBackendsFilePathShapeUntouched() {
        // the widened detection must not swallow the DOCUMENTED shape of llmBackendsConfig — a path
        for (String path : new String[]{
            "/etc/mockserver/backends.json",
            "./config/llm-backends.json",
            "C:\\mockserver\\backends.json",
            "file:/etc/mockserver/backends.json"
        }) {
            assertThat(redactSensitiveValue("mockserver.llmBackendsConfig", path), is(path));
        }
    }

    @Test
    public void leavesUnrelatedPropertiesAndAbsentValuesAlone() {
        assertThat(redactSensitiveValue("mockserver.maxExpectations", "4242"), is("4242"));
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders", ""), is(""));
        assertThat(redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders", null), is((String) null));
    }

    @Test
    public void restoreIsTheInverseForTheValueTheRuleRedacted() {
        String held = "Api-Key=NRAK-secret,X-Scope-OrgID=tenant-a";
        String masked = redactSensitiveValue("mockserver.prometheusRemoteWriteHeaders", held);

        assertThat("an unedited round trip returns the held value verbatim",
            restoreRedactedValue("mockserver.prometheusRemoteWriteHeaders", masked, held), is(held));
        assertThat("a property with no embedded-credential shape is passed straight through",
            restoreRedactedValue("mockserver.maxExpectations", "4242", "1"), is("4242"));
    }

    @Test
    public void neverResolvesAnUnresolvableMaskInAStructuredValue() {
        // refused by each restore* method's own check — null means "leave the held value untouched"
        assertThat("a header whose value merely contains the mask cannot be resolved",
            restoreRedactedValue("mockserver.prometheusRemoteWriteHeaders",
                "Authorization=Bearer " + REDACTED_VALUE, "Authorization=Bearer real"), is((String) null));
        assertThat("a backend field whose value merely contains the mask cannot be resolved",
            restoreRedactedValue("mockserver.llmBackendsConfig",
                "[{\"name\":\"openai\",\"apiKey\":\"sk-" + REDACTED_VALUE + "\"}]",
                "[{\"name\":\"openai\",\"apiKey\":\"sk-real\"}]"), is((String) null));
    }

    @Test
    public void neverResolvesToAMaskCarryingValueForAPropertyWithNoStructuredShape() {
        // the backstop in restoreRedactedValue itself, and the ONLY assertion that exercises it: no
        // restore* method examines a property outside the two structured shapes, so without this check
        // such a value would be written with the mask intact
        assertThat(restoreRedactedValue("mockserver.someFutureProperty", "prefix-" + REDACTED_VALUE, "real"),
            is((String) null));
        assertThat("a value carrying no mask is still passed straight through",
            restoreRedactedValue("mockserver.someFutureProperty", "plain", "real"), is("plain"));
    }
}
