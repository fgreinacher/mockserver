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

    /// Read an arbitrary top-level string field by name, or `None` if absent/`null`/non-string.
    ///
    /// A generic escape hatch over the same flat-JSON reader the typed accessors use — handy when a
    /// `shape_response` module wraps an unescaped body string (see
    /// [`ShapeResponse::body_unescaped`](ShapeResponse::body_unescaped)) in a `Request` to pull a field
    /// out of it, e.g. `Request::new(body.as_bytes()).field("name")`.
    pub fn field(&self, name: &str) -> Option<&'a str> {
        string_field(self.json, name)
    }
}

/// Number of bytes reserved for a `shape_response` module's serialised output buffer (see
/// [`export_shape_response!`]). 16 KiB comfortably covers realistic shaped mock responses; the runtime
/// additionally caps any returned region (see MockServer's `SHAPE_MAX_RETURN_BYTES`).
pub const SHAPE_OUT_BUFFER_LEN: usize = 16 * 1024;

/// The `no_std` panic handler MockServer WASM rules need. Emitted by the `export_*` macros; not for
/// direct use.
#[macro_export]
macro_rules! __mockserver_panic_handler {
    () => {
        #[cfg(not(test))]
        #[panic_handler]
        fn __mockserver_panic(_info: &core::panic::PanicInfo) -> ! {
            core::arch::wasm32::unreachable()
        }
    };
}

/// The `match_request` export body (no panic handler). Not for direct use — see [`export_match_request!`].
#[macro_export]
macro_rules! __mockserver_match_request_export {
    ($rule:path) => {
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

/// The `shape_response` export body (no panic handler). Not for direct use — see [`export_shape_response!`].
#[macro_export]
macro_rules! __mockserver_shape_response_export {
    ($shape:path) => {
        /// MockServer ABI v3 entry point: receives the JSON shape envelope, returns a packed
        /// `(ptr << 32) | len` pointing at the response JSON in linear memory (`0` = leave unchanged).
        #[export_name = "shape_response"]
        pub extern "C" fn __mockserver_shape_response(ptr: i32, len: i32) -> i64 {
            if ptr < 0 || len < 0 {
                return 0;
            }
            // SAFETY: MockServer guarantees `len` bytes live at `ptr` in our linear memory.
            let bytes = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };
            let env = $crate::ShapeEnvelope::new(bytes);
            // The output must outlive this call (MockServer reads it after we return), so it lives in a
            // module-static buffer. WASM here is single-threaded and non-reentrant, so a single shared
            // buffer is safe; the raw-pointer reborrow sidesteps the `static_mut_refs` lint.
            static mut __MOCKSERVER_SHAPE_OUT: [u8; $crate::SHAPE_OUT_BUFFER_LEN] =
                [0u8; $crate::SHAPE_OUT_BUFFER_LEN];
            let buf: &mut [u8] = unsafe { &mut *core::ptr::addr_of_mut!(__MOCKSERVER_SHAPE_OUT) };
            $shape(&env, $crate::ResponseBuilder::new(buf))
        }
    };
}

/// Wire up the `match_request` ABI export from a `fn(&Request) -> bool`.
///
/// Expands to an `extern "C"` function named `match_request` plus a `no_std` panic handler. Use
/// **exactly one** `export_*` macro per rule crate (each defines the panic handler).
#[macro_export]
macro_rules! export_match_request {
    ($rule:path) => {
        $crate::__mockserver_panic_handler!();
        $crate::__mockserver_match_request_export!($rule);
    };
}

/// Wire up the `shape_response` ABI export from a `fn(&ShapeEnvelope, ResponseBuilder) -> i64`.
///
/// The rule reads the [`ShapeEnvelope`] (matched request + the response the expectation would return),
/// builds a response with [`ResponseBuilder`], and returns `builder.…finish()`. Use for a shape-only
/// module; for a module that both matches and shapes use [`export_match_and_shape_response!`].
///
/// ```ignore
/// fn shape(env: &ShapeEnvelope, out: ResponseBuilder) -> i64 {
///     out.status(200).header("X-Shaped", "true").body("{\"ok\":true}").finish()
/// }
/// export_shape_response!(shape);
/// ```
#[macro_export]
macro_rules! export_shape_response {
    ($shape:path) => {
        $crate::__mockserver_panic_handler!();
        $crate::__mockserver_shape_response_export!($shape);
    };
}

/// Wire up **both** the `match_request` predicate and the `shape_response` hook from one crate, so a
/// module matches a request and then rewrites the response the expectation would return. Defines the
/// panic handler once. Use exactly once per rule crate (instead of the single-export macros).
#[macro_export]
macro_rules! export_match_and_shape_response {
    ($rule:path, $shape:path) => {
        $crate::__mockserver_panic_handler!();
        $crate::__mockserver_match_request_export!($rule);
        $crate::__mockserver_shape_response_export!($shape);
    };
}

// ---------------------------------------------------------------------------
// Response shaping (ABI v3): read the shape envelope and build the response JSON.
// ---------------------------------------------------------------------------

/// A borrowed view over the MockServer **shape envelope** (ABI v3):
///
/// ```json
/// {
///   "version": 3,
///   "request":  { /* the same envelope match_request modules receive */ },
///   "response": { "statusCode": 200, "headers": { "Content-Type": ["application/json"] }, "body": "..." }
/// }
/// ```
///
/// [`request`](ShapeEnvelope::request) returns a [`Request`] over the nested request object (so all the
/// familiar accessors work), and [`response`](ShapeEnvelope::response) returns a [`ShapeResponse`] over
/// the response the expectation would return.
pub struct ShapeEnvelope<'a> {
    json: &'a str,
}

