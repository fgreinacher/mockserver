package org.mockserver.wasm;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Packaging guard: the WASM interpreter must reach the module that <strong>ships the server</strong>.
 * <p>
 * The equivalent ABI tests in {@code mockserver-core} cannot catch this. Maven puts an
 * {@code <optional>true</optional>} dependency on its own module's classpath but does <em>not</em>
 * propagate it to consumers, so chicory is always present in core's test JVM regardless of how it is
 * declared. {@code mockserver-netty} is a consumer of core and is the module the standalone
 * {@code jar-with-dependencies} and the Docker image are assembled from — its runtime classpath is
 * therefore the one that decides whether a released MockServer can actually execute a WASM rule.
 * <p>
 * When chicory was declared optional this test failed: {@link WasmRuntime#callMatch} caught the
 * resulting {@link NoClassDefFoundError} at its fail-closed boundary and returned {@code false}, so an
 * advertised, documented feature silently never matched in every shipped artifact.
 * <p>
 * This is deliberately a behavioural test rather than a {@code Class.forName} probe: it asserts the
 * interpreter genuinely executes a module here, not merely that some class resolves.
 */
public class WasmRuntimeShippedWithServerTest {

    /**
     * The prebuilt Rust example from {@code examples/wasm/rust/} (also used by the core ABI tests):
     * exports {@code match(i32 ptr, i32 len) -> i32} and matches a JSON-style {@code "amount"} strictly
     * greater than 1000.
     */
    private static byte[] amountOver1000Module() throws IOException {
        try (InputStream in = WasmRuntimeShippedWithServerTest.class.getResourceAsStream("amount-over-1000.wasm")) {
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

    @Test
    public void shouldExecuteWasmModuleFromTheModuleThatShipsTheServer() throws IOException {
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());

        // fails closed to false when the interpreter is absent from the shipped classpath
        assertThat("WASM rule should match — a false here means the interpreter is missing from the "
            + "server module's runtime classpath, so WASM matching is silently dead in the shipped "
            + "jar and Docker image", runtime.callMatch("{\"amount\": 5000}"), is(true));
    }

    @Test
    public void shouldNotMatchWhenModuleReportsNoMatch() throws IOException {
        // guards against the assertion above being satisfiable by a runtime that blindly returns true
        WasmRuntime runtime = new WasmRuntime(amountOver1000Module());

        assertThat(runtime.callMatch("{\"amount\": 10}"), is(false));
    }
}
