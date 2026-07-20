# WASM Custom Rule Engine

## Overview

MockServer supports WebAssembly (WASM) modules as custom body matchers. Users upload compiled WASM binaries via the control-plane REST API, then reference them by name in expectation body matchers. The WASM module receives the HTTP request body and returns a match/no-match decision.

This feature uses the **chicory** pure-Java WASM interpreter (no JNI or native code required), keeping MockServer's "runs anywhere Java runs" promise.

## Packaging — chicory must NOT be optional

`com.dylibso.chicory:runtime` is declared in `mockserver-core/pom.xml` as an ordinary (non-optional)
dependency, and must stay that way.

Maven keeps an `<optional>true</optional>` dependency on its own module's classpath but does not
propagate it to consumers. Declaring chicory optional therefore left `mockserver-netty` — the module the
standalone `jar-with-dependencies` and every Docker image are assembled from — without an interpreter,
while still shipping the `org.mockserver.wasm` classes. Module upload and expectation registration both
still succeeded, so the feature looked healthy right up to the point of use, where every match failed
closed on a `NoClassDefFoundError`. Because it fails closed rather than erroring, the symptom is a silent
non-match plus a `WARNING` in the log — easy to mistake for a faulty module.

This is deliberately *unlike* GraalJS (see [response templates](../../jekyll-www.mock-server.com/mock_server/response_templates.html)),
which is genuinely optional: GraalVM is ~262 MB and gets its own `-graaljs` image variant plus a
documented add-on step. chicory is ~365 KB and WASM is gated off by default (`wasmEnabled=false`), so
bundling it is inert until opted into and the size argument does not apply.

Guarded by `WasmRuntimeShippedWithServerTest` in **mockserver-netty** — it must live in a *consumer* of
core, because chicory is present in core's own test JVM regardless of how it is declared, so an
equivalent test there cannot fail.

That is a general property of optional dependencies, not something specific to chicory, and it can be
re-verified by inspection at any time: `polyglot`, `js` and `slf4j-jdk14` are all still declared
`<optional>true</optional>` in `mockserver-core/pom.xml`, and all three appear on core's own test
classpath (`./mvnw dependency:build-classpath -pl mockserver-core -Dmdep.includeScope=test`). So for any
optional dependency backing a shipped feature, a test in the declaring module is structurally incapable
of detecting its absence from the assembled artifact — the guard has to sit in a module that consumes it.

