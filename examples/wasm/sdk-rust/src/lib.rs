//! # mockserver-wasm-sdk
//!
//! Minimal, dependency-free authoring SDK for **MockServer WASM matcher rules**.
//!
//! MockServer's richer WASM ABI calls an exported function
//! `match_request(ptr: i32, len: i32) -> i32` with a JSON envelope written into
//! linear memory at offset 0 (envelope **version 2**):
//!
//! ```json
//! {
//!   "version": 2,
//!   "method": "POST",
//!   "path": "/orders",
//!   "queryStringParameters": { "tenant": ["acme"] },
//!   "headers": { "X-Tenant": ["acme"] },
//!   "cookies": { "session": "abc123" },
//!   "body": "..."
//! }
//! ```
//!
//! Writing a parser by hand for every rule is tedious and error-prone, so this SDK
//! exposes typed accessors over that envelope: [`Request::method`], [`Request::path`],
//! [`Request::query_param`], [`Request::header`], [`Request::cookie`] and
//! [`Request::body`]. It is `no_std`, allocation-free, and pulls in **no dependencies**
//! (no `serde`), so a rule built against it stays tiny and freestanding on
//! `wasm32-unknown-unknown`. `query_param`/`cookie` require envelope version 2; against an
//! older envelope they return `None`, so a rule stays backward compatible.
//!
//! ## Usage
//!
//! ```ignore
//! #![no_std]
//! use mockserver_wasm_sdk::{export_match_request, Request};
//!
//! fn rule(req: &Request) -> bool {
//!     // match POST requests to /orders carrying the acme tenant header
//!     req.method() == "POST"
//!         && req.path() == "/orders"
//!         && req.header("X-Tenant") == Some("acme")
//! }
//!
//! export_match_request!(rule);
//! ```
//!
//! The [`export_match_request!`] macro wires up the ABI: it reads `len` bytes at
//! `ptr`, parses the envelope, calls your `fn(&Request) -> bool`, and returns
//! `1`/`0`. Back-compat: if you only care about the body, the legacy body-only
//! `match(ptr, len)` ABI still works without this SDK.

#![no_std]

/// A borrowed, parsed view over the MockServer request envelope.
///
/// All accessors borrow from the underlying JSON bytes — no allocation, no copying.
/// Header lookups are case-insensitive (matching HTTP semantics and MockServer's own
/// header handling).
pub struct Request<'a> {
    json: &'a str,
}

impl<'a> Request<'a> {
    /// Wrap the raw envelope bytes. `bytes` must be the UTF-8 JSON envelope MockServer
    /// wrote into linear memory. Invalid UTF-8 yields an empty request (all accessors
    /// return empty / `None`), keeping rules fail-safe.
    pub fn new(bytes: &'a [u8]) -> Self {
        let json = core::str::from_utf8(bytes).unwrap_or("");
        Request { json }
    }

    /// The HTTP method (e.g. `"POST"`), or `""` if absent.
    pub fn method(&self) -> &'a str {
        string_field(self.json, "method").unwrap_or("")
    }

    /// The request path (e.g. `"/orders"`), or `""` if absent.
    pub fn path(&self) -> &'a str {
        string_field(self.json, "path").unwrap_or("")
    }

    /// The request body, or `None` when the envelope carries a `null`/absent body.
    pub fn body(&self) -> Option<&'a str> {
        string_field(self.json, "body")
    }

    /// First value of the named header (case-insensitive), or `None` if absent.
    pub fn header(&self, name: &str) -> Option<&'a str> {
        first_array_value(self.json, "headers", name, true)
    }

    /// First value of the named query-string parameter (case-sensitive), or `None` if absent.
    ///
    /// Requires envelope [`version`](Request::version) 2 or newer; against a version-1 envelope
    /// (no `queryStringParameters` field) this always returns `None`.
    pub fn query_param(&self, name: &str) -> Option<&'a str> {
        first_array_value(self.json, "queryStringParameters", name, false)
    }

    /// Value of the named cookie (case-sensitive), or `None` if absent.
    ///
    /// Requires envelope [`version`](Request::version) 2 or newer; against a version-1 envelope
    /// (no `cookies` field) this always returns `None`.
    pub fn cookie(&self, name: &str) -> Option<&'a str> {
        nested_string_value(self.json, "cookies", name)
    }

    /// The envelope version MockServer declared (its top-level `version` field), or `1` when the
    /// field is absent (a version-1 envelope predates the `version` field). Modules can feature-detect
    /// newer fields with this, though reading a missing field simply returns `None` regardless.
    pub fn version(&self) -> u32 {
        match int_field(self.json, "version") {
            Some(v) => v,
            None => 1,
        }
    }
}

