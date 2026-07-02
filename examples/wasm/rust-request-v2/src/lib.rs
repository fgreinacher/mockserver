//! Example MockServer WASM matcher using the **envelope v2** fields — query string
//! parameters and cookies — via [`mockserver_wasm_sdk`].
//!
//! It matches when **all** of the following hold, demonstrating routing on parts of the
//! request that envelope v1 did not expose:
//!
//! * the method is `POST`,
//! * the path is exactly `/orders`,
//! * the query-string parameter `tenant` equals `acme` (case-sensitive),
//! * the cookie `session` equals `abc123`.
//!
//! Because it exports `match_request`, MockServer passes the full JSON request envelope.
//! `query_param`/`cookie` require envelope v2; against an older server they return `None`,
//! so the rule simply fails to match rather than misbehaving.

#![no_std]

use mockserver_wasm_sdk::{export_match_request, Request};

fn rule(req: &Request) -> bool {
    req.method() == "POST"
        && req.path() == "/orders"
        && req.query_param("tenant") == Some("acme")
        && req.cookie("session") == Some("abc123")
}

export_match_request!(rule);
