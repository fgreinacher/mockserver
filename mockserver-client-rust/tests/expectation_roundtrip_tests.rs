//! Round-trip fidelity tests for the `Expectation` model and its sub-types.
//!
//! Each test deserialises a wire-shape JSON fixture into the typed model, then
//! serialises it back and asserts semantic (order-independent) JSON equality
//! with the input. This proves every modelled feature survives a
//! `from_str -> to_value` round-trip with no silently-dropped fields.
//!
//! Fixtures are self-contained inline strings — no external files.

use mockserver_client::*;
use serde_json::{json, Value};

/// Deserialise `json` as an `Expectation`, serialise it back, and assert the
/// output JSON is semantically equal to the input.
fn round_trip_expectation(json_str: &str) -> Expectation {
    let input: Value = serde_json::from_str(json_str).expect("fixture is valid JSON");
    let exp: Expectation =
        serde_json::from_value(input.clone()).expect("fixture deserialises to Expectation");
    let output = serde_json::to_value(&exp).expect("Expectation serialises");
    assert_eq!(
        output, input,
        "Expectation did not round-trip.\n input: {input:#}\noutput: {output:#}"
    );
    exp
}

/// Same, for a bare `Body`.
fn round_trip_body(value: Value) -> Body {
    let body: Body = serde_json::from_value(value.clone()).expect("Body deserialises");
    let output = serde_json::to_value(&body).expect("Body serialises");
    assert_eq!(
        output, value,
        "Body did not round-trip.\n input: {value:#}\noutput: {output:#}"
    );
    body
}

// ---------------------------------------------------------------------------
// Expectation-level new fields
// ---------------------------------------------------------------------------

#[test]
fn percentage_chaos_rate_limit_round_trip() {
    let exp = round_trip_expectation(
        r#"{
            "percentage": 25,
            "chaos": {
                "errorStatus": 503,
                "errorProbability": 0.3,
                "dropConnectionProbability": 0.1,
                "latency": { "timeUnit": "MILLISECONDS", "value": 200 },
                "quotaName": "tenant-a",
                "quotaLimit": 100,
                "quotaWindowMillis": 60000,
                "graphqlErrors": true,
                "graphqlErrorMessage": "boom"
            },
            "rateLimit": {
                "name": "shared",
                "algorithm": "token_bucket",
                "burst": 10,
                "refillPerSecond": 2.5,
                "errorStatus": 429
            },
            "httpRequest": { "path": "/x" },
            "httpResponse": { "statusCode": 200 }
        }"#,
    );
    assert_eq!(exp.percentage, Some(25));
    assert_eq!(exp.chaos.as_ref().unwrap().error_status, Some(503));
    assert_eq!(
        exp.rate_limit.as_ref().unwrap().algorithm.as_deref(),
        Some("token_bucket")
    );
}

#[test]
fn namespace_and_timestamp_round_trip() {
    let exp = round_trip_expectation(
        r#"{
            "namespace": "team-b",
            "timestamp": "2026-07-03T10:00:00Z",
            "httpRequest": { "path": "/y" },
            "httpResponse": { "statusCode": 204 }
        }"#,
    );
    assert_eq!(exp.namespace.as_deref(), Some("team-b"));
    assert_eq!(exp.timestamp.as_deref(), Some("2026-07-03T10:00:00Z"));
}

#[test]
fn unknown_top_level_field_survives_via_extra() {
    // A field the typed model does not name must NOT be dropped.
    let exp = round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/z" },
            "httpResponse": { "statusCode": 200 },
            "someFutureField": { "nested": [1, 2, 3] },
            "anotherUnknown": "keep-me"
        }"#,
    );
    assert_eq!(exp.extra.get("anotherUnknown"), Some(&json!("keep-me")));
    assert!(exp.extra.contains_key("someFutureField"));
}

// ---------------------------------------------------------------------------
// Forward-family actions
// ---------------------------------------------------------------------------

#[test]
fn http_forward_with_fallback_round_trips() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/api" },
            "httpForwardWithFallback": {
                "httpForward": { "host": "backend", "port": 8080, "scheme": "HTTPS" },
                "fallbackResponse": { "statusCode": 503, "body": "unavailable" },
                "fallbackOnStatusCodes": [500, 502, 503],
                "fallbackOnTimeout": true,
                "primary": true
            }
        }"#,
    );
}

