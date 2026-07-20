package org.mockserver.mock.action.http;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Mid-stream {@code grpc-timeout} enforcement for a server-streaming RPC.
 * <p>
 * This is asserted at handler level, on an {@link EmbeddedChannel} whose scheduler is driven
 * manually, because the end-to-end real-client test <strong>cannot</strong> distinguish the two
 * outcomes that matter: grpc-java reports {@code DEADLINE_EXCEEDED} whether the server terminated
 * the stream or the client simply gave up locally at the same instant. Here the frames MockServer
 * actually wrote are observable directly, so "the server terminated and stopped emitting" is
 * provable rather than inferred.
 */
public class GrpcStreamDeadlineTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String METHOD = "ListGreetings";
    private static final String DESCRIPTOR = "src/test/resources/grpc/greeting.dsc";

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private GrpcProtoDescriptorStore descriptorStore;
    private Scheduler scheduler;
    private GrpcStreamResponseActionHandler handler;
    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        descriptorStore = new GrpcProtoDescriptorStore(mockServerLogger);
        descriptorStore.loadDescriptorSetFromPath(Paths.get(DESCRIPTOR));
        Configuration configuration = Configuration.configuration();
        scheduler = new Scheduler(configuration, mockServerLogger);
        handler = new GrpcStreamResponseActionHandler(
            mockServerLogger, scheduler, descriptorStore, configuration, null);
        // a no-op handler so the pipeline has a context to write from
        channel = new EmbeddedChannel(new io.netty.channel.ChannelDuplexHandler());
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private HttpRequest streamRequest(String grpcTimeout) {
        HttpRequest request = HttpRequest.request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader("x-grpc-service", SERVICE)
            .withHeader("x-grpc-method", METHOD);
        if (grpcTimeout != null) {
            request.withHeader("grpc-timeout", grpcTimeout);
        }
        return request;
    }

    private List<Object> drainOutbound() {
        List<Object> written = new ArrayList<>();
        Object next;
        while ((next = channel.readOutbound()) != null) {
            written.add(next);
        }
        return written;
    }

    /**
     * The deadline elapses while messages are still pending: the stream must terminate with a
     * DEADLINE_EXCEEDED trailer, and no message frame may be written.
     */
    @Test
    public void shouldTerminateMidStreamWithDeadlineExceededAndEmitNoFurtherMessages() throws Exception {
        handler.handle(
            GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                // each message is delayed well past the deadline, so the deadline necessarily
                // lands between messages while the emission chain is still pending
                .withMessage("{\"greeting\":\"one\"}", new Delay(TimeUnit.SECONDS, 5))
                .withMessage("{\"greeting\":\"two\"}", new Delay(TimeUnit.SECONDS, 5)),
            channel.pipeline().lastContext(),
            streamRequest("100m"));

        // the initial response goes out immediately
        List<Object> initial = drainOutbound();
        assertThat(initial.size(), is(1));
        assertThat(initial.get(0) instanceof DefaultHttpResponse, is(true));

        // drive the event loop past the 100ms deadline
        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        List<Object> afterDeadline = drainOutbound();
        LastHttpContent trailers = null;
        for (Object written : afterDeadline) {
            assertThat("no message frame may be written once the deadline has terminated the stream",
                written instanceof DefaultHttpContent && !(written instanceof LastHttpContent), is(false));
            if (written instanceof LastHttpContent) {
                trailers = (LastHttpContent) written;
            }
        }
        assertThat("the stream must be terminated with a trailer", trailers, is(notNullValue()));
        assertThat(trailers.trailingHeaders().get(GrpcStatusMapper.GRPC_STATUS_HEADER),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));

        // the pending 5s message must never arrive, even after its delay elapses
        Thread.sleep(6000);
        channel.runPendingTasks();
        for (Object written : drainOutbound()) {
            assertThat("nothing may follow the terminal trailer: " + written,
                written instanceof DefaultHttpContent, is(false));
        }
    }

    /**
     * Exactly one terminal trailer. Normal completion and the deadline are mutually exclusive, so a
     * stream that finishes before its deadline must not then be terminated a second time when the
     * timer would have fired.
     */
    @Test
    public void shouldNotWriteASecondTrailerWhenTheStreamCompletesBeforeTheDeadline() throws Exception {
        handler.handle(
            GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\":\"one\"}"),
            channel.pipeline().lastContext(),
            streamRequest("100m"));

        // let the (undelayed) message and normal completion run
        Thread.sleep(500);
        channel.runPendingTasks();
        // then push past where the deadline would have fired
        channel.advanceTimeBy(500, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        int trailerCount = 0;
        for (Object written : drainOutbound()) {
            if (written instanceof LastHttpContent) {
                trailerCount++;
                assertThat("normal completion must keep its own status, not the deadline's",
                    ((LastHttpContent) written).trailingHeaders().get(GrpcStatusMapper.GRPC_STATUS_HEADER),
                    is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.OK.getCode())));
            }
        }
        assertThat("exactly one terminal trailer may be written", trailerCount, is(1));
    }

    /**
     * With no {@code grpc-timeout} the stream is unaffected — no timer is scheduled and the stream
     * completes normally.
     */
    @Test
    public void shouldNotEnforceADeadlineWhenTheClientSentNoTimeout() throws Exception {
        handler.handle(
            GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\":\"one\"}"),
            channel.pipeline().lastContext(),
            streamRequest(null));

        Thread.sleep(500);
        channel.runPendingTasks();
        channel.advanceTimeBy(10, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        boolean sawMessage = false;
        LastHttpContent trailers = null;
        for (Object written : drainOutbound()) {
            if (written instanceof LastHttpContent) {
                trailers = (LastHttpContent) written;
            } else if (written instanceof DefaultHttpContent) {
                sawMessage = true;
            }
        }
        assertThat("the message must be delivered", sawMessage, is(true));
        assertThat(trailers, is(notNullValue()));
        assertThat(trailers.trailingHeaders().get(GrpcStatusMapper.GRPC_STATUS_HEADER),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.OK.getCode())));
    }

}
