package org.mockserver.configuration;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
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
}
