# WASM Custom Rule Engine

## Overview

MockServer supports WebAssembly (WASM) modules as custom body matchers. Users upload compiled WASM binaries via the control-plane REST API, then reference them by name in expectation body matchers. The WASM module receives the HTTP request body and returns a match/no-match decision.

This feature uses the **chicory** pure-Java WASM interpreter (no JNI or native code required), keeping MockServer's "runs anywhere Java runs" promise.

## Architecture

```mermaid
flowchart LR
    Client["API Client"] -->|"PUT /mockserver/wasm/modules?name=myRule"| HttpState
    HttpState -->|"store bytes"| WasmStore
    Request["Incoming HTTP Request"] --> Matcher["HttpRequestPropertiesMatcher"]
    Matcher -->|"body type = WASM"| WasmBodyMatcher
    WasmBodyMatcher -->|"get bytes by name"| WasmStore
    WasmBodyMatcher -->|"callMatch(body)"| WasmRuntime
    WasmRuntime -->|"chicory Parser/Instance"| Chicory["chicory interpreter"]
```

## Module ABI

Two export shapes are supported, both returning non-zero for a match. The runtime
**prefers the richer `match_request` export** when present and otherwise falls back to
the legacy body-only `match`, so existing body-only modules keep working unchanged.

### Legacy body-only — `match`

```wat
(func $match (export "match") (param $ptr i32) (param $len i32) (result i32)
  ;; Read $len bytes (the request body) from linear memory starting at $ptr
  ;; Return 1 for match, 0 for no match
)
```

The request body is written into the module's linear memory at offset 0 as UTF-8 bytes before calling `match`.

### Richer request envelope — `match_request`

```wat
(func $match_request (export "match_request") (param $ptr i32) (param $len i32) (result i32)
  ;; Read $len bytes (a UTF-8 JSON envelope) from linear memory starting at $ptr
  ;; Return 1 for match, 0 for no match
)
```

When a module exports `match_request`, the runtime writes a **JSON envelope** into
linear memory at offset 0 and calls `match_request(0, len)`. This lets a module inspect
the request method, path, query-string parameters, headers and cookies in addition to
the body. The envelope shape (**version 2**):

```json
{
  "version": 2,
  "method": "POST",
  "path": "/orders",
  "queryStringParameters": { "tenant": ["acme"] },
  "headers": { "X-Tenant": ["acme"], "Accept": ["application/json"] },
  "cookies": { "session": "abc123" },
  "body": "..."
}
```

- `version` — the envelope version the runtime emitted (`WasmRuntime.ENVELOPE_VERSION`).
  Present since version 2; absent in the original (version 1) envelope, so a module can
  treat a missing `version` as `1`.
- `queryStringParameters` — each parameter name mapped to an **array** of values
  (preserving repeated parameters, e.g. `?id=1&id=2`). Added in version 2.
- `headers` — each header name mapped to an **array** of values (preserving multi-valued
  headers).
- `cookies` — each cookie name mapped to its **single** string value. Added in version 2.
- `body` — the request body string, or JSON `null` when absent.

**Envelope versioning is additive and backward compatible.** Each version is a strict
superset of the previous one — new top-level fields are only ever added, never removed or
renamed — so a module built against version 1 (which reads only `method`/`path`/`headers`/`body`
and ignores unknown fields) keeps working unchanged against a version-2 envelope. A module
that only reads `body` behaves like a body-only matcher. The `mockserver-core`
`WasmRuntimeRequestV2AbiTest` guards this: it asserts the version-1 example module still
matches when driven with a version-2 envelope.

### Authoring SDK

`examples/wasm/sdk-rust/` is a minimal, dependency-free Rust crate
(`mockserver-wasm-sdk`) that gives module authors typed accessors over the envelope
(`Request::method/path/query_param/header/cookie/body`, plus `Request::version`) and an
`export_match_request!` macro that wires up the ABI. `query_param`/`cookie` require envelope
version 2; against an older (version-1) envelope they return `None`, so a rule stays backward
compatible. Two sample modules build on the SDK:

- `examples/wasm/rust-request/` matches on method + path + header (envelope v1 fields) and
  ships a prebuilt `match-request.wasm`.
- `examples/wasm/rust-request-v2/` matches on method + path + query parameter + cookie
  (envelope v2 fields) and ships a prebuilt `match-request-v2.wasm`.

`mockserver-core` uses both prebuilt binaries as ABI-guard test resources.

### Memory requirements

The module must declare at least one page of linear memory. The maximum memory is controlled by the `wasmMaxMemoryPages` configuration property (default: 256 pages = 16 MiB).

## Components

### WasmStore

