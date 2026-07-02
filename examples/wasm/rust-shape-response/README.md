# WASM Rust Example — response shaping (ABI v3)

A MockServer WASM module that both **matches a request** and **shapes the response** — a WASM-computed
dynamic response — built on [`mockserver-wasm-sdk`](../sdk-rust/). It exports both `match_request` and
`shape_response`.

## What it demonstrates

* `match_request`: matches `POST /shape`.
* `shape_response`: sets the `X-Shaped: true` header and rewrites the JSON body — it reads the `name`
  field from the response the expectation would return and replaces the body with
  `{"greeting":"Hello, <name>!","shaped":true}`.

So an expectation on `POST /shape` whose static response body is `{"name":"acme"}` ends up returning
`{"greeting":"Hello, acme!","shaped":true}` with an added `X-Shaped` header.

A module without a `shape_response` export stays a pure predicate (matching only); modules with both
exports match first, then shape.

## Prerequisites

- Rust toolchain (`rustup`) with the WASM target:
  ```bash
  rustup target add wasm32-unknown-unknown
  ```

## Build

```bash
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/mockserver_wasm_shape_response_example.wasm shape-response.wasm
```

A prebuilt **`shape-response.wasm`** is already committed here so you can use it without a Rust toolchain.
(MockServer's `mockserver-core` test suite also uses it as an ABI-v3 guard fixture.)

## Try it

Upload it (WASM must be enabled: `-Dmockserver.wasmEnabled=true`), then preview both the match **and** the
shaped response against a sample request and candidate response — without creating an expectation:

```bash
curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/test" \
  -H "Content-Type: application/json" \
  -d "{\"module\":\"$(base64 < shape-response.wasm | tr -d '\n')\",
       \"request\":{\"method\":\"POST\",\"path\":\"/shape\",\"body\":\"{}\"},
       \"response\":{\"statusCode\":201,\"headers\":{\"Content-Type\":[\"application/json\"]},\"body\":\"{\\\"name\\\":\\\"acme\\\"}\"}}"
# Returns:
# {"matched":true,"shaped":{"statusCode":200,"headers":{"X-Shaped":["true"]},"body":"{\"greeting\":\"Hello, acme!\",\"shaped\":true}"}}
```

To use it in a live expectation, reference the module from a WASM body matcher; when the request matches,
MockServer shapes the expectation's response through the module. See
[`docs/code/wasm-rules.md`](../../../docs/code/wasm-rules.md) for the full runtime design.
