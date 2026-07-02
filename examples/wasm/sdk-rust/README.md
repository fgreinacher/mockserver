# mockserver-wasm-sdk

A minimal, dependency-free Rust authoring SDK for **MockServer WASM matcher rules** that
use the richer ABI (method, path, headers — not just the body).

## What it gives you

MockServer's richer ABI calls an exported `match_request(ptr, len)` with a JSON request
envelope written into linear memory at offset 0:

```json
{ "method": "POST", "path": "/orders", "headers": { "X-Tenant": ["acme"] }, "body": "..." }
```

Rather than hand-parse that in every rule, this SDK gives you typed accessors and a macro
that wires up the ABI export:

```rust
#![no_std]
use mockserver_wasm_sdk::{export_match_request, Request};

fn rule(req: &Request) -> bool {
    req.method() == "POST"
        && req.path() == "/orders"
        && req.header("X-Tenant") == Some("acme")
}

export_match_request!(rule);
```

`Request` exposes `method()`, `path()`, `query_param(name)`, `header(name)` (case-insensitive),
`cookie(name)`, `body()`, and a generic `field(name)`. The crate is `no_std`, allocation-free, and
pulls in **no dependencies** (no `serde`), so a rule built on it stays tiny and freestanding on
`wasm32-unknown-unknown`.

## Shaping the response (ABI v3)

A module can also **compute the response**, not just match. Export `shape_response` (optionally alongside
`match_request`) and MockServer calls it after a match with a [`ShapeEnvelope`] — the matched request plus
the response the expectation would return — and applies whatever you build back:

```rust
#![no_std]
use mockserver_wasm_sdk::{export_match_and_shape_response, write_parts, Request, ResponseBuilder, ShapeEnvelope};

fn matches(req: &Request) -> bool {
    req.method() == "POST" && req.path() == "/shape"
}

fn shape(env: &ShapeEnvelope, out: ResponseBuilder) -> i64 {
    // read a field out of the original response body and rewrite it
    let mut buf = [0u8; 1024];
    let original = env.response().body_unescaped(&mut buf).unwrap_or("{}");
    let name = Request::new(original.as_bytes()).field("name").unwrap_or("world");
    let mut greeting = [0u8; 256];
    let body = write_parts(&mut greeting, &["{\"greeting\":\"Hello, ", name, "!\"}"]);

    out.status(200).header("X-Shaped", "true").body(body).finish()
}

export_match_and_shape_response!(matches, shape);
```

`ShapeEnvelope` gives you `request()` (a normal `Request`) and `response()` (a `ShapeResponse` with
`status_code()`, `header(name)`, `body()` and `body_unescaped(buf)`). `ResponseBuilder` builds the
returned response with `status`/`header`/`body`, and `finish()` returns the packed pointer the ABI
expects — return `0` (don't call `finish`) to leave the response unchanged. `write_parts` splices strings
into a buffer without allocating. Use exactly **one** `export_*` macro per crate (each defines the panic
handler): `export_match_request!`, `export_shape_response!`, or `export_match_and_shape_response!`. See
[`../rust-shape-response/`](../rust-shape-response/) for a complete sample.

## Build a rule that uses it

See [`../rust-request/`](../rust-request/) for a complete sample crate. To build:

```bash
rustup target add wasm32-unknown-unknown
cargo build --target wasm32-unknown-unknown --release
```

## Run the SDK's own tests

```bash
cargo test
```

Back-compat: if your rule only needs the body, the legacy body-only `match(ptr, len)`
ABI still works without this SDK — see [`../rust/`](../rust/).