`org.mockserver.wasm.WasmStore` -- thread-safe singleton backed by `ConcurrentHashMap<String, byte[]>`. Stores raw WASM module bytes keyed by user-chosen names. Reset on `/mockserver/reset`.

### WasmRuntime

`org.mockserver.wasm.WasmRuntime` -- parses the module with chicory's `Parser` and runs it via an `Instance`. Creates a fresh WASM instance per invocation for thread safety. Fails closed (returns `false`) on any error. The WASM instance is created with `MemoryLimits(min(declared.initialPages, effectiveMax), min(declared.maximumPages, wasmMaxMemoryPages))` — capping linear memory at `wasmMaxMemoryPages` while preserving the module's declared initial pages. `callMatch(WasmRequest)` builds the JSON envelope and invokes `match_request` when the module exports it; `callMatch(String)` is a body-only convenience that delegates to `callMatch(WasmRequest.ofBody(body))`. If `match_request` is absent it falls back to writing only the body and calling `match`.

### WasmRequest

`org.mockserver.wasm.WasmRequest` -- immutable view of the request parts a module can inspect (`method`, `path`, `queryStringParameters`, `headers`, `cookies`, `body`). `WasmBodyMatcher` builds one from the `MatchDifference` request context (populating query parameters and cookies from the matched `HttpRequest`); the `wasm/test` endpoint builds one from the supplied sample request. The fluent `withQueryStringParameter`/`withHeader`/`withCookie` builders add values.

### WasmBody

`org.mockserver.model.WasmBody` -- domain model for a WASM body matcher. Extends `Body<String>` with type `Body.Type.WASM`. The value is the module name.

### WasmBodyMatcher

`org.mockserver.matchers.WasmBodyMatcher` -- extends `BodyMatcher<String>`. Checks `ConfigurationProperties.wasmEnabled()` first; if WASM is disabled, returns `false` (no match). Otherwise looks up the module bytes from `WasmStore`, creates a `WasmRuntime`, and calls `callMatch()` with the request body string.

### WasmBodyDTO

`org.mockserver.serialization.model.WasmBodyDTO` -- Jackson-friendly DTO for JSON serialisation of `WasmBody`.

## REST API

| Method | Path | Description |
|--------|------|-------------|
| PUT | `/mockserver/wasm/modules?name={name}` | Upload a WASM module (raw bytes in body) |
| GET | `/mockserver/wasm/modules` | List loaded module names (JSON array) |
| DELETE | `/mockserver/wasm/modules?name={name}` | Remove a loaded module |
| POST | `/mockserver/wasm/test` | Test a module against a sample request (no live expectation needed) |

All endpoints require control-plane authentication when enabled. All WASM endpoints also require `wasmEnabled=true`; when disabled they return **403 Forbidden** with a descriptive message.

### `POST /mockserver/wasm/test`

Lets IDEs/users validate a module against a sample request without creating an
expectation. Request body:

```json
{
  "module": "<base64-encoded .wasm>",
  "request": {
    "method": "POST",
    "path": "/orders",
    "queryStringParameters": { "tenant": ["acme"] },
    "headers": { "X-Tenant": ["acme"] },
    "cookies": { "session": "abc123" },
    "body": "{}"
  }
}
```

Either `module` (base64 WASM bytes) **or** `moduleName` (a module already loaded via
`PUT /wasm/modules`) is required; `request` is optional (defaults to an empty body-only
request). Within `request`, `queryStringParameters`/`headers` accept either an array of
values or a single scalar per name; `cookies` maps each name to a single value. The response
is `{ "matched": true|false }`. The runtime fails closed, so an invalid module reports
`matched: false` rather than an error.

## Configuration

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `mockserver.wasmEnabled` | `MOCKSERVER_WASM_ENABLED` | `false` | Enable WASM body matching (must opt in) |
| `mockserver.wasmMaxMemoryPages` | `MOCKSERVER_WASM_MAX_MEMORY_PAGES` | `256` | Maximum WASM linear memory pages (64 KiB each) |

## JSON expectation format

```json
{
  "httpRequest": {
    "body": {
      "type": "WASM",
      "moduleName": "myMatcher"
    }
  },
  "httpResponse": {
    "statusCode": 200
  }
}
```

## Security considerations

- WASM modules run inside the chicory interpreter sandbox -- they cannot access the host filesystem, network, or JVM internals
- Fail-closed design: any WASM error (parse failure, runtime trap, missing export) returns no-match
- The feature is disabled by default (`wasmEnabled = false`) -- users must explicitly opt in. When disabled, all WASM control-plane endpoints return 403 Forbidden, and `WasmBodyMatcher` returns no-match.
- Linear memory is capped by `wasmMaxMemoryPages` (default 256 = 16 MiB) via chicory's `MemoryLimits`, enforced at instance creation