/// Wire up the `match_request` ABI export from a `fn(&Request) -> bool`.
///
/// Expands to an `extern "C"` function named `match_request` plus a `no_std`
/// panic handler. Use exactly once per rule crate.
#[macro_export]
macro_rules! export_match_request {
    ($rule:path) => {
        #[cfg(not(test))]
        #[panic_handler]
        fn __mockserver_panic(_info: &core::panic::PanicInfo) -> ! {
            core::arch::wasm32::unreachable()
        }

        /// MockServer richer ABI entry point: receives the JSON request envelope.
        #[export_name = "match_request"]
        pub extern "C" fn __mockserver_match_request(ptr: i32, len: i32) -> i32 {
            if ptr < 0 || len < 0 {
                return 0;
            }
            // SAFETY: MockServer guarantees `len` bytes live at `ptr` in our linear memory.
            let bytes = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };
            let req = $crate::Request::new(bytes);
            if $rule(&req) { 1 } else { 0 }
        }
    };
}

// ---------------------------------------------------------------------------
// Tiny purpose-built JSON reader for the fixed envelope shape. Not a general JSON
// parser — it understands exactly the envelope MockServer emits.
// ---------------------------------------------------------------------------

/// Find a top-level string field `"name": "value"`. Returns `None` if the field is
/// absent or its value is `null`. Handles `\"` and `\\` escapes inside the value by
/// returning the raw (still-escaped) slice — sufficient for equality checks on
/// typical method/path/header values; callers needing full unescaping can do so.
fn string_field<'a>(json: &'a str, name: &str) -> Option<&'a str> {
    let bytes = json.as_bytes();
    let key = find_key(json, name, 0)?;
    // key is the byte index just after the closing quote of the key name.
    let colon = skip_ws(bytes, key);
    if colon >= bytes.len() || bytes[colon] != b':' {
        return None;
    }
    let val_start = skip_ws(bytes, colon + 1);
    if val_start >= bytes.len() {
        return None;
    }
    if bytes[val_start] == b'n' {
        // null
        return None;
    }
    if bytes[val_start] != b'"' {
        return None;
    }
    read_string(json, val_start)
}

/// First value of a multi-valued object `"<object_key>": { "Name": ["v1", ...] }` for `name`.
/// Used for both `headers` (case-insensitive names) and `queryStringParameters`
/// (case-sensitive names); `case_insensitive` selects which.
fn first_array_value<'a>(json: &'a str, object_key: &str, name: &str, case_insensitive: bool) -> Option<&'a str> {
    let bytes = json.as_bytes();
    let headers_key = find_key(json, object_key, 0)?;
    let colon = skip_ws(bytes, headers_key);
    if colon >= bytes.len() || bytes[colon] != b':' {
        return None;
    }
    let obj_start = skip_ws(bytes, colon + 1);
    if obj_start >= bytes.len() || bytes[obj_start] != b'{' {
        return None;
    }
    // Scan keys within the headers object until the matching '}'.
    let mut i = obj_start + 1;
    let mut depth = 1usize;
    while i < bytes.len() && depth > 0 {
        match bytes[i] {
            b'{' => {
                depth += 1;
                i += 1;
            }
            b'}' => {
                depth -= 1;
                i += 1;
            }
            b'"' if depth == 1 => {
                // potential header-name key
                let (key, after) = match read_string_span(json, i) {
                    Some(v) => v,
                    None => return None,
                };
                let after_ws = skip_ws(bytes, after);
                if after_ws < bytes.len() && bytes[after_ws] == b':' {
                    let name_matches = if case_insensitive {
                        eq_ignore_ascii_case(key, name)
                    } else {
                        key == name
                    };
                    if name_matches {
                        // value is an array of strings: take the first
                        let arr = skip_ws(bytes, after_ws + 1);
                        if arr < bytes.len() && bytes[arr] == b'[' {
                            let first = skip_ws(bytes, arr + 1);
                            if first < bytes.len() && bytes[first] == b'"' {
                                return read_string(json, first);
                            }
                            return None;
                        } else if arr < bytes.len() && bytes[arr] == b'"' {
                            return read_string(json, arr);
                        }
                        return None;
                    }
                    i = after_ws + 1;
                } else {
                    i = after_ws;
                }
            }
            _ => i += 1,
        }
    }
    None
}

