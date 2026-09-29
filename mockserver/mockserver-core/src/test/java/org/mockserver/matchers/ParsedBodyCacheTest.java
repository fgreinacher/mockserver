package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.JsonBody;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.xml.StringToXmlDocumentParser;
import org.mockserver.xml.XPathEvaluator;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonPathBody.jsonPath;

/**
 * The request body is parsed once per candidate scan, and nothing parsed outlives the scan.
 */
public class ParsedBodyCacheTest {

    /**
     * Records the cache the scan's matchers were handed, so a test can inspect it after the scan.
     */
    static final class RecordingRequest extends HttpRequest {
        ParsedBodyCache used;

        @Override
        public ParsedBodyCache parsedBodyCacheForCurrentThread() {
            ParsedBodyCache cache = super.parsedBodyCacheForCurrentThread();
            if (used == null) {
                used = cache;
            }
            return cache;
        }
    }

    private static String largeJsonBody() {
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 20_000; i++) {
            body.append(i == 0 ? "" : ",").append("{\"sku\":\"SKU-").append(i).append("\",\"qty\":").append(i).append('}');
        }
        return body.append("]}").toString();
    }

    private static RequestMatchers requestMatchers(Configuration configuration, int jsonExpectations, int jsonPathExpectations) {
        RequestMatchers requestMatchers = new RequestMatchers(configuration, new MockServerLogger(configuration, ParsedBodyCacheTest.class), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        for (int i = 0; i < jsonExpectations; i++) {
            requestMatchers.add(new Expectation(request().withBody(new JsonBody("{\"items\":[{\"sku\":\"NONE-" + i + "\"}]}"))).thenRespond(response()), API);
        }
        for (int i = 0; i < jsonPathExpectations; i++) {
            requestMatchers.add(new Expectation(request().withBody(jsonPath("$.items[?(@.sku == 'NONE-" + i + "')]"))).thenRespond(response()), API);
        }
        return requestMatchers;
    }

    private static void assertReleased(RecordingRequest request) {
        // nothing survives the scan: the cache is emptied and the scope detached from the request
        assertThat(request.used.isEmpty(), is(true));
        assertThat(request.parsedBodyCacheForCurrentThread(), nullValue());
        assertThat("detached, so a new scope can be opened", request.openParsedBodyCache(), is(true));
        request.closeParsedBodyCache();
    }

    @Test
    public void scanParsesTheBodyOnceAndReleasesItWhenTheScanEnds() {
        Configuration configuration = configuration().logLevel("WARN");
        RequestMatchers requestMatchers = requestMatchers(configuration, 20, 20);
        RecordingRequest request = new RecordingRequest();
        request.withMethod("POST").withPath("/large").withBody(new JsonBody(largeJsonBody()));

        assertThat(requestMatchers.firstMatchingExpectation(request), nullValue());

        // forty candidates, one Jackson tree and one JSONPath document
        assertThat(request.used, notNullValue());
        assertThat(request.used.parses(), is(2));
        assertReleased(request);
    }

    @Test
    public void closestMatchRescanAlsoParsesOnceAndReleases() {
        Configuration configuration = configuration().logLevel("WARN");
        RequestMatchers requestMatchers = requestMatchers(configuration, 10, 10);
        RecordingRequest request = new RecordingRequest();
        request.withMethod("POST").withPath("/hint").withBody(new JsonBody("{\"items\":[{\"sku\":\"A\"}]}"));

        assertThat(requestMatchers.findClosestMatchHint(request), notNullValue());

        assertThat(request.used.parses(), is(2));
        assertReleased(request);
    }

    @Test
    public void scanWithoutJsonMatchersCreatesNoCache() {
        Configuration configuration = configuration().logLevel("WARN");
        RequestMatchers requestMatchers = new RequestMatchers(configuration, new MockServerLogger(configuration, ParsedBodyCacheTest.class), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        requestMatchers.add(new Expectation(request().withPath("/other")).thenRespond(response()), API);
        RecordingRequest request = new RecordingRequest();
        request.withMethod("POST").withPath("/exact").withBody(new JsonBody("{}"));

        requestMatchers.firstMatchingExpectation(request);

        assertThat(request.used, nullValue());
        assertThat(request.openParsedBodyCache(), is(true));
        request.closeParsedBodyCache();
    }

    @Test
    public void enclosingScopeIsReusedAndNotClosedByAnInnerScan() {
        Configuration configuration = configuration().logLevel("WARN");
        RequestMatchers requestMatchers = requestMatchers(configuration, 5, 5);
        HttpRequest request = request().withMethod("POST").withPath("/nested").withBody(new JsonBody("{\"items\":[]}"));
        assertThat(request.openParsedBodyCache(), is(true));
        try {
            requestMatchers.firstMatchingExpectation(request);
            requestMatchers.findClosestMatchHint(request);

            ParsedBodyCache outer = request.parsedBodyCacheForCurrentThread();
            assertThat("still open after both inner scans", outer, notNullValue());
            assertThat("both scans shared one parse per representation", outer.parses(), is(2));
            assertThat(request.openParsedBodyCache(), is(false));
        } finally {
            request.closeParsedBodyCache();
        }
        assertThat(request.parsedBodyCacheForCurrentThread(), nullValue());
    }

    @Test
    public void onlyTheOwningThreadSeesTheCache() throws Exception {
        HttpRequest request = request().withBody("{}");
        assertThat(request.openParsedBodyCache(), is(true));
        try {
            ParsedBodyCache cache = request.parsedBodyCacheForCurrentThread();
            AtomicReference<ParsedBodyCache> seenElsewhere = new AtomicReference<>(cache);
            AtomicBoolean openedElsewhere = new AtomicBoolean(true);
            CompletableFuture.runAsync(() -> {
                seenElsewhere.set(request.parsedBodyCacheForCurrentThread());
                openedElsewhere.set(request.openParsedBodyCache());
                // closing from a thread that does not own the scope must leave it open
                request.closeParsedBodyCache();
            }).get();
            assertThat(seenElsewhere.get(), nullValue());
            assertThat(openedElsewhere.get(), is(false));
            assertThat(request.parsedBodyCacheForCurrentThread(), sameInstance(cache));
        } finally {
            request.closeParsedBodyCache();
        }
    }

    @Test
    public void entriesAreKeyedOnTheBodyText() throws Exception {
        ParsedBodyCache cache = ParsedBodyCache.forCurrentThread();
        String body = "{\"a\":1}";

        assertThat(cache.jsonTree(body), sameInstance(cache.jsonTree(new String(body.toCharArray()))));
        assertThat(cache.parses(), is(1));
        assertThat(cache.jsonTree("{\"a\":2}").get("a").asInt(), is(2));
        assertThat(cache.parses(), is(2));
    }

    @Test
    public void noneStoresNothing() throws Exception {
        String body = "{\"a\":1}";

        assertThat(ParsedBodyCache.NONE.jsonTree(body) == ParsedBodyCache.NONE.jsonTree(body), is(false));
        assertThat(ParsedBodyCache.NONE.isEmpty(), is(true));
    }

    @Test
    public void jsonPathThatAppendsNeverWritesToTheSharedDocument() {
        ParsedBodyCache cache = ParsedBodyCache.forCurrentThread();
        String body = "{\"nums\":[1,2,3]}";
        JsonPathMatcher append = new JsonPathMatcher(new MockServerLogger(), "$.nums.append(4)");
        JsonPathMatcher four = new JsonPathMatcher(new MockServerLogger(), "$.nums[?(@ == 4)]");
        JsonPathMatcher three = new JsonPathMatcher(new MockServerLogger(), "$.nums[?(@ == 3)]");

        assertThat(three.matches(null, body, cache), is(true));
        assertThat(append.matches(null, body, cache), is(true));
        assertThat("the append ran on its own copy", four.matches(null, body, cache), is(false));
        assertThat(cache.parses(), is(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void jsonPathFunctionsAreTheAuditedSet() throws Exception {
        // JsonPathMatcher shares one parsed document across a scan and opts out only paths naming
        // "append", the one function audited as writing to the document it reads
        Map<String, ?> functions = (Map<String, ?>) Class.forName("com.jayway.jsonpath.internal.function.PathFunctionFactory")
            .getField("FUNCTIONS")
            .get(null);

        assertThat(
            "json-path's functions changed: audit each new function for writes to the document it reads, and extend "
                + "JsonPathMatcher's readsOnly guard for any that write, before updating this set",
            new TreeSet<>(functions.keySet()),
            is(new TreeSet<>(Arrays.asList("avg", "stddev", "sum", "min", "max", "concat", "length", "size", "append", "keys", "first", "last", "index")))
        );
    }

    @Test
    public void bodyMatchersKeepNoPerThreadState() {
        for (Class<?> type : new Class<?>[]{JsonStringMatcher.class, JsonPathMatcher.class, XPathMatcher.class, ParsedBodyCache.class, XPathEvaluator.class, StringToXmlDocumentParser.class}) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(type.getSimpleName() + "." + field.getName(), ThreadLocal.class.isAssignableFrom(field.getType()), is(false));
                }
            }
        }
    }
}