impl<'a> ShapeEnvelope<'a> {
    /// Wrap the raw shape-envelope bytes MockServer wrote into linear memory. Invalid UTF-8 yields an
    /// empty envelope (all accessors return empty), keeping modules fail-safe.
    pub fn new(bytes: &'a [u8]) -> Self {
        let json = core::str::from_utf8(bytes).unwrap_or("");
        ShapeEnvelope { json }
    }

    /// The shape-envelope version MockServer declared, or `0` when absent.
    pub fn version(&self) -> u32 {
        int_field(self.json, "version").unwrap_or(0)
    }

    /// The matched request, as the same [`Request`] view a `match_request` module receives.
    pub fn request(&self) -> Request<'a> {
        let span = object_span(self.json, "request").unwrap_or("{}");
        Request::new(span.as_bytes())
    }

    /// The response the matched expectation would return.
    pub fn response(&self) -> ShapeResponse<'a> {
        let span = object_span(self.json, "response").unwrap_or("{}");
        ShapeResponse { json: span }
    }
}

/// A borrowed view over the `response` object of the [`ShapeEnvelope`] — the response the matched
/// expectation would return, before shaping.
pub struct ShapeResponse<'a> {
    json: &'a str,
}

impl<'a> ShapeResponse<'a> {
    /// The status code, or `None` if absent/`null`.
    pub fn status_code(&self) -> Option<u32> {
        int_field(self.json, "statusCode")
    }

    /// First value of the named response header (case-insensitive), or `None` if absent.
    pub fn header(&self, name: &str) -> Option<&'a str> {
        first_array_value(self.json, "headers", name, true)
    }

    /// The raw (still JSON-escaped) response body slice, or `None` when the body is `null`/absent. Use
    /// [`body_unescaped`](ShapeResponse::body_unescaped) to decode escapes for further JSON parsing.
    pub fn body(&self) -> Option<&'a str> {
        string_field(self.json, "body")
    }

    /// Decode the response body's JSON string escapes into `buf`, returning the decoded slice. `None`
    /// when the body is absent/`null`, `buf` is too small, or the decoded bytes are not valid UTF-8.
    /// Handy for pulling a field out of a JSON body: `Request::new(decoded.as_bytes()).field("name")`.
    pub fn body_unescaped<'b>(&self, buf: &'b mut [u8]) -> Option<&'b str> {
        let raw = self.body()?;
        json_unescape(raw, buf)
    }
}