/// Value of `"<object_key>": { "Name": "value", ... }` for `name` (case-sensitive). Used for
/// the `cookies` object, whose values are plain strings rather than arrays. Returns `None` if the
/// object or the named entry is absent, or the entry's value is `null`.
fn nested_string_value<'a>(json: &'a str, object_key: &str, name: &str) -> Option<&'a str> {
    let bytes = json.as_bytes();
    let obj_key = find_key(json, object_key, 0)?;
    let colon = skip_ws(bytes, obj_key);
    if colon >= bytes.len() || bytes[colon] != b':' {
        return None;
    }
    let obj_start = skip_ws(bytes, colon + 1);
    if obj_start >= bytes.len() || bytes[obj_start] != b'{' {
        return None;
    }
    let mut i = obj_start + 1;
    let mut depth = 1usize;
    while i < bytes.len() && depth > 0 {
        match bytes[i] {
            b'{' => {
                depth += 1;
                i += 1;
            }
            b'}' => {
                depth -= 1;
                i += 1;
            }
            b'"' if depth == 1 => {
                let (key, after) = read_string_span(json, i)?;
                let after_ws = skip_ws(bytes, after);
                if after_ws < bytes.len() && bytes[after_ws] == b':' {
                    if key == name {
                        let val = skip_ws(bytes, after_ws + 1);
                        if val < bytes.len() && bytes[val] == b'"' {
                            return read_string(json, val);
                        }
                        return None;
                    }
                    // step over this entry's value
                    i = skip_value(bytes, after_ws + 1);
                } else {
                    i = after_ws;
                }
            }
            _ => i += 1,
        }
    }
    None
}

/// Read a top-level non-negative integer field `"name": <number>`, or `None` if absent/non-numeric.
fn int_field(json: &str, name: &str) -> Option<u32> {
    let bytes = json.as_bytes();
    let key = find_key(json, name, 0)?;
    let colon = skip_ws(bytes, key);
    if colon >= bytes.len() || bytes[colon] != b':' {
        return None;
    }
    let mut i = skip_ws(bytes, colon + 1);
    let start = i;
    let mut value: u32 = 0;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        value = value.wrapping_mul(10).wrapping_add((bytes[i] - b'0') as u32);
        i += 1;
    }
    if i == start {
        return None;
    }
    Some(value)
}

/// Locate the **top-level** object key `"name"` starting from `from`, returning the byte
/// index just after the key's closing quote.
///
/// Correctness matters here: a string token only counts as our key when it is (a) at
/// object depth 1 (top level of the envelope) and (b) immediately followed — modulo
/// whitespace — by `:`. Without those guards a VALUE string, a header NAME, or a header
/// VALUE that happens to equal `"method"`/`"path"`/`"headers"`/`"body"` would be matched
/// instead of the real key (these tokens are NOT unique within the envelope). Values that
/// are not our key (including whole nested objects/arrays such as the `headers` object)
/// are skipped, so we never descend into them.
fn find_key(json: &str, name: &str, from: usize) -> Option<usize> {
    let bytes = json.as_bytes();
    let mut i = from;
    let mut depth: i32 = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'{' | b'[' => {
                depth += 1;
                i += 1;
            }
            b'}' | b']' => {
                depth -= 1;
                i += 1;
            }
            b'"' => {
                let (key, after) = match read_string_span(json, i) {
                    Some(v) => v,
                    None => return None,
                };
                let after_ws = skip_ws(bytes, after);
                let is_key = after_ws < bytes.len() && bytes[after_ws] == b':';
                // We are inside the envelope object once depth == 1.
                if is_key && depth == 1 && key == name {
                    return Some(after);
                }
                if is_key {
                    // Skip the ':' and the value that follows so a non-matching key's
                    // value string can never be mistaken for a key on the next iteration.
                    i = skip_value(bytes, after_ws + 1);
                } else {
                    // A bare string that is not a key (e.g. an array element): step past it.
                    i = after;
                }
            }
            _ => i += 1,
        }
    }
    None
}

