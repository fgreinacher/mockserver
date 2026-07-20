package org.mockserver.wasm;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Guards {@link WasmRuntime}'s wall-clock execution budget: a user-supplied module has unrestricted
 * control flow, so without a budget a module containing an unbounded loop pins the calling thread
 * forever — and because {@code callMatch} runs during request matching, that wedges matcher threads
 * and starves the event loop.
 * <p>
 * Every test carries a JUnit {@code timeout} so a regression <em>fails the build</em> rather than
 * hanging CI indefinitely. The JUnit timeouts are set well above the budgets under test so they only
 * fire on a genuine regression, never on a slow machine.
 */
public class WasmRuntimeExecutionBudgetTest {

    private long originalTimeout;

    @Before
    public void saveConfig() {
        originalTimeout = ConfigurationProperties.wasmExecutionTimeoutMillis();
    }

    @After
    public void restoreConfig() {
        ConfigurationProperties.wasmExecutionTimeoutMillis(originalTimeout);
    }

    /**
     * A hand-assembled WASM module exporting {@code memory} and {@code match(i32,i32) -> i32}, whose
     * {@code match} body is {@code (loop (br 0))} — an unbounded loop that never returns.
     * <p>
     * Assembled by hand rather than compiled because the repo's other WASM fixtures are prebuilt Rust
     * artifacts from {@code examples/wasm/} (there is no build-time toolchain in the test path, and no
     * {@code wat2wasm} available), and because a hostile "never returns" module is not something we
     * want to ship as an example. At 59 bytes the binary is small enough to stay readable inline.
     */
    static byte[] infiniteLoopMatchModule() {
        return new byte[]{
            // magic "\0asm", version 1
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            // type section: one type, (i32, i32) -> i32
            0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f,
            // function section: one function, of type 0
            0x03, 0x02, 0x01, 0x00,
            // memory section: one memory, min 1 page, no max
            0x05, 0x03, 0x01, 0x00, 0x01,
            // export section: two exports...
            0x07, 0x12, 0x02,
            // ...  "memory" -> memory 0
            0x06, 0x6d, 0x65, 0x6d, 0x6f, 0x72, 0x79, 0x02, 0x00,
            // ...  "match"  -> func 0
            0x05, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x00, 0x00,
            // code section: one body — no locals; loop; br 0; end; i32.const 0; end
            0x0a, 0x0b, 0x01, 0x09, 0x00, 0x03, 0x40, 0x0c, 0x00, 0x0b, 0x41, 0x00, 0x0b
        };
    }

    /**
     * The prebuilt Rust example from {@code examples/wasm/rust/}: matches when the body contains a
     * JSON-style {@code "amount"} greater than 1000. Used as the "well-behaved, fast" module.
     */
    private static byte[] amountOver1000Module() throws IOException {
        try (InputStream in = WasmRuntimeExecutionBudgetTest.class.getResourceAsStream("amount-over-1000.wasm")) {
            assertThat("test resource amount-over-1000.wasm must be on the classpath", in, notNullValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Test(timeout = 30_000)
    public void shouldFailClosedWhenModuleExceedsExecutionBudget() {
        WasmRuntime runtime = new WasmRuntime(infiniteLoopMatchModule(), 256, 500L);

        long start = System.currentTimeMillis();
        boolean matched = runtime.callMatch("anything");
        long elapsed = System.currentTimeMillis() - start;

        assertThat("an unbounded module must fail closed, not match", matched, is(false));
        assertThat("callMatch must return shortly after the budget expires, not hang", elapsed, lessThan(15_000L));
    }

    @Test(timeout = 30_000)
    public void shouldNotBlockTheCallerWaitingForTheInterruptedWorkerToDie() {
        WasmRuntime runtime = new WasmRuntime(infiniteLoopMatchModule(), 256, 300L);

        // three sequential unbounded calls must each cost roughly the budget and no more: the caller is
        // released on expiry without waiting for the abandoned worker, and a worker left interrupted must
        // not leak its interrupt flag into the next call
        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            assertThat(runtime.callMatch("anything"), is(false));
        }
        long elapsed = System.currentTimeMillis() - start;

        assertThat("each budgeted call must cost ~the budget, not accumulate waiting on dead workers",
            elapsed, lessThan(15_000L));
    }

    @Test(timeout = 30_000)
    public void shouldReadBudgetFromConfigurationWhenNotGivenExplicitly() {
        ConfigurationProperties.wasmExecutionTimeoutMillis(500L);

        // the two-arg and one-arg constructors must both pick the budget up from configuration
        long start = System.currentTimeMillis();
        assertThat(new WasmRuntime(infiniteLoopMatchModule()).callMatch("anything"), is(false));
        assertThat(new WasmRuntime(infiniteLoopMatchModule(), 256).callMatch("anything"), is(false));
        long elapsed = System.currentTimeMillis() - start;

        assertThat("configured budget must be applied by the default constructors", elapsed, lessThan(20_000L));
    }

    @Test(timeout = 30_000)
    public void shouldStillMatchNormalModuleUnderTheBudget() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module(), 256, 5000L);

        assertThat(runtime.callMatch("{\"amount\": 5000}"), is(true));
        assertThat(runtime.callMatch("{\"amount\": 10}"), is(false));
    }