#[test]
fn http_forward_validate_action_round_trips() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/api" },
            "httpForwardValidateAction": {
                "specUrlOrPayload": "https://example.com/openapi.yaml",
                "host": "backend",
                "port": 443,
                "scheme": "HTTPS",
                "validateRequest": true,
                "validateResponse": true,
                "validationMode": "STRICT"
            }
        }"#,
    );
}

#[test]
fn http_override_forwarded_request_modern_shape_round_trips() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/api" },
            "httpOverrideForwardedRequest": {
                "delay": { "timeUnit": "MILLISECONDS", "value": 50 },
                "requestOverride": { "path": "/rewritten", "headers": { "X-Trace": ["1"] } },
                "requestModifier": {
                    "path": { "regex": "^/old", "substitution": "/new" },
                    "headers": { "add": { "X-Add": ["v"] }, "remove": ["X-Drop"] }
                },
                "responseOverride": { "statusCode": 201 },
                "responseModifier": { "jsonMergePatch": { "patched": true } },
                "primary": true
            }
        }"#,
    );
}

#[test]
fn http_override_forwarded_request_legacy_shape_round_trips() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/api" },
            "httpOverrideForwardedRequest": {
                "httpRequest": { "path": "/forwarded" },
                "httpResponse": { "statusCode": 200 }
            }
        }"#,
    );
}

// ---------------------------------------------------------------------------
// LLM response
// ---------------------------------------------------------------------------

#[test]
fn http_llm_response_round_trips() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/v1/messages" },
            "httpLlmResponse": {
                "provider": "ANTHROPIC",
                "model": "claude-x",
                "delay": { "timeUnit": "MILLISECONDS", "value": 10 },
                "completion": {
                    "text": "hello",
                    "stopReason": "end_turn",
                    "streaming": true,
                    "toolCalls": [
                        { "id": "t1", "name": "get_weather", "arguments": "{\"city\":\"x\"}" }
                    ],
                    "usage": {
                        "inputTokens": 12,
                        "outputTokens": 34,
                        "cachedInputTokens": 5
                    },
                    "streamingPhysics": {
                        "timeToFirstToken": { "timeUnit": "MILLISECONDS", "value": 100 },
                        "tokensPerSecond": 50,
                        "jitter": 0.2,
                        "subwordStreaming": true
                    }
                },
                "chaos": { "errorStatus": 429, "retryAfter": "5", "malformedSse": true },
                "primary": true
            }
        }"#,
    );
}

// ---------------------------------------------------------------------------
// gRPC bidi response
// ---------------------------------------------------------------------------

#[test]
fn grpc_bidi_response_round_trips() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/grpc" },
            "grpcBidiResponse": {
                "statusName": "OK",
                "headers": { "x-meta": ["a", "b"] },
                "messages": [
                    { "json": "{\"id\":1}", "templateType": "VELOCITY" }
                ],
                "rules": [
                    {
                        "matchJson": "{\"cmd\":\"ping\"}",
                        "responses": [ { "json": "{\"pong\":true}" } ]
                    }
                ],
                "closeConnection": true
            }
        }"#,
    );
}

// ---------------------------------------------------------------------------
// before/after actions, capture, steps
// ---------------------------------------------------------------------------

#[test]
fn before_and_after_actions_array_form_round_trips() {
    let exp = round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/hooked" },
            "httpResponse": { "statusCode": 200 },
            "beforeActions": [
                { "httpRequest": { "method": "POST", "path": "/notify" }, "blocking": true }
            ],
            "afterActions": [
                { "httpClassCallback": { "callbackClass": "com.example.After" }, "failurePolicy": "BEST_EFFORT" }
            ]
        }"#,
    );
    assert_eq!(exp.before_actions.as_ref().unwrap().len(), 1);
    assert_eq!(exp.after_actions.as_ref().unwrap().len(), 1);
}

#[test]
fn before_actions_single_object_form_is_accepted() {
    // The server accepts a single object; we normalise it into a one-element Vec.
    let input = r#"{
        "httpRequest": { "path": "/hooked" },
        "httpResponse": { "statusCode": 200 },
        "beforeActions": { "httpRequest": { "path": "/notify" } }
    }"#;
    let exp: Expectation = serde_json::from_str(input).unwrap();
    assert_eq!(exp.before_actions.as_ref().unwrap().len(), 1);
    assert_eq!(
        exp.before_actions.as_ref().unwrap()[0]
            .http_request
            .as_ref()
            .unwrap()
            .path
            .as_deref(),
        Some("/notify")
    );
    // Re-serialises as an array (the other valid wire form).
    let out = serde_json::to_value(&exp).unwrap();
    assert!(out["beforeActions"].is_array());
}

