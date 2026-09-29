package org.mockserver.log.model;

import org.junit.Test;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.LogEntrySerializer;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;

import java.net.InetSocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;

public class DeferredLogArgumentTest {

    private static final HttpRequestToCurlSerializer CURL = new HttpRequestToCurlSerializer(new MockServerLogger());
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("localhost", 1080);

    private static HttpRequest requestWithSecret() {
        return request("/path").withHeader("Authorization", "Bearer token-value").withQueryStringParameter("key", "query-value");
    }

    private static DeferredLogArgument failing() {
        HttpRequestToCurlSerializer serializer = mock(HttpRequestToCurlSerializer.class);
        when(serializer.toCurl(any(), any())).thenThrow(new IllegalStateException("boom"));
        return DeferredLogArgument.curl(serializer, requestWithSecret(), ADDRESS);
    }

    @Test
    public void shouldRenderOnEveryRead() {
        DeferredLogArgument argument = DeferredLogArgument.curl(CURL, requestWithSecret(), ADDRESS);

        String rendered = argument.render(null);
        assertThat(rendered, is(CURL.toCurl(requestWithSecret(), ADDRESS)));
        assertThat(argument.render(null), is(rendered));
        assertThat(argument.render(null), not(sameInstance(rendered)));
    }

    @Test
    public void shouldRenderFromTheRedactedRequest() {
        String rendered = DeferredLogArgument.curl(CURL, requestWithSecret(), ADDRESS).render(new FixtureRedactor());

        assertThat(rendered, not(containsString("token-value")));
        assertThat(rendered, not(containsString("query-value")));
        assertThat(rendered, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactToStringEvenWithRedactionOff() {
        String rendered = DeferredLogArgument.curl(CURL, requestWithSecret(), ADDRESS).toString();

        assertThat(rendered, not(containsString("token-value")));
        assertThat(rendered, not(containsString("query-value")));
        assertThat(rendered, containsString("curl -v"));
    }

    @Test
    public void shouldFallBackToAPlaceholderWhenRenderingFails() {
        DeferredLogArgument argument = failing();

        assertThat(argument.render(null), is("<unable to render: IllegalStateException>"));
        assertThat(argument.render(new FixtureRedactor()), is("<unable to render: IllegalStateException>"));
        assertThat(argument.toString(), is("<unable to render: IllegalStateException>"));
    }

    @Test
    public void shouldNotBreakRenderingOfTheEntryWhenAnArgumentFails() {
        LogEntry entry = new LogEntry()
            .setMessageFormat("forwarded request in curl:{}")
            .setArguments(failing());

        assertThat(entry.getMessage(), containsString("<unable to render: IllegalStateException>"));
        assertThat(entry.getCompactMessage(), containsString("<unable to render: IllegalStateException>"));
        assertThat(new LogEntrySerializer(new MockServerLogger()).serialize(entry), containsString("<unable to render: IllegalStateException>"));
    }
}
