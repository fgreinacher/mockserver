package org.mockserver.mappers;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;
import org.mockserver.model.Protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertSame;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.NottableString.headerName;
import static org.mockserver.model.NottableString.string;

/**
 * Differential harness pinning single-pass header ingest against the legacy names()+getAll() grouped
 * ingest over the same netty header inputs. Every observable view (getHeaderList, getHeader,
 * getFirstHeader, Headers.equals/hashCode, shared name instances) must be identical between the two;
 * only the raw flat-store order is intended to differ (wire order, not name-grouped) and is pinned as
 * the new contract by {@link #shouldStoreRequestHeadersInWireOrderNotGroupedOrder()} — the test the
 * negative control reddens when the mapper is reverted to the two-pass form.
 */
public class FullHttpRequestToMockServerHttpRequestHeaderIngestDifferentialTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    private FullHttpRequestToMockServerHttpRequest mapper() {
        return new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, false, null, 80);
    }

    // ordered (name, value) pairs; interleaved and consecutive same-case duplicates. Case-variant
    // duplicate names (Host vs host) are pinned separately because the legacy two-pass ingest and the
    // single-pass ingest genuinely DIVERGE on them (see the case-variant test) — netty getAll(name) is
    // case-insensitive, so the legacy path duplicated every case-variant's values under each variant.
    private static final String[][] CORPUS = {
        {"A", "1"},
        {"B", "2"},
        {"A", "3"},
        {"Host", "h1"},
        {"X-Empty", ""},
        {"X-Multi", "m1"},
        {"X-Multi", "m2"},
        {"!foo", "!bar"},
        {"Content-Type", "text/plain"},
    };

    private FullHttpRequest requestWith(String[][] pairs) {
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        for (String[] pair : pairs) {
            nettyRequest.headers().add(pair[0], pair[1]);
        }
        return nettyRequest;
    }

    /**
     * Reproduces the removed two-pass ingest exactly: names() gives distinct names in first-occurrence
     * order, getAll(name) gathers that name's values, wrapped literally the same way the new path wraps.
     */
    private Headers legacyGroupedHeaders(HttpHeaders httpHeaders) {
        Headers headers = new Headers();
        for (String name : httpHeaders.names()) {
            List<NottableString> values = new ArrayList<>();
            for (String value : httpHeaders.getAll(name)) {
                values.add(string(value, false));
            }
            headers.withEntry(headerName(name), values);
        }
        return headers;
    }

    @Test
    public void shouldMatchLegacyGroupedIngestForEveryObservableView() {
        // given
        FullHttpRequest nettyRequest = requestWith(CORPUS);
        try {
            Headers legacy = legacyGroupedHeaders(nettyRequest.headers());

            // when
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);
            Headers actual = mapped.getHeaders();

            // then — getHeaderList equal element-for-element IN ORDER, grouped values per name preserved
            assertThat(mapped.getHeaderList(), equalTo(legacy.getEntries()));

            // and — getFirstHeader/getHeader identical for every name, incl. case-only variants
            for (Header header : legacy.getEntries()) {
                String name = header.getName().getValue();
                assertThat("getHeader(" + name + ")", mapped.getHeader(name), equalTo(valuesFor(legacy, name)));
                assertThat("getFirstHeader(" + name + ")", mapped.getFirstHeader(name), equalTo(firstFor(legacy, name)));
            }
            assertThat(mapped.getHeader("HOST"), equalTo(valuesFor(legacy, "HOST")));
            assertThat(mapped.getHeader("host"), equalTo(valuesFor(legacy, "host")));

            // and — whole-collection equality and hashCode identical
            assertThat(actual, equalTo(legacy));
            assertThat(actual.hashCode(), equalTo(legacy.hashCode()));

            // and — well-known names resolve to the SHARED instance (guards commit 37a8fb023)
            assertSame(headerName("Content-Type"), headerFor(mapped, "Content-Type").getName());
            assertSame(headerName("Host"), headerFor(mapped, "Host").getName());

            // and — a header literally named/valued "!foo"/"!bar" is stored verbatim, not a negation
            Header literal = headerFor(mapped, "!foo");
            assertThat(literal.getName().isNot(), is(false));
            assertThat(literal.getName().getValue(), equalTo("!foo"));
            assertThat(literal.getValues().get(0).isNot(), is(false));
            assertThat(literal.getValues().get(0).getValue(), equalTo("!bar"));

            // and — an empty-valued header keeps a single empty value
            assertThat(mapped.getHeader("X-Empty"), equalTo(Collections.singletonList("")));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldRecordCaseVariantDuplicateNamesAsDistinctSingleValueEntries() {
        // given the same header name in two casings, each with its own value
        FullHttpRequest nettyRequest = requestWith(new String[][]{{"Host", "h1"}, {"host", "h2"}});
        try {
            // when
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then — each case-variant is a distinct, case-preserved, single-value entry. Single-pass
            // ingest fixes a latent quirk of the legacy two-pass path: netty getAll(name) is
            // case-insensitive, so names()+getAll wrongly recorded Host->[h1,h2] AND host->[h1,h2].
            assertThat(mapped.getHeaderList(), equalTo(java.util.Arrays.asList(
                new Header("Host", "h1"),
                new Header("host", "h2")
            )));

            // and — case-insensitive lookup still gathers both, once each, in first-occurrence order
            assertThat(mapped.getHeader("host"), equalTo(java.util.Arrays.asList("h1", "h2")));
            assertThat(mapped.getFirstHeader("HOST"), equalTo("h1"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldStoreRequestHeadersInWireOrderNotGroupedOrder() {
        // given interleaved duplicate names A:1, B:2, A:3
        FullHttpRequest nettyRequest = requestWith(new String[][]{{"A", "1"}, {"B", "2"}, {"A", "3"}});
        try {
            // when
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then — the raw flat store holds WIRE order (the intended behaviour change)
            List<String> rawOrder = new ArrayList<>();
            Iterator<Map.Entry<NottableString, NottableString>> iterator =
                mapped.getHeaders().getMultimap().entries().iterator();
            while (iterator.hasNext()) {
                Map.Entry<NottableString, NottableString> entry = iterator.next();
                rawOrder.add(entry.getKey().getValue() + "=" + entry.getValue().getValue());
            }
            assertThat(rawOrder, contains("A=1", "B=2", "A=3"));

            // and — getHeaderList is unaffected: it regroups by first-occurrence key
            List<Header> headerList = mapped.getHeaderList();
            assertThat(headerList, equalTo(java.util.Arrays.asList(
                new Header("A", "1", "3"),
                new Header("B", "2")
            )));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldSkipContentLengthWhenTransferEncodingPreserved() {
        // given a preserved Transfer-Encoding and a live Content-Length
        FullHttpRequest nettyRequest = requestWith(new String[][]{{"Content-Length", "42"}, {"X-Other", "v"}});
        List<Header> preserved = Collections.singletonList(new Header(TRANSFER_ENCODING.toString(), "chunked"));
        try {
            // when
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(nettyRequest, preserved, null, null, Protocol.HTTP_1_1);

            // then — Content-Length is dropped, other headers survive
            assertThat(mapped.getHeader(CONTENT_LENGTH.toString()), equalTo(Collections.emptyList()));
            assertThat(mapped.getHeader("X-Other"), equalTo(Collections.singletonList("v")));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldOnlyReAddPreservedHeaderWhenActuallyRemoved() {
        // given one preserved header still present in live headers, one absent from them
        FullHttpRequest nettyRequest = requestWith(new String[][]{{"Content-Encoding", "gzip"}});
        List<Header> preserved = new ArrayList<>();
        preserved.add(new Header("content-encoding", "gzip"));
        preserved.add(new Header("X-Removed", "restored"));
        try {
            // when
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(nettyRequest, preserved, null, null, Protocol.HTTP_1_1);

            // then — present header not duplicated, absent header re-added
            assertThat(mapped.getHeader("Content-Encoding"), equalTo(Collections.singletonList("gzip")));
            assertThat(mapped.getHeader("X-Removed"), equalTo(Collections.singletonList("restored")));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldCaptureStreamIdOnlyOverHttp2() {
        String streamIdHeader = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text().toString();

        // given an HTTP/2 request carrying the stream id
        FullHttpRequest http2 = requestWith(new String[][]{{streamIdHeader, "7"}});
        try {
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(http2, null, null, null, Protocol.HTTP_2);
            assertThat(mapped.getStreamId(), is(7));
        } finally {
            http2.release();
        }

        // given an HTTP/1.1 request forging the stream id — it must be ignored
        FullHttpRequest http1 = requestWith(new String[][]{{streamIdHeader, "7"}});
        try {
            HttpRequest mapped = mapper()
                .mapFullHttpRequestToMockServerRequest(http1, null, null, null, Protocol.HTTP_1_1);
            assertThat(mapped.getStreamId(), is(nullValue()));
        } finally {
            http1.release();
        }
    }

    private static List<String> valuesFor(Headers headers, String name) {
        HttpRequest holder = new HttpRequest().withHeaders(headers);
        return holder.getHeader(name);
    }

    private static String firstFor(Headers headers, String name) {
        return new HttpRequest().withHeaders(headers).getFirstHeader(name);
    }

    private static Header headerFor(HttpRequest request, String name) {
        for (Header header : request.getHeaderList()) {
            if (header.getName().getValue().equals(name)) {
                return header;
            }
        }
        throw new AssertionError("no header named '" + name + "' in " + request.getHeaderList());
    }
}