#[test]
fn capture_array_form_round_trips() {
    let exp = round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/capture" },
            "httpResponse": { "statusCode": 200 },
            "capture": [
                { "source": "jsonPath", "expression": "$.id", "into": "userId" },
                { "source": "header", "expression": "X-Trace", "into": "trace" }
            ]
        }"#,
    );
    assert_eq!(exp.capture.as_ref().unwrap().len(), 2);
}

#[test]
fn steps_round_trip() {
    round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/multi" },
            "steps": [
                { "httpResponse": { "statusCode": 200, "body": "first" }, "responder": true },
                { "httpForward": { "host": "downstream", "port": 9000 }, "delay": { "timeUnit": "MILLISECONDS", "value": 5 } }
            ]
        }"#,
    );
}

// ---------------------------------------------------------------------------
// Request matcher gaps
// ---------------------------------------------------------------------------

#[test]
fn request_new_fields_round_trip() {
    let exp = round_trip_expectation(
        r#"{
            "httpRequest": {
                "not": true,
                "secure": true,
                "keepAlive": false,
                "protocol": "HTTP_2",
                "path": "/users/{id}",
                "pathParameters": { "id": ["42"] },
                "cookies": { "session": "abc" },
                "clientCertificate": { "subject": "CN=test" }
            },
            "httpResponse": { "statusCode": 200 }
        }"#,
    );
    let req = &exp.http_request;
    assert_eq!(req.secure, Some(true));
    assert_eq!(req.not, Some(true));
    assert_eq!(req.protocol.as_deref(), Some("HTTP_2"));
    assert!(req.path_parameters.is_some());
    assert!(req.cookies.is_some());
    // clientCertificate is not typed yet — it must survive via the request extra map.
    assert!(req.extra.contains_key("clientCertificate"));
}

// ---------------------------------------------------------------------------
// Response gaps
// ---------------------------------------------------------------------------

#[test]
fn response_new_fields_round_trip() {
    let exp = round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/r" },
            "httpResponse": {
                "statusCode": 404,
                "reasonPhrase": "Not Here",
                "statusCodeRange": "2xx",
                "cookies": { "sid": "xyz" },
                "trailers": { "x-checksum": ["deadbeef"] },
                "generateFromSchema": "{\"type\":\"object\"}",
                "connectionOptions": {
                    "suppressContentLengthHeader": true,
                    "chunkSize": 128,
                    "chunkDelay": { "timeUnit": "MILLISECONDS", "value": 3 },
                    "closeSocket": true
                },
                "recoverAfter": { "failTimes": 2, "idempotencyHeader": "Idempotency-Key" },
                "primary": false
            }
        }"#,
    );
    let resp = exp.http_response.as_ref().unwrap();
    assert_eq!(resp.reason_phrase.as_deref(), Some("Not Here"));
    assert!(resp.connection_options.is_some());
    assert!(resp.recover_after.is_some());
    assert!(resp.cookies.is_some());
}

#[test]
fn unknown_response_field_survives_via_extra() {
    let exp = round_trip_expectation(
        r#"{
            "httpRequest": { "path": "/r" },
            "httpResponse": { "statusCode": 200, "futureResponseThing": 99 }
        }"#,
    );
    assert_eq!(
        exp.http_response
            .as_ref()
            .unwrap()
            .extra
            .get("futureResponseThing"),
        Some(&json!(99))
    );
}

// ---------------------------------------------------------------------------
// Body value-key variants
// ---------------------------------------------------------------------------

#[test]
fn body_string_substring_round_trips() {
    round_trip_body(json!({ "type": "STRING", "string": "abc", "subString": true }));
}

#[test]
fn body_json_with_match_type_round_trips() {
    // matchType must NOT be dropped by the typed-JSON fast path.
    round_trip_body(json!({ "type": "JSON", "json": "{\"a\":1}", "matchType": "STRICT" }));
}

#[test]
fn body_json_schema_round_trips() {
    round_trip_body(json!({ "type": "JSON_SCHEMA", "jsonSchema": "{\"type\":\"object\"}" }));
}

#[test]
fn body_xml_round_trips() {
    round_trip_body(json!({ "type": "XML", "xml": "<a/>", "contentType": "application/xml" }));
}

