# WASM Rust Example — envelope v2 (query parameters + cookies)

A MockServer WASM matcher rule that uses the **envelope v2** fields exposed through the
richer `match_request` ABI via [`mockserver-wasm-sdk`](../sdk-rust/), so it can route on
query-string parameters and cookies — not just method, path, headers and body.

## What it demonstrates

It matches when **all** of:

* the method is `POST`,
* the path is exactly `/orders`,
* the query-string parameter `tenant` equals `acme` (case-sensitive),
* the cookie `session` equals `abc123`.

`query_param`/`cookie` require envelope v2. Against an older MockServer that sends a v1
envelope (no `queryStringParameters`/`cookies`) they return `None`, so the rule simply
does not match rather than misbehaving.

## Prerequisites

- Rust toolchain (`rustup`) with the WASM target:
  ```bash
  rustup target add wasm32-unknown-unknown
  ```

## Build

```bash
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/mockserver_wasm_request_v2_example.wasm match-request-v2.wasm
```

A prebuilt **`match-request-v2.wasm`** is already committed here so you can use it without a
Rust toolchain. (MockServer's `mockserver-core` test suite also uses it as an envelope-v2
ABI-guard fixture.)

## Try it

Upload it (WASM must be enabled: `-Dmockserver.wasmEnabled=true`), then test it against a
sample request without creating an expectation:

```bash
curl -X POST "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/wasm/test" \
  -H "Content-Type: application/json" \
  -d "{\"module\":\"$(base64 < match-request-v2.wasm | tr -d '\n')\",
       \"request\":{\"method\":\"POST\",\"path\":\"/orders\",
                    \"queryStringParameters\":{\"tenant\":[\"acme\"]},
                    \"cookies\":{\"session\":\"abc123\"},\"body\":\"{}\"}}"
# Returns: {"matched":true}
```

See [`docs/code/wasm-rules.md`](../../../docs/code/wasm-rules.md) for the full runtime design.
