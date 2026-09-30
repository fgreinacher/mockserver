package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpTemplate.template;

/**
 * Floods a delayed-response and a templated expectation past {@code maxPendingDelayedResponses} /
 * {@code maxQueuedTemplateActions} through a real server and asserts that the backlog the server holds stays
 * at the bound, that every request over it is answered promptly with a 503, and that requests inside the
 * bound (and normal load afterwards) are served exactly as before.
 */
public class OverloadBoundIntegrationTest {

    private static final int FLOOD = 100;
    private static final int DELAYED_LIMIT = 20;
    private static final long DELAY_MILLIS = 5_000;

    private static EventLoopGroup clientEventLoopGroup;
    private static NettyHttpClient httpClient;

    @BeforeClass
    public static void createClient() {
        clientEventLoopGroup = new NioEventLoopGroup(4, new Scheduler.SchedulerThreadFactory(OverloadBoundIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
    }

    @AfterClass
    public static void stopClient() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test(timeout = 60_000)
    public void shouldBoundPendingDelayedResponsesAndAnswerTheExcessWith503() throws Exception {
        MockServer mockServer = new MockServer(configuration().maxPendingDelayedResponses(DELAYED_LIMIT), 0);
        try {
            int port = mockServer.getLocalPort();
            new MockServerClient("localhost", port)
                .when(request().withPath("/delayed"))
                .respond(response().withBody("delayed body").withDelay(MILLISECONDS, DELAY_MILLIS));
            Scheduler scheduler = mockServer.getScheduler();

            AtomicInteger peakPending = new AtomicInteger();
            AtomicInteger peakQueued = new AtomicInteger();
            AtomicBoolean sampling = new AtomicBoolean(true);
            Thread sampler = startSampler(sampling, () -> {
                peakPending.accumulateAndGet(scheduler.getPendingDelayedTaskCount(), Math::max);
                peakQueued.accumulateAndGet(scheduler.getQueuedTaskCount(), Math::max);
            });

            long start = System.nanoTime();
            List<CompletableFuture<HttpResponse>> responses = new ArrayList<>();
            List<Long> overloadedAtMillis = new ArrayList<>();
            for (int i = 0; i < FLOOD; i++) {
                responses.add(send("/delayed", port).whenComplete((response, throwable) -> {
                    if (response != null && response.getStatusCode() == 503) {
                        synchronized (overloadedAtMillis) {
                            overloadedAtMillis.add((System.nanoTime() - start) / 1_000_000L);
                        }
                    }
                }));
            }

            int ok = 0;
            int overloaded = 0;
            for (CompletableFuture<HttpResponse> future : responses) {
                HttpResponse response = future.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == 200) {
                    assertThat(response.getBodyAsString(), is("delayed body"));
                    ok++;
                } else {
                    assertThat(response.getStatusCode(), is(503));
                    assertThat(response.getFirstHeader("Retry-After"), is("1"));
                    overloaded++;
                }
            }
            sampling.set(false);
            sampler.join(5_000);

            assertThat("exactly the bound was admitted", ok, is(DELAYED_LIMIT));
            assertThat("everything over the bound was answered 503", overloaded, is(FLOOD - DELAYED_LIMIT));
            assertThat("the server never held more delayed responses than the bound", peakPending.get(), lessThanOrEqualTo(DELAYED_LIMIT));
            assertThat("the scheduler queue stayed at the bound", peakQueued.get(), lessThanOrEqualTo(DELAYED_LIMIT));
            for (long at : overloadedAtMillis) {
                assertThat("a 503 is immediate, not held for the delay", at, lessThan(DELAY_MILLIS));
            }
            awaitCondition(() -> scheduler.getPendingDelayedTaskCount() == 0);

            // normal load inside the bound afterwards is unaffected
            List<CompletableFuture<HttpResponse>> normal = new ArrayList<>();
            for (int i = 0; i < DELAYED_LIMIT; i++) {
                normal.add(send("/delayed", port));
            }
            for (CompletableFuture<HttpResponse> future : normal) {
                HttpResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode(), is(200));
            }
        } finally {
            mockServer.stop();
        }
    }

    @Test(timeout = 60_000)
    public void shouldBoundQueuedTemplateRendersAndAnswerTheExcessWith503() throws Exception {
        int templateLimit = 5;
        int flood = 30;
        MockServer mockServer = new MockServer(configuration().actionHandlerThreadCount(1).maxQueuedTemplateActions(templateLimit), 0);
        CountDownLatch release = new CountDownLatch(1);
        try {
            int port = mockServer.getLocalPort();
            new MockServerClient("localhost", port)
                .when(request().withPath("/template"))
                .respond(template(HttpTemplate.TemplateType.VELOCITY, "{ \"statusCode\": 200, \"body\": \"rendered $request.path\" }"));
            Scheduler scheduler = mockServer.getScheduler();

            // occupy the only template thread so renders queue deterministically
            CountDownLatch blockerStarted = new CountDownLatch(1);
            scheduler.scheduleTemplateAction(() -> {
                blockerStarted.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, false);
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

            List<CompletableFuture<HttpResponse>> responses = new ArrayList<>();
            for (int i = 0; i < flood; i++) {
                responses.add(send("/template", port));
            }

            // every request over the bound is answered while the admitted renders are still blocked
            awaitCondition(() -> responses.stream().filter(CompletableFuture::isDone).count() >= flood - templateLimit);
            MILLISECONDS.sleep(200);
            int overloaded = 0;
            for (CompletableFuture<HttpResponse> future : responses) {
                if (future.isDone()) {
                    assertThat(future.get().getStatusCode(), is(503));
                    overloaded++;
                }
            }
            assertThat("everything over the template queue bound was answered 503", overloaded, is(flood - templateLimit));
            assertThat("the template queue stayed at the bound", scheduler.getQueuedTemplateActionCount(), is(templateLimit));

            release.countDown();
            int rendered = 0;
            for (CompletableFuture<HttpResponse> future : responses) {
                HttpResponse response = future.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode() == 200) {
                    assertThat(response.getBodyAsString(), is("rendered /template"));
                    rendered++;
                }
            }
            assertThat("renders inside the bound were served", rendered, is(templateLimit));

            // normal load afterwards is unaffected
            for (int i = 0; i < 10; i++) {
                HttpResponse response = send("/template", port).get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode(), is(200));
                assertThat(response.getBodyAsString(), is("rendered /template"));
            }
        } finally {
            release.countDown();
            mockServer.stop();
        }
    }

    private static CompletableFuture<HttpResponse> send(String path, int port) {
        return httpClient.sendRequest(request().withPath(path).withHeader("Host", "localhost:" + port), new InetSocketAddress("localhost", port));
    }

    private static Thread startSampler(AtomicBoolean sampling, Runnable sample) {
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                sample.run();
                try {
                    MILLISECONDS.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "overload-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            MILLISECONDS.sleep(10);
        }
        assertThat(condition.getAsBoolean(), is(true));
    }
}
