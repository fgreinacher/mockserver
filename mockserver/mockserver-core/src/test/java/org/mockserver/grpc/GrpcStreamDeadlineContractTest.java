package org.mockserver.grpc;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The single-shot contract of {@link GrpcStreamDeadline}, which every streaming terminal path
 * relies on so that exactly one terminal trailer is ever written.
 * <p>
 * Tested directly rather than through a handler. The handlers' SYNCHRONOUS emission paths are
 * fronted by an {@link GrpcStreamDeadline#isTerminated()} check, so their {@code tryTerminate()}
 * calls are defence-in-depth there. On the asynchronous paths -- a breakpoint CLOSE decision and
 * a throw from a scheduled write -- {@code tryTerminate()} is the live guard, but neither is
 * reachable in a single-threaded harness, so a handler test would not discriminate. The primitive
 * is what actually carries the guarantee under contention, so the primitive is what is pinned here.
 */
public class GrpcStreamDeadlineContractTest {

    @Test
    public void shouldAllowExactlyOneClaim() {
        GrpcStreamDeadline deadline = new GrpcStreamDeadline();

        assertThat("the first claim wins", deadline.tryTerminate(), is(true));
        assertThat("a second claim must lose", deadline.tryTerminate(), is(false));
        assertThat("and it stays terminated", deadline.isTerminated(), is(true));
    }

    @Test
    public void shouldNotReportTerminatedBeforeAnyClaim() {
        assertThat(new GrpcStreamDeadline().isTerminated(), is(false));
    }

    /**
     * Under genuine contention exactly one caller may win, which is what makes the deadline and
     * normal completion mutually exclusive when they race on different threads.
     */
    @Test
    public void shouldAllowExactlyOneWinnerUnderContention() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            GrpcStreamDeadline deadline = new GrpcStreamDeadline();
            int contenders = 8;
            ExecutorService executor = Executors.newFixedThreadPool(contenders);
            try {
                List<Callable<Boolean>> claims = new ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    claims.add(deadline::tryTerminate);
                }
                int winners = 0;
                for (Future<Boolean> claimed : executor.invokeAll(claims)) {
                    if (claimed.get()) {
                        winners++;
                    }
                }
                assertThat("exactly one of " + contenders + " concurrent claims may win", winners, is(1));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Cancelling an unscheduled deadline must be safe — terminal paths call it unconditionally.
     */
    @Test
    public void shouldTolerateCancelWithoutSchedule() {
        GrpcStreamDeadline deadline = new GrpcStreamDeadline();
        deadline.cancel();
        deadline.cancel();
        assertThat(deadline.isTerminated(), is(false));
    }
}