#[test]
fn body_xml_schema_round_trips() {
    round_trip_body(json!({ "type": "XML_SCHEMA", "xmlSchema": "<schema/>" }));
}

#[test]
fn body_xpath_round_trips() {
    round_trip_body(json!({ "type": "XPATH", "xpath": "/a/b" }));
}

#[test]
fn body_parameters_round_trips() {
    round_trip_body(json!({ "type": "PARAMETERS", "parameters": { "q": ["1", "2"] } }));
}

#[test]
fn body_binary_round_trips() {
    round_trip_body(
        json!({ "type": "BINARY", "base64Bytes": "AQIDBA==", "contentType": "application/octet-stream" }),
    );
}

#[test]
fn body_graphql_round_trips() {
    round_trip_body(json!({
        "type": "GRAPHQL",
        "query": "query { me { id } }",
        "operationName": "me",
        "selectionSetMatchType": "AST_SUBSET"
    }));
}

#[test]
fn body_multipart_round_trips() {
    round_trip_body(json!({
        "type": "MULTIPART",
        "fields": { "file": ["x"] },
        "filenames": { "file": ["a.txt"] }
    }));
}

#[test]
fn body_wasm_round_trips() {
    round_trip_body(json!({ "type": "WASM", "module": "base64...", "entrypoint": "match" }));
}

#[test]
fn body_constructors_produce_expected_wire_shape() {
    assert_eq!(
        serde_json::to_value(Body::string("abc", true)).unwrap(),
        json!({ "type": "STRING", "string": "abc", "subString": true })
    );
    assert_eq!(
        serde_json::to_value(Body::xml("<a/>")).unwrap(),
        json!({ "type": "XML", "xml": "<a/>" })
    );
    assert_eq!(
        serde_json::to_value(Body::json_schema("{}")).unwrap(),
        json!({ "type": "JSON_SCHEMA", "jsonSchema": "{}" })
    );
    assert_eq!(
        serde_json::to_value(Body::graphql("{ x }")).unwrap(),
        json!({ "type": "GRAPHQL", "query": "{ x }" })
    );
    assert_eq!(
        serde_json::to_value(Body::binary([1u8, 2, 3, 4], None)).unwrap(),
        json!({ "type": "BINARY", "base64Bytes": "AQIDBA==" })
    );
}

// ---------------------------------------------------------------------------
// Kitchen sink
// ---------------------------------------------------------------------------

#[test]
fn kitchen_sink_expectation_round_trips() {
    // Exercises a broad slice of the model at once, including previously-missing
    // fields and an unknown field, all in a single expectation.
    round_trip_expectation(
        r#"{
            "id": "ks-1",
            "priority": 5,
            "percentage": 90,
            "namespace": "tenant-x",
            "timestamp": "2026-07-03T00:00:00Z",
            "chaos": {
                "errorStatus": 500,
                "errorProbability": 0.1,
                "latency": { "timeUnit": "MILLISECONDS", "value": 25 },
                "seed": 7
            },
            "rateLimit": {
                "name": "ks",
                "algorithm": "fixed_window",
                "limit": 50,
                "windowMillis": 1000
            },
            "httpRequest": {
                "method": "POST",
                "path": "/orders/{orderId}",
                "secure": true,
                "protocol": "HTTP_1_1",
                "pathParameters": { "orderId": ["123"] },
                "queryStringParameters": { "verbose": ["true"] },
                "headers": { "Content-Type": ["application/json"] },
                "cookies": { "session": "s1" },
                "body": { "type": "JSON", "json": "{\"ok\":true}", "matchType": "ONLY_MATCHING_FIELDS" }
            },
            "httpResponse": {
                "statusCode": 201,
                "reasonPhrase": "Created",
                "headers": { "Location": ["/orders/123"] },
                "cookies": { "issued": "yes" },
                "connectionOptions": { "keepAliveOverride": true },
                "delay": { "timeUnit": "MILLISECONDS", "value": 15 }
            },
            "beforeActions": [
                { "httpRequest": { "path": "/audit" }, "blocking": false }
            ],
            "capture": [
                { "source": "pathParameter", "expression": "orderId", "into": "oid" }
            ],
            "scenarioName": "checkout",
            "scenarioState": "started",
            "newScenarioState": "ordered",
            "times": { "remainingTimes": 3, "unlimited": false },
            "timeToLive": { "timeUnit": "SECONDS", "timeToLive": 60, "unlimited": false },
            "unknownFutureKnob": { "keep": "me" }
        }"#,
    );
}
