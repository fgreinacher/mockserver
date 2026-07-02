//! Example MockServer WASM module demonstrating **response shaping** (ABI v3) via
//! [`mockserver_wasm_sdk`]. It exports **both** hooks, so the same module matches the request and then
//! rewrites the response the expectation would return — a WASM-computed dynamic response.
//!
//! * `match_request`: matches `POST /shape`.
//! * `shape_response`: sets the `X-Shaped: true` header and rewrites the JSON body — it reads the
//!   `name` field from the response the expectation would return and replaces the body with
//!   `{"greeting":"Hello, <name>!","shaped":true}`.
//!
//! So an expectation whose static response body is `{"name":"acme"}` ends up returning
//! `{"greeting":"Hello, acme!","shaped":true}` with an added `X-Shaped` header.

#![no_std]

use mockserver_wasm_sdk::{export_match_and_shape_response, write_parts, Request, ResponseBuilder, ShapeEnvelope};

/// Match `POST /shape` so the expectation carrying this module fires.
fn matches(req: &Request) -> bool {
    req.method() == "POST" && req.path() == "/shape"
}

/// Shape the response: add a header and rewrite the JSON body's greeting from the original `name` field.
fn shape(env: &ShapeEnvelope, out: ResponseBuilder) -> i64 {
    // decode the original response body so we can read a field out of it
    let mut body_buf = [0u8; 1024];
    let original = env.response().body_unescaped(&mut body_buf).unwrap_or("{}");
    let name = Request::new(original.as_bytes()).field("name").unwrap_or("world");

    // build the rewritten body by splicing the name into a greeting template
    let mut greeting_buf = [0u8; 512];
    let greeting = write_parts(
        &mut greeting_buf,
        &["{\"greeting\":\"Hello, ", name, "!\",\"shaped\":true}"],
    );

    out.status(200)
        .header("X-Shaped", "true")
        .body(greeting)
        .finish()
}

export_match_and_shape_response!(matches, shape);
