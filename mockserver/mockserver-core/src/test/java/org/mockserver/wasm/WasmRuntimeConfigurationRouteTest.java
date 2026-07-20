package org.mockserver.wasm;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.serialization.model.ConfigurationDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Asserts the WASM limits reach the enforcement point through EVERY configuration route.
 *
 * <p>{@code wasmExecutionTimeoutMillis} and {@code wasmMaxMemoryPages} were read from the STATIC
 * {@code ConfigurationProperties} store at the point of use, while {@code ConfigurationDTO} happily
 * accepted both and {@code PUT /mockserver/configuration} returned 200 with the new value echoed back.
 * That is the same "reports success while not acting" shape this unit exists to remove — so it is guarded
 * here per route rather than only for the system property that happened to work.
 */
public class WasmRuntimeConfigurationRouteTest {

    /**
     * A module whose {@code match} export loops forever — the only reliable way to observe that an
     * execution budget is actually being enforced. Reuses the hand-assembled module from
     * {@link WasmRuntimeExecutionBudgetTest} rather than duplicating the WASM byte encoding.
     */
    private static final byte[] INFINITE_LOOP_MODULE = WasmRuntimeExecutionBudgetTest.infiniteLoopMatchModule();

    @Before
    public void setUp() {
        ConfigurationProperties.wasmExecutionTimeoutMillis(5000L);
    }

    @After
    public void tearDown() {
        ConfigurationProperties.wasmExecutionTimeoutMillis(5000L);
        WasmRuntime.invalidateAll();
    }

    @Test(timeout = 20000)
    public void executionBudgetSetOnConfigurationInstanceMustBeEnforced() {
        Configuration configuration = Configuration.configuration().wasmExecutionTimeoutMillis(250L);

        long start = System.currentTimeMillis();
        boolean matched = new WasmRuntime(INFINITE_LOOP_MODULE, configuration).callMatch("body");
        long elapsed = System.currentTimeMillis() - start;

        assertThat("a timed-out module must fail closed", matched, is(false));
        assertThat("the budget from the Configuration instance must be the one enforced, not the "
                + "5000ms static default", elapsed, is(lessThan(3000L)));
    }

    @Test(timeout = 20000)
    public void executionBudgetAppliedViaConfigurationDtoMustBeEnforced() {
        // the exact mutation PUT /mockserver/configuration performs
        Configuration configuration = Configuration.configuration();
        new ConfigurationDTO().setWasmExecutionTimeoutMillis(250L).applyTo(configuration);

        long start = System.currentTimeMillis();
        boolean matched = new WasmRuntime(INFINITE_LOOP_MODULE, configuration).callMatch("body");
        long elapsed = System.currentTimeMillis() - start;

        assertThat("a timed-out module must fail closed", matched, is(false));
        assertThat("a budget applied through ConfigurationDTO (the PUT /mockserver/configuration route) "
                + "must reach the enforcement point", elapsed, is(lessThan(3000L)));
    }

    @Test(timeout = 20000)
    public void executionBudgetSetViaSystemPropertyMustStillBeEnforced() {
        ConfigurationProperties.wasmExecutionTimeoutMillis(250L);

        long start = System.currentTimeMillis();
        // null configuration => the static store is the correct source
        boolean matched = new WasmRuntime(INFINITE_LOOP_MODULE, (Configuration) null).callMatch("body");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(matched, is(false));
        assertThat(elapsed, is(lessThan(3000L)));
    }

    @Test
    public void memoryPageLimitMustComeFromTheConfigurationInstance() throws Exception {
        // wasmMaxMemoryPages had the same enforcement split. Provoking linear-memory exhaustion to observe
        // it would be disproportionate, so the constructed limit is inspected directly — deliberately by
        // reflection rather than by widening the production API with a test-only accessor.
        Configuration configuration = Configuration.configuration().wasmMaxMemoryPages(7);

        assertThat(intField(new WasmRuntime(INFINITE_LOOP_MODULE, configuration), "maxMemoryPages"), is(7));
    }

    @Test
    public void nullConfigurationFallsBackToTheStaticStore() throws Exception {
        ConfigurationProperties.wasmExecutionTimeoutMillis(1234L);

        assertThat(longField(new WasmRuntime(INFINITE_LOOP_MODULE, (Configuration) null), "executionTimeoutMillis"),
            is(1234L));
    }

    private static int intField(WasmRuntime runtime, String name) throws Exception {
        java.lang.reflect.Field field = WasmRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(runtime);
    }

    private static long longField(WasmRuntime runtime, String name) throws Exception {
        java.lang.reflect.Field field = WasmRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(runtime);
    }
}
