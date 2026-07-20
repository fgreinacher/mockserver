package org.mockserver.dashboard.model;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Proves {@code redactSecretsInLog} set on a {@link Configuration} <em>instance</em> masks secrets on the
 * DASHBOARD path.
 * <p>
 * The dashboard renders log entries through {@link DashboardLogEntryDTO}, which reads
 * {@code LogEntry.getHttpUpdatedRequests()/getHttpUpdatedResponse()}. Those accessors previously resolved
 * the redactor from the static {@code ConfigurationProperties} store only. Because
 * {@code PUT /mockserver/configuration} writes only the {@code Configuration} instance, enabling redaction
 * through the instance/DTO/REST route left {@code Authorization} and cookies in clear in the dashboard —
 * the exact surface the feature exists to protect.
 * <p>
 * These tests set the value ONLY on an instance, never on the static store, so they fail if the dashboard
 * path regresses to a static read.
 */
public class DashboardLogEntryDTORedactionTest {

    private static LogEntry entryWithSecrets() {
        return new LogEntry()
            .setType(LogEntry.LogMessageType.EXPECTATION_RESPONSE)
            .setHttpRequest(
                HttpRequest.request()
                    .withPath("/secure")
                    .withHeader("Authorization", "Bearer super-secret-token")
                    .withHeader("x-api-key", "secret-api-key")
            )
            .setHttpResponse(
                HttpResponse.response()
                    .withHeader("Set-Cookie", "session=secret-session-value")
                    .withBody("{\"ok\":true}")
            );
    }

    @Test
    public void shouldRedactDashboardEntryWhenRedactionEnabledOnConfigurationInstance() {
        Configuration configuration = Configuration.configuration().redactSecretsInLog(true);

        DashboardLogEntryDTO dto = new DashboardLogEntryDTO(entryWithSecrets(), configuration);

        String request = String.valueOf(dto.getHttpRequests()[0]);
        String response = String.valueOf(dto.getHttpResponse());

        assertThat("Authorization must be masked in the dashboard view",
            request, not(containsString("super-secret-token")));
        assertThat(request, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
        assertThat("api key must be masked in the dashboard view",
            request, not(containsString("secret-api-key")));
        assertThat("Set-Cookie must be masked in the dashboard view",
            response, not(containsString("secret-session-value")));
    }

    @Test
    public void shouldNotRedactDashboardEntryWhenRedactionDisabledOnConfigurationInstance() {
        Configuration configuration = Configuration.configuration().redactSecretsInLog(false);

        DashboardLogEntryDTO dto = new DashboardLogEntryDTO(entryWithSecrets(), configuration);

        assertThat("with redaction off the dashboard view must be byte-for-byte unchanged",
            String.valueOf(dto.getHttpRequests()[0]), containsString("super-secret-token"));
    }

    @Test
    public void shouldFallBackToStaticStoreWhenNoConfigurationSupplied() {
        // no Configuration and no static value set -> redaction off (the default), secrets in clear
        DashboardLogEntryDTO dto = new DashboardLogEntryDTO(entryWithSecrets(), null);

        assertThat(String.valueOf(dto.getHttpRequests()[0]), containsString("super-secret-token"));
    }

    @Test
    public void shouldLeaveTheLiveLogEntryUnmutatedByDashboardRedaction() {
        Configuration configuration = Configuration.configuration().redactSecretsInLog(true);
        LogEntry logEntry = entryWithSecrets();

        new DashboardLogEntryDTO(logEntry, configuration);

        assertThat("redaction must operate on clones so matching/verification still see the raw value",
            logEntry.getHttpRequest().toString().contains("super-secret-token"), is(true));
    }
}
