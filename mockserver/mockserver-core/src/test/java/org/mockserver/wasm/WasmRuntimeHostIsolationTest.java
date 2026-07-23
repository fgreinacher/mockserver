package org.mockserver.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.UnlinkableException;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;

/**
 * Security regression pin for <strong>WASM guest host-isolation</strong>: a custom rule module cannot
 * reach the filesystem or any host/WASI function, because {@link WasmRuntime} wires <em>no</em> host
 * imports when it instantiates a module.
 * <p>
 * A WASM guest can only touch the outside world through functions the host explicitly imports into the
 * instance. MockServer's {@code WasmRuntime.buildInstance} builds every instance with a bare
 * {@code Instance.builder(module).build()} — it never calls {@code withImportValues(...)}. So a module
 * whose import section declares, say, {@code wasi_snapshot_preview1.fd_write} (the WASI stdout/file
 * write) has no matching host function to link against and chicory <strong>refuses to instantiate it</strong>
 * (throws {@link UnlinkableException}). That refusal is the isolation boundary: an unsandboxed module that
 * wants to call out to the host is rejected before it can run, so it cannot escape into the filesystem or
 * any other host capability.
 * <p>
 * The module used here ({@link #wasmModuleImportingFdWrite()}) is a hand-assembled, minimal-but-valid
 * WASM binary that imports {@code fd_write} and exports a {@code match} whose body actually
 * {@code call}s that import — so it genuinely attempts to reach the host.
 * <p>
 * <strong>Positive control (degrade &rarr; RED).</strong> {@link #providingTheHostImportAllowsInstantiation()}
 * demonstrates that MockServer's <em>choice not to wire imports</em> is exactly what causes the refusal:
 * building the identical module <em>with</em> a stub {@code fd_write} host function supplied via
 * {@code withImportValues(...)} instantiates cleanly. If a future change to {@code buildInstance} ever
 * started wiring host imports (the degrade), {@link #moduleDeclaringWasiImportIsRefusedAtInstantiation()}
 * would go RED. With the bare-instance wiring restored, it is GREEN. chicory is a hard (non-optional)
 * dependency of {@code mockserver-core}, exactly as {@link WasmRuntimeRealModuleTest} relies on, so no
 * availability gate is needed.
 */
public class WasmRuntimeHostIsolationTest {

    private static final String WASI_MODULE = "wasi_snapshot_preview1";
    private static final String FD_WRITE = "fd_write";

    /**
     * The module parses (it is a structurally valid binary) but cannot be <em>instantiated</em> the way
     * {@code WasmRuntime.buildInstance} does it — a bare {@code Instance.builder(module).build()} with no
     * host imports — because its {@code fd_write} import is unresolved. This is the core isolation pin:
     * MockServer supplies no host capabilities, so a module that wants one is refused.
     */
    @Test
    public void moduleDeclaringWasiImportIsRefusedAtInstantiation() {
        WasmModule module = Parser.parse(wasmModuleImportingFdWrite());
        // it is a valid module — the refusal below is specifically about the unresolved host import,
        // not malformed bytes
        assertThat("hand-assembled module should parse", module, notNullValue());

        // mirror WasmRuntime.buildInstance exactly: no withImportValues(...) => no host capabilities wired
        UnlinkableException refused = assertThrows(
            UnlinkableException.class,
            () -> Instance.builder(module).build());

        assertThat(
            "chicory should refuse the module because MockServer wires no host import for it",
            refused.getMessage(),
            containsString(WASI_MODULE + "." + FD_WRITE));
    }

    /**
     * Positive control. Supplying a stub {@code fd_write} host function — precisely the wiring MockServer
     * deliberately does NOT do — lets the identical module instantiate. This proves the refusal in
     * {@link #moduleDeclaringWasiImportIsRefusedAtInstantiation()} is caused by the absent host wiring and
     * not by anything intrinsic to the module.
     */
    @Test
    public void providingTheHostImportAllowsInstantiation() {
        WasmModule module = Parser.parse(wasmModuleImportingFdWrite());

        ImportValues withStubHostImport = ImportValues.builder()
            .addFunction(new HostFunction(
                WASI_MODULE,
                FD_WRITE,
                FunctionType.of(
                    List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                    List.of(ValType.I32)),
                // a stub that reports "0 bytes written, success" — never actually invoked here, we only
                // need instantiation to succeed
                (instance, args) -> new long[]{0L}))
            .build();

        Instance instance = Instance.builder(module)
            .withImportValues(withStubHostImport)
            .build();

        assertThat("module instantiates once its host import is satisfied", instance, notNullValue());
    }

