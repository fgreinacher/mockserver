package org.mockserver.wasm;

/**
 * Signals that a WASM {@code shape_response} module failed in a way that makes its returned response
 * unusable — it trapped, returned an out-of-bounds or oversized memory region, or returned bytes that
 * are not a valid response JSON object.
 * <p>
 * This is deliberately distinct from the "module has no {@code shape_response} export" and "module opted
 * out" cases, which {@link WasmRuntime#callShape} signals by returning {@code null} (no error, no log).
 * A {@code WasmShapeException} tells {@link WasmResponseShaper} to fall back to the unshaped response and
 * warn <em>once</em> per module, keeping the request fail-safe — a broken shaper never 500s the request.
 */
public class WasmShapeException extends RuntimeException {

    public WasmShapeException(String message) {
        super(message);
    }

    public WasmShapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
