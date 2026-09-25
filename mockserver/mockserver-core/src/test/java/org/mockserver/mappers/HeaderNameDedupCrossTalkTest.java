package org.mockserver.mappers;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;
import org.mockserver.model.Protocol;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.NottableString.headerName;

/**
 * Hazard-class-1 (reuse and pooling) evidence for the shared well-known header-name wrappers:
 * many threads, each recording requests whose well-known header names are the shared instances but
 * whose values are distinguishable per request, asserting that no request ever observes another
 * request's header value and that the shared name wrapper is never mutated by the concurrency.
 */
public class HeaderNameDedupCrossTalkTest {

    private static final String[] WELL_KNOWN_NAMES = {"Host", "Content-Type", "Accept", "User-Agent", "Connection"};

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    private FullHttpRequestToMockServerHttpRequest mapper() {
        return new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, false, null, 80);
    }

    @Test
    public void sharedHeaderNamesCarryNoCrossRequestState() throws Exception {
        int threads = 16;
        int iterationsPerThread = 750;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    FullHttpRequestToMockServerHttpRequest mapper = mapper();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        // a value unique to this thread+iteration behind each shared well-known name
                        String stamp = "t" + threadId + "-i" + i;
                        FullHttpRequest nettyRequest =
                            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
                        try {
                            for (String name : WELL_KNOWN_NAMES) {
                                nettyRequest.headers().add(name, name + "=" + stamp);
                            }
                            HttpRequest result = mapper.mapFullHttpRequestToMockServerRequest(
                                nettyRequest, null, null, null, Protocol.HTTP_1_1);

                            for (String name : WELL_KNOWN_NAMES) {
                                String expected = name + "=" + stamp;
                                String actual = result.getFirstHeader(name);
                                if (!expected.equals(actual)) {
                                    failures.add("cross-talk: expected '" + expected + "' but header '"
                                        + name + "' held '" + actual + "'");
                                }
                            }

                            // the name wrapper recorded must be the shared instance (dedup is active)
                            // and its own value must never have been mutated to another request's stamp
                            for (Header header : result.getHeaderList()) {
                                NottableString recordedName = header.getName();
                                NottableString shared = headerName(recordedName.getValue());
                                if (recordedName != shared) {
                                    failures.add("name '" + recordedName.getValue() + "' was not the shared instance");
                                }
                                if (!isWellKnownName(recordedName.getValue())) {
                                    failures.add("unexpected header name recorded: " + recordedName.getValue());
                                }
                            }
                        } finally {
                            nettyRequest.release();
                        }
                    }
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat("workers did not finish in time", done.await(120, TimeUnit.SECONDS), is(true));
        pool.shutdownNow();

        if (firstError.get() != null) {
            throw new AssertionError("worker threw", firstError.get());
        }
        assertThat("no cross-talk expected but saw: " + failures, failures, is(empty()));

        // the shared wrappers survived the run unmutated
        for (String name : WELL_KNOWN_NAMES) {
            assertThat(headerName(name).getValue(), equalTo(name));
            assertThat(headerName(name).isNot(), is(false));
        }
    }

    private static boolean isWellKnownName(String value) {
        for (String name : WELL_KNOWN_NAMES) {
            if (name.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