/// Builds the JSON response a `shape_response` module returns, writing directly into the module-static
/// output buffer supplied by [`export_shape_response!`]. Every field is optional — omit one to leave that
/// part of the response unchanged. Consume the builder with [`finish`](ResponseBuilder::finish), which
/// serialises the response and returns the packed `(ptr << 32) | len` the ABI expects.
pub struct ResponseBuilder<'b, 'd> {
    buf: &'b mut [u8],
    status: Option<u32>,
    header_names: [&'d str; RESPONSE_BUILDER_MAX_HEADERS],
    header_values: [&'d str; RESPONSE_BUILDER_MAX_HEADERS],
    header_count: usize,
    body: Option<&'d str>,
}

/// Maximum number of headers a single [`ResponseBuilder`] can set (extra `header` calls are ignored).
pub const RESPONSE_BUILDER_MAX_HEADERS: usize = 16;

impl<'b, 'd> ResponseBuilder<'b, 'd> {
    /// Create a builder over the output buffer. Normally called for you by [`export_shape_response!`].
    pub fn new(buf: &'b mut [u8]) -> Self {
        ResponseBuilder {
            buf,
            status: None,
            header_names: [""; RESPONSE_BUILDER_MAX_HEADERS],
            header_values: [""; RESPONSE_BUILDER_MAX_HEADERS],
            header_count: 0,
            body: None,
        }
    }

    /// Set the response status code.
    pub fn status(mut self, code: u32) -> Self {
        self.status = Some(code);
        self
    }

    /// Set (or overwrite) a response header. MockServer merges each returned header into the response by
    /// name, leaving unmentioned headers intact. Beyond [`RESPONSE_BUILDER_MAX_HEADERS`] extra calls are
    /// silently ignored.
    pub fn header(mut self, name: &'d str, value: &'d str) -> Self {
        if self.header_count < RESPONSE_BUILDER_MAX_HEADERS {
            self.header_names[self.header_count] = name;
            self.header_values[self.header_count] = value;
            self.header_count += 1;
        }
        self
    }

    /// Set the response body. `content` is the literal body text (not JSON-escaped); the builder escapes
    /// it when embedding it in the envelope.
    pub fn body(mut self, content: &'d str) -> Self {
        self.body = Some(content);
        self
    }

    /// Serialise the response into the output buffer and return the packed `(ptr << 32) | len` result.
    /// Output larger than the buffer is truncated (MockServer then rejects the malformed JSON and falls
    /// back to the unshaped response — fail-safe).
    pub fn finish(self) -> i64 {
        let ptr = self.buf.as_ptr() as usize;
        let mut w = JsonWriter { buf: self.buf, len: 0 };
        w.byte(b'{');
        let mut first = true;
        if let Some(code) = self.status {
            w.raw("\"statusCode\":");
            w.number(code);
            first = false;
        }
        if self.header_count > 0 {
            if !first {
                w.byte(b',');
            }
            first = false;
            w.raw("\"headers\":{");
            let mut i = 0;
            while i < self.header_count {
                if i > 0 {
                    w.byte(b',');
                }
                w.byte(b'"');
                w.escaped(self.header_names[i]);
                w.raw("\":[\"");
                w.escaped(self.header_values[i]);
                w.raw("\"]");
                i += 1;
            }
            w.byte(b'}');
        }
        if let Some(content) = self.body {
            if !first {
                w.byte(b',');
            }
            w.raw("\"body\":\"");
            w.escaped(content);
            w.byte(b'"');
        }
        w.byte(b'}');
        let len = w.len;
        (((ptr as u64) << 32) | (len as u64)) as i64
    }
}

/// Copy each part into `buf` in order and return the concatenation as a `&str`, truncating if `buf` is
/// too small. Allocation-free string building for `no_std` shape rules (e.g. splicing a request value
/// into a JSON body template). Non-UTF-8 truncation at a multibyte boundary yields an empty string.
pub fn write_parts<'a>(buf: &'a mut [u8], parts: &[&str]) -> &'a str {
    let mut len = 0usize;
    for part in parts {
        for &b in part.as_bytes() {
            if len < buf.len() {
                buf[len] = b;
                len += 1;
            }
        }
    }
    core::str::from_utf8(&buf[..len]).unwrap_or("")
}

/// A minimal forward-only writer over a fixed byte buffer. Writes past the end are dropped (bounded,
/// fail-safe truncation).
struct JsonWriter<'b> {
    buf: &'b mut [u8],
    len: usize,
}

impl<'b> JsonWriter<'b> {
    fn byte(&mut self, b: u8) {
        if self.len < self.buf.len() {
            self.buf[self.len] = b;
            self.len += 1;
        }
    }

    fn raw(&mut self, s: &str) {
        for &b in s.as_bytes() {
            self.byte(b);
        }
    }

