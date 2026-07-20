package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.PublishOptions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A failing publish cycle must not kill scheduled publishing.
 *
 * <p>{@code ScheduledExecutorService.scheduleAtFixedRate} suppresses all subsequent executions if
 * one throws, and buries the throwable in a {@code ScheduledFuture} nobody reads. Since a publish
 * can now fail (an AMQP message reaching no queue, a Kafka delivery rejected), an unguarded cycle
 * would stop periodic publishing permanently and silently — and it would stay stopped even after
 * the cause cleared, which is a worse failure than the one being fixed.
 */
public class AsyncApiScheduledPublishResilienceTest {

    private AsyncApiMockOrchestrator orchestrator;

    @After
    public void tearDown() {
        if (orchestrator != null) {
            orchestrator.stop();
        }
    }

    @Test
    public void scheduleShouldSurviveAFailingPublishCycle() throws Exception {
        CountDownLatch cyclesAfterFailure = new CountDownLatch(3);
        AtomicInteger publishCount = new AtomicInteger();

        // every publish fails, as an unroutable AMQP channel or a rejected Kafka delivery would
        MessagePublisher alwaysFailing = new MessagePublisher() {
            @Override
            public void publish(String channel, String payload) {
                publishCount.incrementAndGet();
                cyclesAfterFailure.countDown();
                throw new RuntimeException("was not routed to any queue");
            }

            @Override
            public void publish(String channel, String payload, PublishOptions options) {
                publish(channel, payload);
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        orchestrator = new AsyncApiMockOrchestrator(specWithOneChannel(), alwaysFailing);
        orchestrator.startPublishing(50);

        assertThat("the schedule must keep running after a cycle throws",
            cyclesAfterFailure.await(10, TimeUnit.SECONDS), is(true));
        assertThat(publishCount.get(), greaterThanOrEqualTo(3));
        assertThat("the failure should be observable rather than only buried in a Future",
            orchestrator.getLastPublishFailure(), containsString("was not routed to any queue"));
    }

    @Test
    public void scheduleShouldRecoverOncePublishingSucceedsAgain() throws Exception {
        CountDownLatch succeeded = new CountDownLatch(2);
        AtomicInteger attempts = new AtomicInteger();

        // fails the first two cycles, then recovers — as it would once a consumer binds its queue
        MessagePublisher failsThenRecovers = new MessagePublisher() {
            @Override
            public void publish(String channel, String payload) {
                if (attempts.incrementAndGet() <= 2) {
                    throw new RuntimeException("was not routed to any queue");
                }
                succeeded.countDown();
            }

            @Override
            public void publish(String channel, String payload, PublishOptions options) {
                publish(channel, payload);
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        orchestrator = new AsyncApiMockOrchestrator(specWithOneChannel(), failsThenRecovers);
        orchestrator.startPublishing(50);

        assertThat("publishing must resume once the underlying cause clears",
            succeeded.await(10, TimeUnit.SECONDS), is(true));
        assertThat("a recovered cycle should clear the recorded failure",
            orchestrator.getLastPublishFailure(), is(nullValue()));
    }

    /**
     * One failing channel must not suppress the others.
     *
     * <p>Publishing is a loop over channels, so an exception escaping a single {@code publish()}
     * aborts the whole cycle — and since the cycle restarts at channel #1 each time, a spec whose
     * first channel has no bound queue would publish nothing at all, on any channel, forever. That
     * is a strictly wider failure than the silent single-channel drop being fixed. Every other
     * failure-path test here uses a single channel or an all-failing publisher and so cannot see it.
     */
    @Test
    public void oneFailingChannelShouldNotStopTheOthersPublishing() throws Exception {
        List<String> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();

        // only the first channel is unroutable, as an exchange with no bound queue would be
        MessagePublisher firstChannelFails = new MessagePublisher() {
            @Override
            public void publish(String channel, String payload) {
                if ("unbound".equals(channel)) {
                    throw new RuntimeException("was not routed to any queue");
                }
                delivered.add(channel);
            }

            @Override
            public void publish(String channel, String payload, PublishOptions options) {
                publish(channel, payload);
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        AsyncApiMockOrchestrator orchestrator =
            new AsyncApiMockOrchestrator(specWithChannels("unbound", "healthy-a", "healthy-b"),
                firstChannelFails);

        RuntimeException failure = null;
        try {
            orchestrator.publishAll();
        } catch (RuntimeException e) {
            failure = e;
        }

        assertThat("the healthy channels must still have been published",
            delivered, containsInAnyOrder("healthy-a", "healthy-b"));
        assertThat("the failure must still be reported", failure, is(notNullValue()));
        assertThat("the report should name the channel that failed",
            failure.getMessage(), containsString("unbound"));
    }

    private static AsyncApiSpec specWithChannels(String... names) throws Exception {
        JsonNode example = new ObjectMapper().readTree("{\"v\":1}");
        List<AsyncApiChannel> channels = new java.util.ArrayList<>();
        for (String name : names) {
            channels.add(new AsyncApiChannel(name, List.of(example), null));
        }
        return new AsyncApiSpec("2.6.0", "Test", channels);
    }

    private static AsyncApiSpec specWithOneChannel() throws Exception {
        JsonNode example = new ObjectMapper().readTree("{\"v\":1}");
        AsyncApiChannel channel = new AsyncApiChannel("events", List.of(example), null);
        return new AsyncApiSpec("2.6.0", "Test", List.of(channel));
    }
}
