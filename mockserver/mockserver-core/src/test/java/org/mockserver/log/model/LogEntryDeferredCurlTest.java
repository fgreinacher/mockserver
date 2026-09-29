package org.mockserver.log.model;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;
import org.mockserver.model.XmlBody;
import org.mockserver.serialization.LogEntrySerializer;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.matchers.MatchType.ONLY_MATCHING_FIELDS;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.Parameter.param;

/**
 * Differential proof that deferring the curl argument, and reading released bodies without caching, changes
 * no output. Each forwarded exchange is logged twice from identically built objects: once the old way (the
 * curl rendered eagerly into a String argument) and once the new way ({@link DeferredLogArgument}); every
 * rendered surface must be byte-identical, including after the entry is retained, released and its response
 * written. Proxy (Host header), forward (remote address, no Host), the address-less forward-action path and
 * a templated (echo) mock response are covered.
 */
public class LogEntryDeferredCurlTest {

    private static final MockServerLogger LOGGER = new MockServerLogger(LogEntryDeferredCurlTest.class);
    private static final HttpRequestToCurlSerializer CURL = new HttpRequestToCurlSerializer(LOGGER);
    private static final String FORWARDED_FORMAT = "returning response:{}for forwarded request\n\n in json:{}\n\n in curl:{}";
    private static final String TEXT = "line one\nline 'two' café {\"k\":\"v\"}";

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    // a wire-decoded body: value plus canonical raw bytes, as BodyDecoderEncoder builds it
    private static StringBody wireText(String value) {
        return new StringBody(value, utf8(value), false, MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
    }

    private static final class Exchange {
        final String name;
        final HttpRequest request;
        final HttpResponse response;
        final InetSocketAddress remoteAddress;

        Exchange(String name, HttpRequest request, HttpResponse response, InetSocketAddress remoteAddress) {
            this.name = name;
            this.request = request;
            this.response = response;
            this.remoteAddress = remoteAddress;
        }
    }

    private static List<Supplier<Exchange>> forwardedExchanges() {
        List<Supplier<Exchange>> exchanges = new ArrayList<>();
        exchanges.add(() -> new Exchange("proxy text",
            request().withMethod("POST").withPath("/api/load").withQueryStringParameter(param("n", "1", "2"))
                .withHeader(header("Host", "upstream.example:8080"))
                .withHeader(header("Accept-Encoding", "gzip, deflate"))
                .withHeader(header("x-request-id", "req-1"))
                .withCookie(cookie("session", "abc'123"))
                .withBody(wireText(TEXT)),
            response().withStatusCode(200).withHeader("content-type", "text/plain; charset=utf-8").withBody(wireText(TEXT)),
            new InetSocketAddress("localhost", 8080)));
        exchanges.add(() -> new Exchange("forward json, no host header",
            request().withMethod("PUT").withPath("/orders/42").withSecure(true)
                .withBody(new JsonBody("{\"id\":42,\"name\":\"café\"}", utf8("{\"id\":42,\"name\":\"café\"}"), JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS)),
            response().withStatusCode(201).withBody(new JsonBody("{\"ok\":true}", utf8("{\"ok\":true}"), JsonBody.DEFAULT_JSON_CONTENT_TYPE, ONLY_MATCHING_FIELDS)),
            InetSocketAddress.createUnresolved("upstream.internal", 9443)));
        exchanges.add(() -> new Exchange("forward xml GET",
            request().withMethod("GET").withPath("/feed").withHeader(header("Host", "feed.example"))
                .withBody(new XmlBody("<a>b</a>", utf8("<a>b</a>"), MediaType.APPLICATION_XML_UTF_8)),
            response().withStatusCode(200).withBody(new XmlBody("<ok/>", utf8("<ok/>"), MediaType.APPLICATION_XML_UTF_8)),
            new InetSocketAddress("127.0.0.1", 1081)));
        exchanges.add(() -> new Exchange("forward action, no address or host",
            request().withMethod("POST").withPath("/no-address").withBody(wireText(TEXT)),
            response().withStatusCode(502),
            null));
        return exchanges;
    }

    private static LogEntry forwardedEntry(Exchange exchange, Object curlArgument) {
        return new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setEpochTime(1_700_000_000_000L)
            .setCorrelationId("correlation-id")
            .setHttpRequest(exchange.request)
            .setHttpResponse(exchange.response)
            .setMessageFormat(FORWARDED_FORMAT)
            .setArguments(exchange.response, exchange.request, curlArgument);
    }

    private static LogEntry eager(Exchange exchange) {
        return forwardedEntry(exchange, CURL.toCurl(exchange.request, exchange.remoteAddress));
    }

    private static LogEntry deferred(Exchange exchange) {
        return forwardedEntry(exchange, DeferredLogArgument.curl(CURL, exchange.request, exchange.remoteAddress));
    }

    // With redaction off the deferred curl renders exactly as the eager String did. With it on the two
    // differ by design (the eager String bypassed redaction), so those renders are only checked for caching.
    private static List<String> renderings(LogEntry entry) {
        Configuration notRedacting = configuration().redactSecretsInLog(false);
        List<String> out = new ArrayList<>();
        out.add(entry.getMessage());
        out.add(entry.getCompactMessage());
        out.add(entry.getMessage(notRedacting));
        out.add(entry.getCompactMessage(notRedacting));
        out.add(new LogEntrySerializer(LOGGER).serialize(entry));
        out.add(new LogEntrySerializer(LOGGER, notRedacting).serialize(entry));
        out.add(String.valueOf(entry.getArguments()[2]));
        out.add(String.valueOf(entry.getArguments(notRedacting)[2]));
        return out;
    }

    private static void renderRedacted(LogEntry entry) {
        Configuration redacting = configuration().redactSecretsInLog(true);
        entry.getMessage(redacting);
        entry.getCompactMessage(redacting);
        new LogEntrySerializer(LOGGER, redacting).serialize(entry);
    }

    @Test
    public void shouldRenderDeferredCurlIdenticallyToTheEagerString() {
        for (Supplier<Exchange> supplier : forwardedExchanges()) {
            Exchange exchange = supplier.get();
            LogEntry eager = eager(exchange);
            LogEntry deferred = deferred(supplier.get());

            assertThat(exchange.name, renderings(deferred), is(renderings(eager)));
            assertThat(exchange.name, deferred.getArguments()[2], instanceOf(String.class));
            assertThat(exchange.name, deferred, is(eager));
            assertThat(exchange.name, eager, is(deferred));
            assertThat(exchange.name, deferred.hashCode(), is(eager.hashCode()));
            assertThat(exchange.name, renderings(deferred.clone()), is(renderings(eager)));
        }
    }

    @Test
    public void shouldNotRetainTheRenderedCurl() {
        for (Supplier<Exchange> supplier : forwardedExchanges()) {
            Exchange exchange = supplier.get();
            LogEntry deferred = deferred(exchange);
            deferred.getMessage();

            // translateTo is the ring-buffer copy that becomes the retained entry
            LogEntry retained = new LogEntry();
            deferred.translateTo(retained, 0);
            for (Object argument : retained.getRawArguments()) {
                assertThat(exchange.name, argument, not(instanceOf(String.class)));
            }
            assertThat(exchange.name, retained.getRawArguments()[2], instanceOf(DeferredLogArgument.class));
        }
    }

    @Test
    public void shouldRenderRetainedReleasedAndWrittenEntryIdenticallyWithoutReCachingBodies() {
        MockServerHttpResponseToFullHttpResponse writer = new MockServerHttpResponseToFullHttpResponse(LOGGER);
        for (Supplier<Exchange> supplier : forwardedExchanges()) {
            Exchange eagerExchange = supplier.get();
            eagerExchange.request.getBodyAsString();
            List<String> expected = renderings(eager(eagerExchange));

            Exchange exchange = supplier.get();
            LogEntry retained = deferred(exchange);
            retained.releaseDerivedForms();
            // the response is written after it is logged, and the entry is read later by retrieve / dashboard
            writer.mapMockServerResponseToNettyResponse(exchange.response).forEach(io.netty.util.ReferenceCountUtil::release);
            List<String> actual = renderings(retained);
            renderRedacted(retained);

            assertThat(exchange.name, actual, is(expected));
            assertThat(exchange.name, derivedBytes(exchange.request.getBody()), is(0L));
            assertThat(exchange.name, derivedBytes(exchange.response.getBody()), is(0L));
        }
    }

    @Test
    public void shouldRenderRetainedEchoResponseIdenticallyWithoutReCachingIt() {
        MockServerHttpResponseToFullHttpResponse writer = new MockServerHttpResponseToFullHttpResponse(LOGGER);
        Supplier<LogEntry> echo = () -> {
            HttpRequest request = request().withMethod("POST").withPath("/api/load").withBody(wireText(TEXT));
            // a templated response body is built from a String, so it starts with the String cached
            HttpResponse response = response().withStatusCode(200).withHeader("content-type", "text/plain").withBody(new StringBody(TEXT));
            return new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setLogLevel(Level.INFO)
                .setEpochTime(1_700_000_000_000L)
                .setHttpRequest(request)
                .setHttpResponse(response)
                .setMessageFormat("returning response:{}for request:{}")
                .setArguments(response, request);
        };
        List<String> expected = renderings3(echo.get());

        LogEntry retained = echo.get();
        retained.releaseDerivedForms();
        writer.mapMockServerResponseToNettyResponse(retained.getHttpResponse()).forEach(io.netty.util.ReferenceCountUtil::release);

        assertThat(derivedBytes(retained.getHttpResponse().getBody()), is(0L));
        assertThat(renderings3(retained), is(expected));
        assertThat(derivedBytes(retained.getHttpResponse().getBody()), is(0L));
        assertThat(derivedBytes(((HttpRequest) retained.getHttpRequests()[0]).getBody()), is(0L));
    }

    private static List<String> renderings3(LogEntry entry) {
        List<String> out = new ArrayList<>();
        out.add(entry.getMessage());
        out.add(entry.getCompactMessage());
        out.add(new LogEntrySerializer(LOGGER).serialize(entry));
        return out;
    }

    private static long derivedBytes(Body<?> body) {
        return body == null ? 0L : body.retainedDerivedFormBytes();
    }
}
