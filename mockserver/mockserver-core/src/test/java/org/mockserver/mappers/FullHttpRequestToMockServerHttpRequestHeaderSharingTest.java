package org.mockserver.mappers;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.model.Protocol;
import org.mockserver.model.RetrieveType;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * The request mapper shares a header name or value with the previous request on the same connection
 * when they are equal. That must be invisible: a connection's requests are mapped through ONE mapper
 * (sharing) and through a FRESH mapper per request (no sharing, the behaviour before sharing existed),
 * and every observable form of every request must be identical.
 */
public class FullHttpRequestToMockServerHttpRequestHeaderSharingTest {

    private static final String AUTH = "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2ln";
    private static final String[] RETRIEVE_FORMATS = {"JSON", "JAVA", "LOG_ENTRIES", "HAR", "CURL", "POSTMAN"};

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    /**
     * One connection's requests, in order. Exercises repeats, a case-only change, a changed value, reordering,
     * duplicate names, shrinking and growing header counts past the memo bound, empty and "!"-prefixed
     * literals, equal values at different positions, and a Content-Length skipped for a preserved
     * Transfer-Encoding.
     */
    private static final String[][][] CONNECTION = {
        {{"Host", "api.example.com"}, {"Accept", "application/json"}, {"Authorization", AUTH}, {"traceparent", "t-1"}, {"Accept-Encoding", "gzip"}},
        {{"Host", "api.example.com"}, {"Accept", "application/json"}, {"Authorization", AUTH}, {"traceparent", "t-2"}, {"Accept-Encoding", "gzip"}},
        {{"Host", "api.example.com"}, {"Accept", "APPLICATION/JSON"}, {"Authorization", AUTH}, {"traceparent", "t-3"}, {"Accept-Encoding", "GZIP"}},
        {{"host", "api.example.com"}, {"Accept", "application/json"}, {"Authorization", AUTH}, {"TRACEPARENT", "t-3"}, {"Accept-Encoding", "gzip"}},
        {{"Authorization", AUTH}, {"Host", "api.example.com"}, {"traceparent", "t-4"}, {"Accept", "application/json"}},
        {{"X-Multi", "m1"}, {"X-Multi", "m2"}, {"X-Multi", "m1"}, {"Host", "api.example.com"}},
        {{"X-Multi", "m1"}, {"X-Multi", "m1"}, {"X-Multi", "m2"}, {"Host", "api.example.com"}},
        {{"Host", "api.example.com"}},
        manyHeaders(70, "a"),
        manyHeaders(70, "a"),
        manyHeaders(70, "b"),
        {{"X-Empty", ""}, {"!foo", "!bar"}, {"X-Neg", "!value"}, {"Host", ""}},
        {{"X-Empty", ""}, {"!foo", "!bar"}, {"X-Neg", "!value"}, {"Host", ""}},
        {{"X-A", "same"}, {"X-B", "same"}, {"X-C", "same"}},
        {{"X-B", "same"}, {"X-A", "same"}, {"X-C", "other"}},
        {{"Content-Length", "0"}, {"Host", "api.example.com"}, {"X-Other", "v"}},
        {{"Host", "api.example.com"}, {"Accept", "application/json"}, {"Authorization", AUTH}, {"traceparent", "t-5"}, {"Accept-Encoding", "gzip"}},
    };

    private static String[][] manyHeaders(int count, String variant) {
        String[][] headers = new String[count][];
        for (int i = 0; i < count; i++) {
            headers[i] = new String[]{"X-H" + i, i % 3 == 0 ? variant + i : "fixed" + i};
        }
        return headers;
    }

    private enum Wire {
        // String names and values, a fresh copy on every request, as a caller building headers by hand supplies
        STRINGS,
        // raw bytes through netty's HttpRequestDecoder and aggregator, as a real HTTP/1.1 connection arrives:
        // String values, AsciiString names (a shared constant for Host, Accept, Connection, Content-Type and
        // Content-Length in exactly that case, otherwise a fresh instance per request)
        HTTP_1_1_DECODED,
        // lower-case AsciiString names and values, a fresh instance on every request (HPACK literals)
        HTTP_2,
        // the same AsciiString instance whenever the text repeats, as HPACK-indexed headers arrive
        HTTP_2_INDEXED
    }

    /** One client connection: the source of its requests' netty form. */
    private static final class Connection {
        private final Wire wire;
        private final Map<String, AsciiString> hpackTable = new HashMap<>();
        private final EmbeddedChannel http11Decoder;