    @Test(timeout = 30_000)
    public void shouldStillMatchNormalModuleRepeatedlyUnderTheBudget() throws IOException {
        // the budget covers parse + instantiate + invoke, and parsing is cached across calls; repeated
        // calls on one runtime must all match, i.e. the budget adds no cumulative cost or state
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module(), 256, 5000L);

        for (int i = 0; i < 25; i++) {
            assertThat(runtime.callMatch("{\"amount\": 5000}"), is(true));
        }
    }

    @Test(timeout = 30_000)
    public void shouldTreatZeroBudgetAsUnlimited() throws IOException {
        // deliberately a FAST module: proving "unlimited" with an unbounded module would hang forever.
        // What this pins is that a zero budget still executes correctly (running inline on the calling
        // thread) rather than being read as "expire immediately", which would fail every match closed.
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module(), 256, 0L);

        assertThat(runtime.callMatch("{\"amount\": 5000}"), is(true));
        assertThat(runtime.callMatch("{\"amount\": 10}"), is(false));
    }

    @Test(timeout = 30_000)
    public void shouldTreatNegativeBudgetAsUnlimited() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module(), 256, -1L);

        assertThat(runtime.callMatch("{\"amount\": 5000}"), is(true));
    }

    @Test(timeout = 30_000)
    public void shouldNotLeaveTheCallingThreadInterruptedAfterATimeout() {
        WasmRuntime runtime = new WasmRuntime(infiniteLoopMatchModule(), 256, 300L);

        assertThat(runtime.callMatch("anything"), is(false));

        // the interrupt targets the abandoned worker, never the caller — a matcher thread left
        // spuriously interrupted would corrupt unrelated work further up the request path
        assertThat("calling thread must not be left interrupted", Thread.currentThread().isInterrupted(), is(false));
    }

    @Test(timeout = 30_000)
    public void shouldFailClosedAndPreserveInterruptWhenCallingThreadIsInterrupted() throws Exception {
        WasmRuntime runtime = new WasmRuntime(infiniteLoopMatchModule(), 256, 60_000L);

        // run on a thread we control so interrupting the caller (not the guest) is observable
        boolean[] matched = new boolean[1];
        boolean[] interruptRestored = new boolean[1];
        Thread caller = new Thread(() -> {
            matched[0] = runtime.callMatch("anything");
            interruptRestored[0] = Thread.currentThread().isInterrupted();
        });
        caller.setDaemon(true);
        caller.start();

        Thread.sleep(500);
        caller.interrupt();
        caller.join(15_000);

        assertThat("caller must not still be blocked in the WASM call", caller.isAlive(), is(false));
        assertThat("an interrupted call must fail closed", matched[0], is(false));
        assertThat("the caller's interrupt flag must be restored, not swallowed", interruptRestored[0], is(true));
    }

    @Test(timeout = 30_000)
    public void shouldFailClosedForAnUnboundedlyRecursiveModule() {
        WasmRuntime runtime = new WasmRuntime(infinitelyRecursiveMatchModule(), 256, 30_000L);

        // Stack exhaustion, the other way a module runs away. Note this does NOT exercise the
        // catch(Throwable) boundary: chicory 1.7.5 converts the host StackOverflowError into a
        // ChicoryException at its CALL boundary (InterpreterMachine, "call stack exhausted"), so this
        // already fails closed through the Exception path. It is kept as a regression guard on that
        // behaviour — if a future chicory stops wrapping, the Throwable boundary catches it and this
        // test keeps passing rather than the matcher blowing up. The budget is deliberately generous
        // so this asserts stack handling, not the timeout.
        assertThat(runtime.callMatch("anything"), is(false));
    }

    /**
     * As {@link #infiniteLoopMatchModule()} but the body is {@code (call 0)} — unbounded self-recursion,
     * which exhausts the host interpreter's Java stack.
     */
    private static byte[] infinitelyRecursiveMatchModule() {
        return new byte[]{
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f,
            0x03, 0x02, 0x01, 0x00,
            0x05, 0x03, 0x01, 0x00, 0x01,
            0x07, 0x12, 0x02,
            0x06, 0x6d, 0x65, 0x6d, 0x6f, 0x72, 0x79, 0x02, 0x00,
            0x05, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x00, 0x00,
            // code: no locals; i32.const 0; i32.const 0; call 0; end
            0x0a, 0x0a, 0x01, 0x08, 0x00, 0x41, 0x00, 0x41, 0x00, 0x10, 0x00, 0x0b
        };
    }
}