    fn number(&mut self, mut n: u32) {
        if n == 0 {
            self.byte(b'0');
            return;
        }
        let mut tmp = [0u8; 10];
        let mut i = 0;
        while n > 0 {
            tmp[i] = b'0' + (n % 10) as u8;
            n /= 10;
            i += 1;
        }
        while i > 0 {
            i -= 1;
            self.byte(tmp[i]);
        }
    }

    /// Write `s` as the contents of a JSON string (without the surrounding quotes), escaping `"`, `\`
    /// and control characters so the result stays valid JSON.
    fn escaped(&mut self, s: &str) {
        for &b in s.as_bytes() {
            match b {
                b'"' => self.raw("\\\""),
                b'\\' => self.raw("\\\\"),
                b'\n' => self.raw("\\n"),
                b'\r' => self.raw("\\r"),
                b'\t' => self.raw("\\t"),
                0x08 => self.raw("\\b"),
                0x0C => self.raw("\\f"),
                c if c < 0x20 => {
                    self.raw("\\u00");
                    self.byte(hex_digit(c >> 4));
                    self.byte(hex_digit(c & 0x0F));
                }
                c => self.byte(c),
            }
        }
    }
}

fn hex_digit(nibble: u8) -> u8 {
    match nibble {
        0..=9 => b'0' + nibble,
        _ => b'a' + (nibble - 10),
    }
}

/// Return the substring covering a top-level object value `"name": { ... }`, including the braces, or
/// `None` if the key is absent or its value is not an object.
fn object_span<'a>(json: &'a str, name: &str) -> Option<&'a str> {
    let bytes = json.as_bytes();
    let key = find_key(json, name, 0)?;
    let colon = skip_ws(bytes, key);
    if colon >= bytes.len() || bytes[colon] != b':' {
        return None;
    }
    let start = skip_ws(bytes, colon + 1);
    if start >= bytes.len() || bytes[start] != b'{' {
        return None;
    }
    let end = skip_value(bytes, start);
    Some(&json[start..end])
}

/// Decode the JSON string escapes in `raw` into `buf`, returning the decoded UTF-8 slice. Handles the
/// standard two-character escapes and `\uXXXX` (basic multilingual plane; surrogate pairs are decoded
/// independently). `None` if `buf` overflows or the result is not valid UTF-8.
fn json_unescape<'b>(raw: &str, buf: &'b mut [u8]) -> Option<&'b str> {
    let src = raw.as_bytes();
    let mut i = 0usize;
    let mut o = 0usize;
    while i < src.len() {
        let c = src[i];
        if c == b'\\' && i + 1 < src.len() {
            i += 1;
            match src[i] {
                b'"' => push_byte(buf, &mut o, b'"')?,
                b'\\' => push_byte(buf, &mut o, b'\\')?,
                b'/' => push_byte(buf, &mut o, b'/')?,
                b'n' => push_byte(buf, &mut o, b'\n')?,
                b't' => push_byte(buf, &mut o, b'\t')?,
                b'r' => push_byte(buf, &mut o, b'\r')?,
                b'b' => push_byte(buf, &mut o, 0x08)?,
                b'f' => push_byte(buf, &mut o, 0x0C)?,
                b'u' => {
                    if i + 4 >= src.len() {
                        return None;
                    }
                    let cp = hex4(&src[i + 1..i + 5])?;
                    i += 4;
                    push_code_point(buf, &mut o, cp)?;
                }
                other => push_byte(buf, &mut o, other)?,
            }
            i += 1;
        } else {
            push_byte(buf, &mut o, c)?;
            i += 1;
        }
    }
    core::str::from_utf8(&buf[..o]).ok()
}

fn push_byte(buf: &mut [u8], o: &mut usize, b: u8) -> Option<()> {
    if *o >= buf.len() {
        return None;
    }
    buf[*o] = b;
    *o += 1;
    Some(())
}

/// UTF-8 encode a basic-multilingual-plane code point (≤ 0xFFFF) into `buf`.
fn push_code_point(buf: &mut [u8], o: &mut usize, cp: u32) -> Option<()> {
    if cp < 0x80 {
        push_byte(buf, o, cp as u8)
    } else if cp < 0x800 {
        push_byte(buf, o, 0xC0 | (cp >> 6) as u8)?;
        push_byte(buf, o, 0x80 | (cp & 0x3F) as u8)
    } else {
        push_byte(buf, o, 0xE0 | (cp >> 12) as u8)?;
        push_byte(buf, o, 0x80 | ((cp >> 6) & 0x3F) as u8)?;
        push_byte(buf, o, 0x80 | (cp & 0x3F) as u8)
    }
}