        private Connection(Wire wire) {
            this.wire = wire;
            this.http11Decoder = wire == Wire.HTTP_1_1_DECODED ? new EmbeddedChannel(new HttpRequestDecoder(), new HttpObjectAggregator(1 << 20)) : null;
        }

        private Protocol protocol() {
            return wire == Wire.HTTP_2 || wire == Wire.HTTP_2_INDEXED ? Protocol.HTTP_2 : Protocol.HTTP_1_1;
        }

        private FullHttpRequest request(String[][] pairs) {
            if (http11Decoder != null) {
                StringBuilder wireBytes = new StringBuilder("GET /api/items?q=1 HTTP/1.1\r\n");
                for (String[] pair : pairs) {
                    wireBytes.append(pair[0]).append(':').append(pair[1].isEmpty() ? "" : " " + pair[1]).append("\r\n");
                }
                http11Decoder.writeInbound(Unpooled.copiedBuffer(wireBytes.append("\r\n").toString(), StandardCharsets.US_ASCII));
                FullHttpRequest decoded = http11Decoder.readInbound();
                assertThat("decoded " + Arrays.deepToString(pairs), decoded.decoderResult().isSuccess(), is(true));
                return decoded;
            }
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/items?q=1");
            for (String[] pair : pairs) {
                String value = new String(pair[1].toCharArray());
                if (wire == Wire.STRINGS) {
                    request.headers().add(new String(pair[0].toCharArray()), value);
                } else if (wire == Wire.HTTP_2) {
                    request.headers().add(new AsciiString(pair[0].toLowerCase()), new AsciiString(value));
                } else {
                    String name = pair[0].toLowerCase();
                    request.headers().add(
                        hpackTable.computeIfAbsent("n:" + name, ignored -> new AsciiString(name)),
                        hpackTable.computeIfAbsent("v:" + value, ignored -> new AsciiString(value))
                    );
                }
            }
            return request;
        }

        private void close() {
            if (http11Decoder != null) {
                http11Decoder.finishAndReleaseAll();
            }
        }
    }