/// Skip one JSON value starting at `i` (after a key's `:`), returning the index just past
/// it. Strings, objects and arrays are skipped wholesale (respecting escapes and nesting);
/// scalars (numbers/true/false/null) are skipped up to the next structural delimiter.
fn skip_value(bytes: &[u8], i: usize) -> usize {
    let mut i = skip_ws(bytes, i);
    if i >= bytes.len() {
        return i;
    }
    match bytes[i] {
        b'"' => {
            // skip a string
            i += 1;
            while i < bytes.len() {
                match bytes[i] {
                    b'\\' => i += 2,
                    b'"' => return i + 1,
                    _ => i += 1,
                }
            }
            i
        }
        b'{' | b'[' => {
            let mut depth: i32 = 0;
            while i < bytes.len() {
                match bytes[i] {
                    b'{' | b'[' => {
                        depth += 1;
                        i += 1;
                    }
                    b'}' | b']' => {
                        depth -= 1;
                        i += 1;
                        if depth == 0 {
                            return i;
                        }
                    }
                    b'"' => {
                        // skip nested string so its braces/brackets are ignored
                        i += 1;
                        while i < bytes.len() {
                            match bytes[i] {
                                b'\\' => i += 2,
                                b'"' => {
                                    i += 1;
                                    break;
                                }
                                _ => i += 1,
                            }
                        }
                    }
                    _ => i += 1,
                }
            }
            i
        }
        _ => {
            // scalar: number / true / false / null
            while i < bytes.len() {
                match bytes[i] {
                    b',' | b'}' | b']' => break,
                    _ => i += 1,
                }
            }
            i
        }
    }
}

/// Read a JSON string whose opening quote is at `start`; return the unescaped-enough
/// inner slice (escapes left raw). Returns `None` if not a well-formed string.
fn read_string<'a>(json: &'a str, start: usize) -> Option<&'a str> {
    read_string_span(json, start).map(|(s, _)| s)
}

/// Like [`read_string`] but also returns the byte index just after the closing quote.
fn read_string_span<'a>(json: &'a str, start: usize) -> Option<(&'a str, usize)> {
    let bytes = json.as_bytes();
    if start >= bytes.len() || bytes[start] != b'"' {
        return None;
    }
    let mut i = start + 1;
    let inner_start = i;
    while i < bytes.len() {
        match bytes[i] {
            b'\\' => i += 2, // skip escaped char
            b'"' => return Some((&json[inner_start..i], i + 1)),
            _ => i += 1,
        }
    }
    None
}

fn skip_ws(bytes: &[u8], mut i: usize) -> usize {
    while i < bytes.len() {
        match bytes[i] {
            b' ' | b'\t' | b'\r' | b'\n' => i += 1,
            _ => break,
        }
    }
    i
}