fn hex4(digits: &[u8]) -> Option<u32> {
    let mut value = 0u32;
    for &d in digits {
        let nibble = match d {
            b'0'..=b'9' => d - b'0',
            b'a'..=b'f' => d - b'a' + 10,
            b'A'..=b'F' => d - b'A' + 10,
            _ => return None,
        };
        value = (value << 4) | nibble as u32;
    }
    Some(value)
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

    // --- ABI v3: shape envelope + response builder ---

    const SHAPE_ENVELOPE: &str = r#"{"version":3,"request":{"version":2,"method":"POST","path":"/shape","queryStringParameters":{"tenant":["acme"]},"headers":{"X-Tenant":["acme"]},"cookies":{"session":"abc123"},"body":"{}"},"response":{"statusCode":201,"headers":{"Content-Type":["application/json"]},"body":"{\"name\":\"acme\"}"}}"#;

    #[test]
    fn shape_envelope_exposes_request_and_response() {
        let env = ShapeEnvelope::new(SHAPE_ENVELOPE.as_bytes());
        assert_eq!(env.version(), 3);
        // nested request is readable via the normal Request accessors
        let req = env.request();
        assert_eq!(req.method(), "POST");
        assert_eq!(req.path(), "/shape");
        assert_eq!(req.query_param("tenant"), Some("acme"));
        assert_eq!(req.cookie("session"), Some("abc123"));
        // response fields
        let resp = env.response();
        assert_eq!(resp.status_code(), Some(201));
        assert_eq!(resp.header("content-type"), Some("application/json"));
    }

    #[test]
    fn shape_response_body_unescaped_and_field_read() {
        let env = ShapeEnvelope::new(SHAPE_ENVELOPE.as_bytes());
        let mut buf = [0u8; 128];
        let body = env.response().body_unescaped(&mut buf).unwrap();
        assert_eq!(body, r#"{"name":"acme"}"#);
        // and a field can be pulled out of the decoded body
        let name = Request::new(body.as_bytes()).field("name");
        assert_eq!(name, Some("acme"));
    }

    #[test]
    fn response_builder_serialises_status_headers_and_body() {
        let mut buf = [0u8; 256];
        let packed = ResponseBuilder::new(&mut buf)
            .status(200)
            .header("X-Shaped", "true")
            .body(r#"{"greeting":"Hello, acme!"}"#)
            .finish();
        let len = (packed & 0xFFFF_FFFF) as usize;
        let json = core::str::from_utf8(&buf[..len]).unwrap();
        assert_eq!(
            json,
            r#"{"statusCode":200,"headers":{"X-Shaped":["true"]},"body":"{\"greeting\":\"Hello, acme!\"}"}"#
        );
    }

    #[test]
    fn response_builder_escapes_control_characters() {
        let mut buf = [0u8; 128];
        let packed = ResponseBuilder::new(&mut buf).body("a\"b\\c\nd").finish();
        let len = (packed & 0xFFFF_FFFF) as usize;
        let json = core::str::from_utf8(&buf[..len]).unwrap();
        assert_eq!(json, r#"{"body":"a\"b\\c\nd"}"#);
    }

    #[test]
    fn response_builder_body_only_leaves_other_fields_out() {
        let mut buf = [0u8; 64];
        let packed = ResponseBuilder::new(&mut buf).body("hi").finish();
        let len = (packed & 0xFFFF_FFFF) as usize;
        assert_eq!(core::str::from_utf8(&buf[..len]).unwrap(), r#"{"body":"hi"}"#);
    }

    #[test]
    fn write_parts_concatenates() {
        let mut buf = [0u8; 64];
        let s = write_parts(&mut buf, &["{\"greeting\":\"Hello, ", "acme", "!\"}"]);
        assert_eq!(s, r#"{"greeting":"Hello, acme!"}"#);
    }

    #[test]
    fn json_unescape_handles_unicode_escape() {
        let mut buf = [0u8; 32];
        // é is 'é'
        assert_eq!(json_unescape(r#"café"#, &mut buf), Some("café"));
    }
}
