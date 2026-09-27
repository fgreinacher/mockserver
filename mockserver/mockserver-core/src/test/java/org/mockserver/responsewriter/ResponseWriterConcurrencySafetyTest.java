package org.mockserver.responsewriter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Concurrency-safety guard for the response-write path exercised by the shared Connection-header
 * constants and the headers-only copy in {@code addConnectionHeader}. Many threads each build their
 * own response (as the control-plane / status endpoint and the per-request-cloned mock path do),
 * run it through the real {@link ResponseWriter#writeResponse} to wire bytes via the two outbound
 * handlers the server wires, then feed the bytes back through an {@link HttpResponseDecoder}; a
 * malformed response (e.g. a bare LF in the header block) makes the decoder throw and is recorded.
 * Every response must parse.
 */
public class ResponseWriterConcurrencySafetyTest {

    private static final int THREADS = 16;
    private static final int ITERATIONS_PER_THREAD = 3000;

    private static final class EncodeDecodeResponseWriter extends ResponseWriter {
        private final MockServerHttpResponseToFullHttpResponse mapper = new MockServerHttpResponseToFullHttpResponse(new MockServerLogger());
        private String failure;

        private EncodeDecodeResponseWriter(Configuration configuration) {
            super(configuration, new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            EmbeddedChannel encodeChannel = new EmbeddedChannel(new HttpResponseEncoder());
            EmbeddedChannel decodeChannel = new EmbeddedChannel(new HttpResponseDecoder(), new HttpObjectAggregator(1 << 20));
            try {
                List<DefaultHttpObject> mapped = mapper.mapMockServerResponseToNettyResponse(response);
                for (DefaultHttpObject object : mapped) {
                    encodeChannel.writeOutbound(object);
                }
                encodeChannel.finish();
                ByteBuf wire;
                while ((wire = encodeChannel.readOutbound()) != null) {
                    try {
                        decodeChannel.writeInbound(wire.retainedDuplicate());
                    } finally {
                        ReferenceCountUtil.release(wire);
                    }
                }
                decodeChannel.finish();
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t.getClass().getName() + ": " + t.getMessage();
                }
            } finally {
                encodeChannel.releaseInbound();
                encodeChannel.releaseOutbound();
                decodeChannel.releaseInbound();
                decodeChannel.releaseOutbound();
            }
        }
    }

    private static String buildJson() {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(i).append(",\"name\":\"record-").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    public void everyResponseParsesUnderConcurrentWrites() throws InterruptedException {
        final String json = buildJson();
        final Configuration configuration = Configuration.configuration();
        final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        final AtomicInteger ops = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final boolean keepAlive = (t % 2 == 0);
            new Thread(() -> {
                try {
                    start.await();
                    EncodeDecodeResponseWriter writer = new EncodeDecodeResponseWriter(configuration);
                    HttpRequest req = request("/mockserver/status").withKeepAlive(keepAlive);
                    for (int i = 0; i < ITERATIONS_PER_THREAD && failures.isEmpty(); i++) {
                        // A fresh response per op, as the control-plane/status endpoint and the
                        // per-request-cloned mock path both produce.
                        HttpResponse response = response().withStatusCode(200)
                            .withHeader("content-type", "application/json")
                            .withHeader("cache-control", "no-cache")
                            .withCookie("session", "abc123")
                            .withBody(json);
                        writer.failure = null;
                        writer.writeResponse(req, response, true);
                        ops.incrementAndGet();
                        if (writer.failure != null) {
                            failures.add(writer.failure);
                        }
                    }
                } catch (Throwable t2) {
                    failures.add(t2.getClass().getName() + ": " + t2.getMessage());
                } finally {
                    done.countDown();
                }
            }, "rw-" + t).start();
        }
        start.countDown();
        assertThat("threads finished", done.await(120, TimeUnit.SECONDS), is(true));
        assertThat("malformed/failed responses (" + ops.get() + " ops): " + failures,
            failures.isEmpty() ? Collections.<String>emptyList() : new ArrayList<>(failures),
            is(empty()));
    }
}