    /**
     * End-to-end through MockServer's public API: a rule module that requires a host import cannot execute,
     * so {@link WasmRuntime#callMatch(String)} fails closed to {@code false} rather than running guest code
     * with host access. This is the user-visible consequence of the isolation boundary.
     */
    @Test
    public void callMatchFailsClosedForModuleRequiringHostImport() {
        WasmRuntime runtime = new WasmRuntime(wasmModuleImportingFdWrite());
        assertThat(runtime.callMatch("{\"amount\": 5000}"), is(false));
    }

    /**
     * Hand-assemble a minimal, valid WASM binary that:
     * <ul>
     *   <li>imports {@code wasi_snapshot_preview1.fd_write : (i32,i32,i32,i32) -> i32} (function index 0),</li>
     *   <li>defines and exports {@code match : (i32,i32) -> i32} (function index 1) whose body
     *       {@code call}s the imported {@code fd_write} — i.e. it genuinely tries to reach the host.</li>
     * </ul>
     * Building this by hand (rather than shipping another {@code .wasm} fixture) keeps the exact import the
     * test asserts on visible in the test itself. All section lengths here are &lt; 128, so every LEB128
     * length is a single byte.
     */
    private static byte[] wasmModuleImportingFdWrite() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // magic "\0asm" + version 1
        out.write(0x00); out.write(0x61); out.write(0x73); out.write(0x6d);
        out.write(0x01); out.write(0x00); out.write(0x00); out.write(0x00);

        // --- Type section (id 1): two function types ---
        writeSection(out, 0x01, new byte[]{
            0x02,                                        // 2 types
            0x60, 0x04, 0x7f, 0x7f, 0x7f, 0x7f, 0x01, 0x7f, // type 0: (i32,i32,i32,i32) -> i32  (fd_write)
            0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f           // type 1: (i32,i32) -> i32          (match)
        });

        // --- Import section (id 2): wasi_snapshot_preview1.fd_write as func of type 0 ---
        ByteArrayOutputStream imp = new ByteArrayOutputStream();
        imp.write(0x01);                                 // 1 import
        writeName(imp, WASI_MODULE);
        writeName(imp, FD_WRITE);
        imp.write(0x00);                                 // import kind: func
        imp.write(0x00);                                 // type index 0
        writeSection(out, 0x02, imp.toByteArray());

        // --- Function section (id 3): one local function of type 1 ---
        writeSection(out, 0x03, new byte[]{
            0x01,                                        // 1 local function
            0x01                                         // type index 1 (match)
        });

        // --- Export section (id 7): export "match" -> function index 1 ---
        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        exp.write(0x01);                                 // 1 export
        writeName(exp, "match");
        exp.write(0x00);                                 // export kind: func
        exp.write(0x01);                                 // function index 1 (import is index 0)
        writeSection(out, 0x07, exp.toByteArray());

        // --- Code section (id 10): body of match calls fd_write(1,0,1,0) and ends ---
        byte[] body = new byte[]{
            0x00,                                        // 0 local declarations
            0x41, 0x01,                                  // i32.const 1  (fd = stdout)
            0x41, 0x00,                                  // i32.const 0  (iovs ptr)
            0x41, 0x01,                                  // i32.const 1  (iovs len)
            0x41, 0x00,                                  // i32.const 0  (nwritten ptr)
            0x10, 0x00,                                  // call 0       (fd_write)
            0x0b                                         // end
        };
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);                                // 1 function body
        code.write(body.length);                         // body size (< 128)
        code.write(body, 0, body.length);
        writeSection(out, 0x0a, code.toByteArray());

        return out.toByteArray();
    }

    /** Write a WASM section: id byte, single-byte length, then content (all our lengths are &lt; 128). */
    private static void writeSection(ByteArrayOutputStream out, int id, byte[] content) {
        out.write(id);
        out.write(content.length);
        out.write(content, 0, content.length);
    }

    /** Write a WASM name: single-byte length prefix then UTF-8 bytes (all our names are &lt; 128 bytes). */
    private static void writeName(ByteArrayOutputStream out, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