    private FullHttpRequestToMockServerHttpRequest mapper() {
        return new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, false, null, 1080);
    }

    private List<HttpRequest> mapConnection(boolean shareAcrossRequests, Wire wire) {
        FullHttpRequestToMockServerHttpRequest connectionMapper = mapper();
        Connection connection = new Connection(wire);
        List<HttpRequest> mapped = new ArrayList<>();
        try {
            for (int i = 0; i < CONNECTION.length; i++) {
                FullHttpRequest request = connection.request(CONNECTION[i]);
                List<Header> preserved = i == CONNECTION.length - 2
                    ? Collections.singletonList(new Header(TRANSFER_ENCODING.toString(), "chunked"))
                    : null;
                try {
                    FullHttpRequestToMockServerHttpRequest requestMapper = shareAcrossRequests ? connectionMapper : mapper();
                    mapped.add(requestMapper.mapFullHttpRequestToMockServerRequest(request, preserved, null, null, connection.protocol()));
                } finally {
                    request.release();
                }
            }
        } finally {
            connection.close();
        }
        return mapped;
    }

    private static List<String> rawHeaderStore(HttpRequest request) {
        List<String> raw = new ArrayList<>();
        if (request.getHeaders() != null) {
            for (Map.Entry<NottableString, NottableString> entry : request.getHeaders().getMultimap().entries()) {
                raw.add(describe(entry.getKey()) + "=" + describe(entry.getValue()));
            }
        }
        return raw;
    }

    private static String describe(NottableString string) {
        return "[" + string.getValue() + "|not=" + string.isNot() + "|" + string.getClass().getSimpleName() + "]";
    }

    @After
    public void shutdownExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    public void shouldMapEveryRequestOfAConnectionExactlyAsAFreshMapperWould() {
        for (Wire wire : Wire.values()) {
            List<HttpRequest> shared = mapConnection(true, wire);
            List<HttpRequest> fresh = mapConnection(false, wire);
            for (int i = 0; i < CONNECTION.length; i++) {
                String at = wire + " request " + i;
                assertThat(at + " raw header store (order, case, multiplicity)", rawHeaderStore(shared.get(i)), equalTo(rawHeaderStore(fresh.get(i))));
                assertThat(at + " header list", shared.get(i).getHeaderList(), equalTo(fresh.get(i).getHeaderList()));
                assertThat(at + " request", shared.get(i), equalTo(fresh.get(i)));
                assertThat(at + " json", shared.get(i).toString(), equalTo(fresh.get(i).toString()));
            }
        }
    }

    @Test
    public void shouldRetrieveIdenticalRequestsInEveryFormat() throws Exception {
        HttpState sharedState = httpState();
        HttpState freshState = httpState();
        List<HttpRequest> shared = new ArrayList<>();
        List<HttpRequest> fresh = new ArrayList<>();
        for (Wire wire : Wire.values()) {
            shared.addAll(mapConnection(true, wire));
            fresh.addAll(mapConnection(false, wire));
        }
        for (int i = 0; i < shared.size(); i++) {
            sharedState.log(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(shared.get(i)));
            freshState.log(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(fresh.get(i)));
        }

        for (String format : RETRIEVE_FORMATS) {
            String fromShared = normalise(retrieve(sharedState, format));
            String fromFresh = normalise(retrieve(freshState, format));
            assertThat(format + " retrieve carries header values", fromShared.contains("t-5"), is(true));
            assertThat(format + " retrieve", fromShared, equalTo(fromFresh));
        }
    }

    @Test
    public void shouldShareAnEqualNameOrValueWithThePreviousRequestOnTheConnection() {
        for (Wire wire : Wire.values()) {
            List<HttpRequest> mapped = mapConnection(true, wire);
            NottableString[][] first = entries(mapped.get(0));
            NottableString[][] second = entries(mapped.get(1));
            NottableString[][] third = entries(mapped.get(2));

            // the repeated Authorization value, and the uncommon traceparent name, are one instance per connection
            assertSame(first[2][1], second[2][1]);
            assertSame(first[3][0], second[3][0]);
            // the changed traceparent value is not shared
            assertNotSame(first[3][1], second[3][1]);
            // a value differing only in case is its own instance and keeps its case
            assertNotSame(second[1][1], third[1][1]);
            assertThat(third[1][1].getValue(), equalTo("APPLICATION/JSON"));
        }
    }

    @Test
    public void shouldShareHpackIndexedHeadersEvenWhenTheClientReordersThem() {
        FullHttpRequestToMockServerHttpRequest connectionMapper = mapper();
        Connection connection = new Connection(Wire.HTTP_2_INDEXED);
        String[][] inOrder = {{"authorization", "Bearer indexed-token"}, {"x-tenant-id", "tenant-1"}, {"accept", "application/json"}};
        String[][] reordered = {{"accept", "application/json"}, {"authorization", "Bearer indexed-token"}, {"x-tenant-id", "tenant-1"}};
        NottableString[][] first = entries(map(connectionMapper, inOrder, connection));
        NottableString[][] second = entries(map(connectionMapper, reordered, connection));

        assertSame(first[0][1], second[1][1]);
        assertSame(first[1][0], second[2][0]);
        assertSame(first[1][1], second[2][1]);
        assertSame(first[2][1], second[0][1]);
        assertThat(second[1][1].getValue(), equalTo("Bearer indexed-token"));
    }

    @Test
    public void shouldFindAReorderedHeaderPastAPlainStringValue() {
        // HTTP/2 hands over AsciiString values except where netty builds a String, such as a joined cookie
        FullHttpRequestToMockServerHttpRequest connectionMapper = mapper();
        AsciiString token = new AsciiString("Bearer indexed-token");
        NottableString[][] first = entries(mapHttp2(connectionMapper, "cookie", "a=b; c=d", "authorization", token));
        NottableString[][] second = entries(mapHttp2(connectionMapper, "authorization", token, "cookie", "a=b; c=d"));

        assertSame(first[1][1], second[0][1]);
        assertThat(second[1][1].getValue(), equalTo("a=b; c=d"));
    }

    private HttpRequest mapHttp2(FullHttpRequestToMockServerHttpRequest connectionMapper, CharSequence... namesAndValues) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/items");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            request.headers().add(namesAndValues[i], namesAndValues[i + 1]);
        }
        try {
            return connectionMapper.mapFullHttpRequestToMockServerRequest(request, null, null, null, Protocol.HTTP_2);
        } finally {
            request.release();
        }
    }

    @Test
    public void shouldNotShareBetweenConnections() {
        HttpRequest one = mapConnection(true, Wire.STRINGS).get(0);
        HttpRequest other = mapConnection(true, Wire.STRINGS).get(0);
        assertThat(entries(one)[2][1], not(sameInstance(entries(other)[2][1])));
    }

    @Test
    public void shouldRetainOnlyTheMostRecentRequestOnTheConnection() throws InterruptedException {
        FullHttpRequestToMockServerHttpRequest connectionMapper = mapper();
        WeakReference<NottableString> requestAValue = firstValueOf(connectionMapper, new String[][]{{"X-A", "only-in-request-a"}, {"X-B", "2"}, {"X-C", "3"}});
        map(connectionMapper, new String[][]{{"X-A", "request-b"}});

        // nothing but the connection's memo could still hold request A's value wrapper
        for (int attempt = 0; attempt < 100 && requestAValue.get() != null; attempt++) {
            System.gc();
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat("the connection still retains a header value from the request before last", requestAValue.get(), nullValue());
    }

    private WeakReference<NottableString> firstValueOf(FullHttpRequestToMockServerHttpRequest connectionMapper, String[][] pairs) {
        return new WeakReference<>(entries(map(connectionMapper, pairs))[0][1]);
    }

    @Test
    public void shouldHoldAtMostThePreviousRequestAndAtMostSixtyFourHeaders() {
        FullHttpRequestToMockServerHttpRequest connectionMapper = mapper();
        NottableString[][] wide = entries(map(connectionMapper, manyHeaders(70, "a")));
        NottableString[][] wideAgain = entries(map(connectionMapper, manyHeaders(70, "a")));
        assertSame(wide[63][1], wideAgain[63][1]);
        assertNotSame(wide[64][1], wideAgain[64][1]);
        assertNotSame(wide[64][0], wideAgain[64][0]);

        // a shorter request forgets the slots it did not use
        String[][] three = {{"X-A", "1"}, {"X-B", "2"}, {"X-C", "3"}};
        map(connectionMapper, three);
        NottableString[][] before = entries(map(connectionMapper, three));
        map(connectionMapper, new String[][]{{"X-A", "1"}});
        NottableString[][] after = entries(map(connectionMapper, three));
        assertSame(before[0][1], after[0][1]);
        assertNotSame(before[2][1], after[2][1]);
    }

    private HttpRequest map(FullHttpRequestToMockServerHttpRequest connectionMapper, String[][] pairs) {
        return map(connectionMapper, pairs, new Connection(Wire.STRINGS));
    }

    private HttpRequest map(FullHttpRequestToMockServerHttpRequest connectionMapper, String[][] pairs, Connection connection) {
        FullHttpRequest request = connection.request(pairs);
        try {
            return connectionMapper.mapFullHttpRequestToMockServerRequest(request, null, null, null, connection.protocol());
        } finally {
            request.release();
        }
    }

    private static NottableString[][] entries(HttpRequest request) {
        List<NottableString[]> entries = new ArrayList<>();
        for (Map.Entry<NottableString, NottableString> entry : request.getHeaders().getMultimap().entries()) {
            entries.add(new NottableString[]{entry.getKey(), entry.getValue()});
        }
        return entries.toArray(new NottableString[0][]);
    }

    private HttpState httpState() {
        Scheduler scheduler = mock(Scheduler.class);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executors.add(executor);
        when(scheduler.getExecutorService()).thenReturn(executor);
        Configuration configuration = configuration();
        return new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    private static String retrieve(HttpState httpState, String format) throws InterruptedException {
        CapturingResponseWriter writer = new CapturingResponseWriter();
        HttpRequest retrieve = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.REQUESTS.name())
            .withQueryStringParameter("format", format);
        assertThat(httpState.handle(retrieve, writer, false), is(true));
        assertThat("no " + format + " response", writer.latch.await(30, TimeUnit.SECONDS), is(true));
        assertThat(format + " status", writer.response.getStatusCode(), is(200));
        return writer.response.getBodyAsString();
    }

    // Two states log at different instants, and entries carry generated ids.
    private static String normalise(String body) {
        return body
            .replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?", "<time>")
            .replaceAll("\"(epochTime|timestamp|time)\" : \\d+", "\"$1\" : <n>")
            .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>");
    }

    private static class CapturingResponseWriter extends ResponseWriter {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile HttpResponse response;

        CapturingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
            latch.countDown();
        }
    }
}