fn eq_ignore_ascii_case(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut i = 0;
    while i < a.len() {
        if a[i].to_ascii_lowercase() != b[i].to_ascii_lowercase() {
            return false;
        }
        i += 1;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    const ENVELOPE: &str = r#"{"method":"POST","path":"/orders","headers":{"X-Tenant":["acme"],"Accept":["application/json"]},"body":"{\"amount\":5000}"}"#;

    const ENVELOPE_V2: &str = r#"{"version":2,"method":"POST","path":"/orders","queryStringParameters":{"tenant":["acme"],"id":["1","2"]},"headers":{"X-Tenant":["acme"]},"cookies":{"session":"abc123","empty":null},"body":"{}"}"#;

    #[test]
    fn reads_query_parameters_case_sensitively() {
        let req = Request::new(ENVELOPE_V2.as_bytes());
        assert_eq!(req.query_param("tenant"), Some("acme"));
        // first value of a multi-valued parameter
        assert_eq!(req.query_param("id"), Some("1"));
        // case-sensitive: wrong case does not match
        assert_eq!(req.query_param("Tenant"), None);
        assert_eq!(req.query_param("missing"), None);
    }

    #[test]
    fn reads_cookies() {
        let req = Request::new(ENVELOPE_V2.as_bytes());
        assert_eq!(req.cookie("session"), Some("abc123"));
        assert_eq!(req.cookie("empty"), None);
        assert_eq!(req.cookie("missing"), None);
    }

    #[test]
    fn reads_version() {
        assert_eq!(Request::new(ENVELOPE_V2.as_bytes()).version(), 2);
        // a version-1 envelope (no version field) reports 1
        assert_eq!(Request::new(ENVELOPE.as_bytes()).version(), 1);
    }

    #[test]
    fn v1_envelope_has_no_query_or_cookies() {
        // back-compat: reading v2-only fields from a v1 envelope yields None, never a wrong value
        let req = Request::new(ENVELOPE.as_bytes());
        assert_eq!(req.query_param("tenant"), None);
        assert_eq!(req.cookie("session"), None);
        // v1 fields still read correctly from a v2 envelope
        let v2 = Request::new(ENVELOPE_V2.as_bytes());
        assert_eq!(v2.method(), "POST");
        assert_eq!(v2.header("X-Tenant"), Some("acme"));
    }

    #[test]
    fn reads_method_and_path() {
        let req = Request::new(ENVELOPE.as_bytes());
        assert_eq!(req.method(), "POST");
        assert_eq!(req.path(), "/orders");
    }

    #[test]
    fn reads_headers_case_insensitively() {
        let req = Request::new(ENVELOPE.as_bytes());
        assert_eq!(req.header("X-Tenant"), Some("acme"));
        assert_eq!(req.header("x-tenant"), Some("acme"));
        assert_eq!(req.header("Accept"), Some("application/json"));
        assert_eq!(req.header("Missing"), None);
    }

    #[test]
    fn reads_body() {
        let req = Request::new(ENVELOPE.as_bytes());
        assert_eq!(req.body(), Some(r#"{\"amount\":5000}"#));
    }

    #[test]
    fn null_body_is_none() {
        let env = r#"{"method":"GET","path":"/","headers":{},"body":null}"#;
        let req = Request::new(env.as_bytes());
        assert_eq!(req.body(), None);
        assert_eq!(req.method(), "GET");
    }

    #[test]
    fn missing_fields_are_empty() {
        let req = Request::new(b"{}");
        assert_eq!(req.method(), "");
        assert_eq!(req.path(), "");
        assert_eq!(req.header("X"), None);
        assert_eq!(req.body(), None);
    }

    // --- adversarial: field-name tokens that also appear as VALUES or header names/values
    //     must NOT shadow the real top-level keys (they are not unique tokens). ---

    #[test]
    fn header_value_equal_to_field_name_does_not_shadow_body() {
        let env = r#"{"method":"GET","path":"/","headers":{"X":["body"]},"body":"real"}"#;
        let req = Request::new(env.as_bytes());
        assert_eq!(req.body(), Some("real"));
        assert_eq!(req.header("X"), Some("body"));
    }

    #[test]
    fn header_name_equal_to_field_name_does_not_shadow_body() {
        let env = r#"{"method":"GET","path":"/","headers":{"body":["h"]},"body":"real"}"#;
        let req = Request::new(env.as_bytes());
        assert_eq!(req.body(), Some("real"));
        assert_eq!(req.header("body"), Some("h"));
    }

    #[test]
    fn method_value_equal_to_path_does_not_shadow_path() {
        let env = r#"{"method":"path","path":"/real","headers":{},"body":"b"}"#;
        let req = Request::new(env.as_bytes());
        assert_eq!(req.method(), "path");
        assert_eq!(req.path(), "/real");
    }

    #[test]
    fn method_value_equal_to_headers_does_not_break_header_lookup() {
        let env = r#"{"method":"headers","path":"/","headers":{"X-Tenant":["acme"]},"body":null}"#;
        let req = Request::new(env.as_bytes());
        assert_eq!(req.method(), "headers");
        assert_eq!(req.header("X-Tenant"), Some("acme"));
        assert_eq!(req.body(), None);
    }

    #[test]
    fn path_value_containing_braces_does_not_confuse_later_fields() {
        let env = r#"{"method":"POST","path":"/a{b}c","headers":{"X":["v"]},"body":"end"}"#;
        let req = Request::new(env.as_bytes());
        assert_eq!(req.path(), "/a{b}c");
        assert_eq!(req.header("X"), Some("v"));
        assert_eq!(req.body(), Some("end"));
    }
}
