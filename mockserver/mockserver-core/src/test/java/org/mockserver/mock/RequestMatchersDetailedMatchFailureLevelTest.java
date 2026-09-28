package org.mockserver.mock;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pins the log-level gate on the in-loop {@link org.mockserver.matchers.MatchDifference}
 * allocation in {@link RequestMatchers#firstMatchingExpectation}: the detailed per-field
 * differences are recorded (a MatchDifference per candidate) only when
 * {@code detailedMatchFailures} is on AND INFO logging is enabled — the only levels that
 * read them (the per-field "because" text and the closest-match selection, both INFO-gated;
 * TRACE implies INFO). At WARN/ERROR/OFF nothing consults them, so a single reusable
 * MatchDifference serves the whole scan and the detailed shell/map churn is not allocated.
 *
 * <p>These tests fix the OBSERVABLE contract that gate must preserve: detail present in the
 * INFO "because", detail absent without the flag, and no functional change at WARN. Uses an
 * instance {@link org.mockserver.configuration.Configuration} and a Mockito-mocked logger
 * only (no static/global state), so they run in the parallel Surefire phase.</p>
 */
public class RequestMatchersDetailedMatchFailureLevelTest {

    private static RequestMatchers matchers(MockServerLogger logger, boolean detailedMatchFailures) {
        return new RequestMatchers(
            configuration().detailedMatchFailures(detailedMatchFailures),
            logger,
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        );
    }

    private static MockServerLogger loggerEnabledFrom(Level lowest) {
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenAnswer(invocation -> {
            Level queried = invocation.getArgument(0);
            return queried.toInt() >= lowest.toInt();
        });
        return logger;
    }

    private static List<LogEntry> perMatcherNotMatched(MockServerLogger logger) {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        // atLeast(0): the WARN arm emits no events at all, so a strict verify would fail there.
        verify(logger, atLeast(0)).logEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(logEntry -> logEntry.getType() == EXPECTATION_NOT_MATCHED)
            .filter(logEntry -> logEntry.getMessageFormat() != null
                && logEntry.getMessageFormat().startsWith("request:{}didn't match"))
            .collect(Collectors.toList());
    }

    @Test
    public void infoWithDetailedRecordsPerFieldDifferenceInBecause() {
        // given - INFO logging on and detailedMatchFailures on: the retained differences must
        // be consumed to build the per-field "because" detail
        MockServerLogger logger = loggerEnabledFrom(Level.INFO);
        RequestMatchers matchers = matchers(logger, true);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when - a request that mismatches on path
        matchers.firstMatchingExpectation(request().withMethod("GET").withPath("differentPath"));

        // then - the not-matched "because" carries the per-field difference detail (the
        // "<field> didn't match: <diff>" form only produced when a MatchDifference retains it)
        List<LogEntry> notMatched = perMatcherNotMatched(logger);
        assertThat(notMatched, hasSize(1));
        assertThat(notMatched.get(0).getBecause(), containsString("didn't match: "));
    }

    @Test
    public void infoWithoutDetailedOmitsPerFieldDifferenceInBecause() {
        // given - INFO on but detailedMatchFailures OFF: no MatchDifference retains a diff, so the
        // "because" still names the failing field but carries no per-field difference detail. This
        // is the control that proves the marker asserted above is DRIVEN by detail retention.
        MockServerLogger logger = loggerEnabledFrom(Level.INFO);
        RequestMatchers matchers = matchers(logger, false);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when
        matchers.firstMatchingExpectation(request().withMethod("GET").withPath("differentPath"));

        // then
        List<LogEntry> notMatched = perMatcherNotMatched(logger);
        assertThat(notMatched, hasSize(1));
        assertThat(notMatched.get(0).getBecause(), not(containsString("didn't match: ")));
    }

    @Test
    public void warnWithDetailedEmitsNoInfoNotMatchedEventButStillReturnsNull() {
        // given - WARN logging (INFO disabled) with detailedMatchFailures on: nothing below INFO
        // reads the differences, so the detailed path is skipped and no INFO not-matched event fires
        MockServerLogger logger = loggerEnabledFrom(Level.WARN);
        RequestMatchers matchers = matchers(logger, true);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("someBody")),
            API
        );

        // when
        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("differentPath"));

        // then - no match, and no INFO-level not-matched diagnostic emitted
        assertThat(matched, is(nullValue()));
        assertThat(perMatcherNotMatched(logger), is(empty()));
    }

    @Test
    public void warnWithDetailedStillMatchesTheCorrectExpectationAcrossManyCandidates() {
        // given - WARN with detail on, several non-matching candidates BEFORE the matching one, so
        // the reused shared MatchDifference is threaded through every candidate in the scan
        MockServerLogger logger = loggerEnabledFrom(Level.WARN);
        RequestMatchers matchers = matchers(logger, true);
        for (int i = 0; i < 5; i++) {
            matchers.add(
                new Expectation(request().withMethod("GET").withPath("/no-match-" + i))
                    .thenRespond(response().withBody("miss" + i)),
                API
            );
        }
        Expectation target = new Expectation(request().withMethod("GET").withPath("/target"))
            .thenRespond(response().withBody("hit"));
        matchers.add(target, API);

        // when
        Expectation matched = matchers.firstMatchingExpectation(request().withMethod("GET").withPath("/target"));

        // then - reuse of the single shared MatchDifference across candidates must not corrupt the
        // match: the correct expectation is returned
        assertThat(matched, is(target));
    }

    @Test
    public void infoWithDetailedStillSelectsClosestMatchByFewestDifferences() {
        // given - INFO + detail: closest-match selection reads getAllDifferences().size(), so the
        // expectation with the FEWEST differing fields (method+path match, one field off) must win
        MockServerLogger logger = loggerEnabledFrom(Level.INFO);
        RequestMatchers matchers = matchers(logger, true);
        matchers.add(
            new Expectation(request().withMethod("GET").withPath("expectedPath"))
                .thenRespond(response().withBody("close")),
            API
        );
        matchers.add(
            new Expectation(request().withMethod("POST").withPath("otherPath"))
                .thenRespond(response().withBody("far")),
            API
        );

        // when - matches the GET method of the first expectation but neither path
        matchers.firstMatchingExpectation(request().withMethod("GET").withPath("differentPath"));

        // then - the closest-expectation summary is emitted and reports the first expectation as
        // the closest (one field off -> matched 9/10), proving size()-based selection still works
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger, atLeastOnce()).logEvent(captor.capture());
        List<LogEntry> closest = captor.getAllValues().stream()
            .filter(logEntry -> logEntry.getType() == EXPECTATION_NOT_MATCHED)
            .filter(logEntry -> logEntry.getMessageFormat() != null
                && logEntry.getMessageFormat().contains("closest expectation"))
            .collect(Collectors.toList());
        assertThat(closest, hasSize(1));
        assertThat(closest.get(0).getMessageFormat(), containsString("matched 9/10 fields"));
    }
}