This bug class has precedent here: see `changelog.md` (issue #2097), where the shaded standalone jar lost
its SLF4J provider by exactly the same mechanism — `slf4j-jdk14` declared optional, not propagated,
logging silently degraded to a no-op in the shipped artifact while every in-module test passed.

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

### Response shaping — `shape_response` (ABI v3)

WASM modules are pure predicates by default (`match_request` → bool). A module may **additionally**
export an optional response-shaping function, letting it compute the response rather than only matching
the request — the deeper extensibility play (WASM-computed dynamic responses).

```wat
(func $shape_response (export "shape_response") (param $ptr i32) (param $len i32) (result i64)
  ;; Read $len bytes (a UTF-8 JSON shape envelope) from linear memory at $ptr.
  ;; Write the response JSON somewhere in linear memory; return a packed (ptr << 32) | len
  ;; pointing at it. Return 0 to leave the response unchanged.
)
```

After a request matches an expectation whose WASM-body module exports `shape_response`, MockServer calls
it with a **shape envelope** (version 3) describing the response the expectation *would* return:

```json
{
  "version": 3,
  "request":  { "version": 2, "method": "POST", "path": "/shape", "queryStringParameters": {}, "headers": {}, "cookies": {}, "body": "{}" },
  "response": { "statusCode": 201, "headers": { "Content-Type": ["application/json"] }, "body": "{\"name\":\"acme\"}" }
}
```

- `request` — the **same** envelope `match_request` modules receive (so a module can shape based on any
  request part), nested under `request` with its own `version: 2`.
- `response` — the materialised response (after any templating/schema/GraphQL synthesis): `statusCode`,
  multi-valued `headers`, and `body` (string or `null`).

The module returns a **possibly-partial** response JSON; MockServer applies it with these semantics:

| Field | Present | Absent / `null` |
|-------|---------|-----------------|
| `statusCode` | replaces the status code | status unchanged |
| `headers` | each entry is **merged** — overwrites a same-named header, adds new ones; an entry with an empty or `null` values list **removes** that header | unmentioned headers left intact |
| `body` | replaces the body (sent as-is; set `Content-Type` via `headers` if needed) | body unchanged |

**Return ABI.** The module writes its response JSON into its own linear memory and returns a packed
`i64` = `(ptr << 32) | len`. A return of `0` means "no change" (opt out). MockServer reads `len` bytes
at `ptr`, capped at `SHAPE_MAX_RETURN_BYTES` (**1 MiB**).

**Fail-safe.** Shaping never fails a request. A missing export or a `0` return is a silent no-op; any
trap, out-of-bounds/oversized return, or invalid/non-object JSON leaves the response **unshaped** and
logs a single WARN **per module** (subsequent failures of the same module are silent). This mirrors the
fail-closed predicate design — a broken shaper degrades to the unshaped response, never a 500.

**Both exports.** A module can export **both** `match_request` and `shape_response`: MockServer matches
first, then shapes with the same module (the module referenced by the expectation's WASM body).

**Scope.** Shaping is wired into `HttpResponseActionHandler.handle(...)` — the point where a **static
`RESPONSE` action** is materialised — so it applies to matched expectations with a static response (after
any inline templated-file/JSON-schema/GraphQL synthesis has run, the module sees and can rewrite the
final response). Template/class-callback/forward action types materialise their response elsewhere and are
already dynamic, so they are intentionally out of scope for this hook.

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
- `examples/wasm/rust-shape-response/` exports **both** hooks (ABI v3): it matches `POST /shape` and
  shapes the response — setting `X-Shaped: true` and rewriting the JSON body's `name` field into a
  greeting — and ships a prebuilt `shape-response.wasm`.

For shaping the SDK adds `ShapeEnvelope`/`ShapeResponse` readers (with `body_unescaped` to decode a JSON
body for further parsing), a no-alloc `ResponseBuilder` (`status`/`header`/`body`/`finish`), the
`write_parts` string-splicing helper, and the `export_shape_response!` and
`export_match_and_shape_response!` macros (use exactly one `export_*` macro per crate — each defines the
`no_std` panic handler).

`mockserver-core` uses all three prebuilt binaries as ABI-guard test resources.

### Memory requirements

The module must declare at least one page of linear memory. The maximum memory is controlled by the `wasmMaxMemoryPages` configuration property (default: 256 pages = 16 MiB).

## Components

### WasmStore

`org.mockserver.wasm.WasmStore` -- thread-safe singleton backed by `ConcurrentHashMap<String, byte[]>`. Stores raw WASM module bytes keyed by user-chosen names. Reset on `/mockserver/reset`.

### WasmRuntime

`org.mockserver.wasm.WasmRuntime` -- parses the module with chicory's `Parser` and runs it via an `Instance`. Creates a fresh WASM instance per invocation for thread safety. Fails closed (returns `false`) on any error. The WASM instance is created with `MemoryLimits(min(declared.initialPages, effectiveMax), min(declared.maximumPages, wasmMaxMemoryPages))` — capping linear memory at `wasmMaxMemoryPages` while preserving the module's declared initial pages. `callMatch(WasmRequest)` builds the JSON envelope and invokes `match_request` when the module exports it; `callMatch(String)` is a body-only convenience that delegates to `callMatch(WasmRequest.ofBody(body))`. If `match_request` is absent it falls back to writing only the body and calling `match`. `callShape(WasmRequest, WasmResponse)` invokes the optional `shape_response` export (ABI v3), returning the parsed shaped `WasmResponse` — or `null` when the module does not export it or opts out (`0`) — and throwing `WasmShapeException` on a trap/oversized/garbage return (the size-cap/out-of-bounds/parse branches are unit-tested via the package-private `readShapedResult(Memory, long)`).

### WasmResponse

`org.mockserver.wasm.WasmResponse` -- immutable view of the response parts a `shape_response` module reads and rewrites (`statusCode`, `headers`, `body`). Used both as the shape-envelope input (the response the expectation would return) and as the parsed module output. Null fields on the parsed output mean "leave unchanged".

### WasmResponseShaper

`org.mockserver.wasm.WasmResponseShaper` -- applies a module's `shape_response` output to a materialised `HttpResponse`, in place. Snapshots the response into a `WasmResponse`, calls `WasmRuntime.callShape`, and merges the result (replace status/body, merge headers). Fail-safe: catches everything, leaves the response untouched on failure, and warns **once per module** (static dedup set). Wired in from `HttpResponseActionHandler.handle(...)` — the response-materialisation layer — only when `wasmEnabled`, the matched request carries a `WasmBody`, and its module is loaded.

### WasmShapeException

`org.mockserver.wasm.WasmShapeException` -- signals a genuine shaping failure (trap, out-of-bounds/oversized region, or non-object/invalid JSON), distinct from the "no export / opt out" no-ops (which `callShape` signals by returning `null`). Tells `WasmResponseShaper` to fall back and warn once.

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

To preview **response shaping** (ABI v3), also supply a candidate `response`
(`{ "statusCode", "headers", "body" }`). The result then additionally carries a `shaped` field —
the shaped response `{ "statusCode", "headers", "body" }` the module would produce, or `null` when the
module does not export `shape_response`, opts out, or fails (fail-safe):

```json
{
  "module": "<base64-encoded .wasm>",
  "request":  { "method": "POST", "path": "/shape" },
  "response": { "statusCode": 201, "headers": { "Content-Type": ["application/json"] }, "body": "{\"name\":\"acme\"}" }
}
```
→ `{ "matched": true, "shaped": { "statusCode": 200, "headers": { "X-Shaped": ["true"] }, "body": "{\"greeting\":\"Hello, acme!\",\"shaped\":true}" } }`

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
- Response shaping (`shape_response`) is equally fail-safe: a trap, out-of-bounds/oversized (> 1 MiB `SHAPE_MAX_RETURN_BYTES`) or invalid-JSON return leaves the response unshaped and logs once per module, so a malicious or broken shaper can neither 500 the request nor force an unbounded memory read
