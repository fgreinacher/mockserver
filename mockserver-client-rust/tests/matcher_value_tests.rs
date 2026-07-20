//! Pure (de)serialization tests for the negation/optional escape hatch.
//!
//! A matcher value that legitimately starts with `!` or `?` collides with
//! MockServer's plain-string marker syntax: the server strips a leading `!`
//! (negation) or `?` (optional) when reading, so a bare `"!foo"` is read as
//! "anything but foo". [`MatcherValue`] escapes such a value to the object form
//! (`{"not":false,"value":"!foo"}`) so the server reads it verbatim, while every
//! value that already round-trips through the plain form stays byte-identical on
//! the wire.

use mockserver_client::{HttpRequest, MatcherValue};
use serde_json::{json, Value};

// A value that round-trips through the plain form must STAY a plain string, so
// existing expectations are byte-identical on the wire.
#[test]
fn unambiguous_stays_plain_string() {
    let cases: &[(MatcherValue, &str)] = &[
        (MatcherValue::literal("foo"), r#""foo""#),
        (MatcherValue::not_literal("foo"), r#""!foo""#),
        (MatcherValue::optional_literal("foo"), r#""?foo""#),
        (MatcherValue::literal(""), r#""""#),
        // marker not at position 0
        (MatcherValue::literal("foo!bar"), r#""foo!bar""#),
        // re-parses to itself, so no escape is needed
        (MatcherValue::not_literal("!foo"), r#""!!foo""#),
    ];
    for (value, want) in cases {
        let got = serde_json::to_string(value).unwrap();
        assert_eq!(&got, want, "serialize {value:?}");
    }
}

// A value the plain form would misread must use the object form, and must carry
// "not" explicitly even when false so the intent is unmistakable.
#[test]
fn ambiguous_uses_object_form_with_explicit_not() {
    let cases: &[(MatcherValue, Value)] = &[
        (
            MatcherValue::literal("!foo"),
            json!({"not": false, "value": "!foo"}),
        ),
        (
            MatcherValue::literal("?foo"),
            json!({"not": false, "value": "?foo"}),
        ),
        (
            MatcherValue::literal("?!foo"),
            json!({"not": false, "value": "?!foo"}),
        ),
        (
            MatcherValue::literal("!?foo"),
            json!({"not": false, "value": "!?foo"}),
        ),
        (
            MatcherValue::optional_literal("!foo"),
            json!({"not": false, "optional": true, "value": "!foo"}),
        ),
    ];
    for (value, want) in cases {
        let got = serde_json::to_value(value).unwrap();
        assert_eq!(&got, want, "serialize {value:?}");
    }
}

// A blank value must NOT use the object form: the server's object-form reader
// ignores a blank "value", so escaping one would silently drop the matcher.
#[test]
fn blank_never_uses_object_form() {
    let cases: &[(MatcherValue, &str)] = &[
        (MatcherValue::literal(""), ""),
        // whitespace-only is blank too, so the value is dropped from the plain form
        (MatcherValue::literal("   "), ""),
        (MatcherValue::not_literal(""), "!"),
    ];
    for (value, want) in cases {
        let got = serde_json::to_value(value).unwrap();
        let got_str = got
            .as_str()
            .unwrap_or_else(|| panic!("serialize {value:?} = {got}, want a plain string"));
        assert_eq!(got_str, *want, "serialize {value:?}");
    }
}

// Both wire forms must decode, and re-encoding must be stable.
#[test]
fn round_trips_both_wire_forms() {
    for wire in [
        json!("foo"),
        json!("!foo"),
        json!({"not": false, "value": "!foo"}),
        json!({"not": true, "value": "!foo"}),
        json!({"not": false, "optional": true, "value": "?foo"}),
    ] {
        let a: MatcherValue = serde_json::from_value(wire.clone()).unwrap();
        let reencoded = serde_json::to_value(&a).unwrap();
        let b: MatcherValue = serde_json::from_value(reencoded).unwrap();
        assert_eq!(a, b, "round-trip {wire}");
    }
}

// `!forbidden` and {"not":true,"value":"forbidden"} are the same matcher; the
// object form normalises to the shorter fixed-point plain string, matching the
// Java NottableString serializer (representation-only, not a mutation).
#[test]
fn negated_object_form_normalises_to_bare_marker() {
    let from_object: MatcherValue =
        serde_json::from_value(json!({"not": true, "value": "forbidden"})).unwrap();
    assert_eq!(from_object, MatcherValue::not_literal("forbidden"));
    assert_eq!(
        serde_json::to_value(&from_object).unwrap(),
        json!("!forbidden")
    );
}

// The plain string builders must keep their current marker-parsing meaning, so
// the new escape is additive rather than a silent behaviour change.
#[test]
fn plain_builders_preserve_marker_meaning() {
    let req = HttpRequest::new()
        .header("X-Neg", "!foo") // still a negation
        .query_param("q", "?maybe") // still optional
        .cookie("ck", "plain");
    let wire = serde_json::to_value(&req).unwrap();
    assert_eq!(wire["headers"]["X-Neg"], json!(["!foo"]));
    assert_eq!(wire["queryStringParameters"]["q"], json!(["?maybe"]));
    assert_eq!(wire["cookies"]["ck"], json!("plain"));
}

// The matcher builders must emit the object form for a literal marker value.
#[test]
fn matcher_builders_emit_object_form_for_literals() {
    let req = HttpRequest::new()
        .path("/x")
        .header_matcher("X-Tag", MatcherValue::literal("!foo"))
        .query_param_matcher("q", MatcherValue::literal("?foo"))
        .cookie_matcher("ck", MatcherValue::literal("!foo"))
        .path_param_matcher("id", MatcherValue::literal("!foo"));
    let wire = serde_json::to_value(&req).unwrap();

    assert_eq!(
        wire["headers"]["X-Tag"][0],
        json!({"not": false, "value": "!foo"})
    );
    assert_eq!(
        wire["queryStringParameters"]["q"][0],
        json!({"not": false, "value": "?foo"})
    );
    assert_eq!(
        wire["cookies"]["ck"],
        json!({"not": false, "value": "!foo"})
    );
    assert_eq!(
        wire["pathParameters"]["id"][0],
        json!({"not": false, "value": "!foo"})
    );
}

// A genuinely negated matcher builder still emits the bare marker string.
#[test]
fn matcher_builder_negation_stays_bare_marker() {
    let req = HttpRequest::new().header_matcher("X-Env", MatcherValue::not_literal("dev"));
    let wire = serde_json::to_value(&req).unwrap();
    assert_eq!(wire["headers"]["X-Env"], json!(["!dev"]));
}

// The escape must survive a decode, not just an encode: the client must decode
// its OWN object-form output back into the literal matcher value.
#[test]
fn object_form_round_trips_through_request_decode() {
    let original = HttpRequest::new()
        .path("/x")
        .header_matcher("X-Tag", MatcherValue::literal("!foo"))
        .cookie_matcher("ck", MatcherValue::literal("!foo"));
    let wire = serde_json::to_value(&original).unwrap();

    let decoded: HttpRequest = serde_json::from_value(wire.clone()).unwrap();
    // An ambiguous value decodes into the additive matcher map, leaving the
    // plain map empty for that field.
    assert_eq!(
        decoded.header_matchers.as_ref().unwrap()["X-Tag"],
        vec![MatcherValue::literal("!foo")]
    );
    assert!(decoded.headers.is_none());
    assert_eq!(
        decoded.cookie_matchers.as_ref().unwrap()["ck"],
        MatcherValue::literal("!foo")
    );
    assert!(decoded.cookies.is_none());

    // and re-encoding reproduces the same wire form
    assert_eq!(serde_json::to_value(&decoded).unwrap(), wire);
}

// Mixing the plain and matcher builders for the same field must not drop the
// plain values: the first matcher call migrates them into the matcher map.
#[test]
fn matcher_builder_migrates_existing_plain_values() {
    let req = HttpRequest::new()
        .path("/x")
        .header("X-Plain", "keep-me")
        .header_matcher("X-Tag", MatcherValue::literal("!foo"));
    let wire = serde_json::to_value(&req).unwrap();
    // both the migrated plain header and the escaped literal are on the wire
    assert_eq!(wire["headers"]["X-Plain"], json!(["keep-me"]));
    assert_eq!(
        wire["headers"]["X-Tag"][0],
        json!({"not": false, "value": "!foo"})
    );
}

// path_param_matcher on a key already carrying plain values replaces them with
// the matcher (object) form; document-and-verify the replacement.
#[test]
fn path_param_matcher_replaces_plain_values_for_same_key() {
    let req = HttpRequest::new()
        .path("/x/{id}")
        .path_param("id", "old")
        .path_param_matcher("id", MatcherValue::literal("!new"));
    let wire = serde_json::to_value(&req).unwrap();
    assert_eq!(
        wire["pathParameters"]["id"],
        json!([{"not": false, "value": "!new"}])
    );
}

// Plain-form traffic must decode into plain-string matcher values exactly as
// before, so nothing about existing behaviour changes on the read path.
#[test]
fn plain_form_decodes_unchanged() {
    let wire = json!({
        "path": "/x",
        "headers": {"X-Tag": ["foo", "!bar"]},
        "cookies": {"ck": "v"}
    });
    let decoded: HttpRequest = serde_json::from_value(wire.clone()).unwrap();
    // Plain-form values decode into the plain map (as strings) and leave the
    // additive matcher map empty, so existing behaviour is unchanged.
    assert_eq!(
        decoded.headers.as_ref().unwrap()["X-Tag"],
        vec!["foo".to_string(), "!bar".to_string()]
    );
    assert!(decoded.header_matchers.is_none());
    assert_eq!(decoded.cookies.as_ref().unwrap()["ck"], "v");
    assert!(decoded.cookie_matchers.is_none());
    // re-encoding is byte-identical to the plain input
    assert_eq!(serde_json::to_value(&decoded).unwrap(), wire);
}
