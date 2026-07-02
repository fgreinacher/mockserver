//! Unit tests — JSON building and model construction.
//! These run with NO server.

use mockserver_client::*;

// ---------------------------------------------------------------------------
// HttpRequest builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_empty_request_serializes_to_empty_object() {
    let req = HttpRequest::new();
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json, serde_json::json!({}));
}

#[test]
fn test_request_method_and_path() {
    let req = HttpRequest::new().method("GET").path("/hello");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["method"], "GET");
    assert_eq!(json["path"], "/hello");
}

#[test]
fn test_request_query_params() {
    let req = HttpRequest::new()
        .path("/search")
        .query_param("q", "rust")
        .query_param("page", "1");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["queryStringParameters"]["q"],
        serde_json::json!(["rust"])
    );
    assert_eq!(
        json["queryStringParameters"]["page"],
        serde_json::json!(["1"])
    );
}

#[test]
fn test_request_multiple_values_same_key() {
    let req = HttpRequest::new()
        .query_param("tag", "a")
        .query_param("tag", "b");
    let json = serde_json::to_value(&req).unwrap();
    let tags = json["queryStringParameters"]["tag"].as_array().unwrap();
    assert_eq!(tags.len(), 2);
    assert!(tags.contains(&serde_json::json!("a")));
    assert!(tags.contains(&serde_json::json!("b")));
}

#[test]
fn test_request_headers() {
    let req = HttpRequest::new()
        .header("Content-Type", "application/json")
        .header("Accept", "text/plain");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["headers"]["Content-Type"],
        serde_json::json!(["application/json"])
    );
    assert_eq!(json["headers"]["Accept"], serde_json::json!(["text/plain"]));
}

#[test]
fn test_request_plain_body() {
    let req = HttpRequest::new().body("hello world");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"], "hello world");
}

#[test]
fn test_request_json_body() {
    let req = HttpRequest::new().json_body(serde_json::json!({"key": "value"}));
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "JSON");
    // The json field is the serialized string of the value
    let json_str: &str = json["body"]["json"].as_str().unwrap();
    let parsed: serde_json::Value = serde_json::from_str(json_str).unwrap();
    assert_eq!(parsed, serde_json::json!({"key": "value"}));
}

#[test]
fn test_request_jwt_matcher() {
    let req = HttpRequest::new().method("GET").path("/secure").jwt(
        Jwt::new()
            .claim("sub", "user-123")
            .claim("role", "!admin")
            .claim("email", "^.+@example.com$")
            .issuer("https://issuer.example.com")
            .audience("my-api")
            .algorithm("RS256"),
    );
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["method"], "GET");
    assert_eq!(json["path"], "/secure");
    // Claims map: exact, negated, and regex values are plain strings.
    assert_eq!(
        json["jwt"],
        serde_json::json!({
            "claims": {
                "sub": "user-123",
                "role": "!admin",
                "email": "^.+@example.com$"
            },
            "issuer": "https://issuer.example.com",
            "audience": "my-api",
            "algorithm": "RS256"
        })
    );
    // Unset optional fields must be omitted entirely.
    assert!(json["jwt"].get("header").is_none());
    assert!(json["jwt"].get("scheme").is_none());
}

#[test]
fn test_request_jwt_full_wire_shape() {
    let req = HttpRequest::new().jwt(
        Jwt::new()
            .claim("sub", "user-123")
            .claim("role", "!admin")
            .claim("email", "^.+@example.com$")
            .issuer("https://issuer.example.com")
            .audience("my-api")
            .algorithm("RS256")
            .header("authorization")
            .scheme("Bearer"),
    );
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json,
        serde_json::json!({
            "jwt": {
                "claims": {
                    "sub": "user-123",
                    "role": "!admin",
                    "email": "^.+@example.com$"
                },
                "issuer": "https://issuer.example.com",
                "audience": "my-api",
                "algorithm": "RS256",
                "header": "authorization",
                "scheme": "Bearer"
            }
        })
    );
}

#[test]
fn test_request_all_of_body() {
    let req = HttpRequest::new().body_value(Body::all_of(vec![
        Body::json_path("$.name"),
        Body::regex(".*active.*"),
    ]));
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["body"],
        serde_json::json!({
            "type": "ALL_OF",
            "bodyAllOf": [
                { "type": "JSON_PATH", "jsonPath": "$.name" },
                { "type": "REGEX", "regex": ".*active.*" }
            ]
        })
    );
}

#[test]
fn test_all_of_body_round_trips() {
    let body = Body::all_of(vec![Body::json_path("$.id"), Body::regex("^ok$")]);
    let value = serde_json::to_value(&body).unwrap();
    let parsed: Body = serde_json::from_value(value).unwrap();
    assert_eq!(parsed, body);
}

// ---------------------------------------------------------------------------
// HttpResponse builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_response_status_code() {
    let resp = HttpResponse::new().status_code(200);
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["statusCode"], 200);
}

#[test]
fn test_response_with_body_and_headers() {
    let resp = HttpResponse::new()
        .status_code(201)
        .header("Location", "/api/42")
        .body("{\"id\": 42}");
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["statusCode"], 201);
    assert_eq!(json["body"], "{\"id\": 42}");
    assert_eq!(json["headers"]["Location"], serde_json::json!(["/api/42"]));
}

#[test]
fn test_response_with_delay() {
    let resp = HttpResponse::new()
        .status_code(200)
        .delay(Delay::milliseconds(500));
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["delay"]["value"], 500);
}

// ---------------------------------------------------------------------------
// HttpForward builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_forward_basic() {
    let fwd = HttpForward::new("backend.local", 8080);
    let json = serde_json::to_value(&fwd).unwrap();
    assert_eq!(json["host"], "backend.local");
    assert_eq!(json["port"], 8080);
    assert!(json.get("scheme").is_none());
}

#[test]
fn test_forward_with_scheme() {
    let fwd = HttpForward::new("secure.local", 443).scheme("HTTPS");
    let json = serde_json::to_value(&fwd).unwrap();
    assert_eq!(json["scheme"], "HTTPS");
}

// ---------------------------------------------------------------------------
// HttpError builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_error_drop_connection() {
    let err = HttpError::new().drop_connection(true);
    let json = serde_json::to_value(&err).unwrap();
    assert_eq!(json["dropConnection"], true);
}

// ---------------------------------------------------------------------------
// Times and TimeToLive tests
// ---------------------------------------------------------------------------

#[test]
fn test_times_unlimited() {
    let t = Times::unlimited();
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["unlimited"], true);
    assert!(json.get("remainingTimes").is_none());
}

#[test]
fn test_times_exactly() {
    let t = Times::exactly(3);
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["unlimited"], false);
    assert_eq!(json["remainingTimes"], 3);
}

#[test]
fn test_times_once() {
    let t = Times::once();
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["remainingTimes"], 1);
    assert_eq!(json["unlimited"], false);
}

#[test]
fn test_ttl_unlimited() {
    let ttl = TimeToLive::unlimited();
    let json = serde_json::to_value(&ttl).unwrap();
    assert_eq!(json["unlimited"], true);
    assert!(json.get("timeUnit").is_none());
}

#[test]
fn test_ttl_seconds() {
    let ttl = TimeToLive::seconds(30);
    let json = serde_json::to_value(&ttl).unwrap();
    assert_eq!(json["unlimited"], false);
    assert_eq!(json["timeUnit"], "SECONDS");
    assert_eq!(json["timeToLive"], 30);
}

// ---------------------------------------------------------------------------
// VerificationTimes tests
// ---------------------------------------------------------------------------

#[test]
fn test_verification_times_at_least() {
    let vt = VerificationTimes::at_least(2);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 2);
    // atMost is always sent as the -1 "unbounded" sentinel so the server does
    // not default it to 0 (which would make at_least(2) an impossible between(2,0)).
    assert_eq!(json["atMost"], -1);
}

#[test]
fn test_verification_times_at_most() {
    let vt = VerificationTimes::at_most(3);
    let json = serde_json::to_value(&vt).unwrap();
    // atLeast is sent as the -1 "unbounded" sentinel; atMost carries the bound.
    assert_eq!(json["atLeast"], -1);
    assert_eq!(json["atMost"], 3);
}

#[test]
fn test_verification_times_exactly() {
    let vt = VerificationTimes::exactly(5);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 5);
    assert_eq!(json["atMost"], 5);
}

#[test]
fn test_verification_times_between() {
    let vt = VerificationTimes::between(1, 10);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 1);
    assert_eq!(json["atMost"], 10);
}

// ---------------------------------------------------------------------------
// Full Expectation tests
// ---------------------------------------------------------------------------

#[test]
fn test_full_expectation_json() {
    let expectation = Expectation::new(
        HttpRequest::new()
            .method("GET")
            .path("/hello")
            .query_param("q", "x")
            .header("H", "v"),
    )
    .respond(
        HttpResponse::new()
            .status_code(200)
            .header("Content-Type", "application/json")
            .body("{\"k\":1}")
            .delay(Delay::milliseconds(0)),
    )
    .times(Times::once())
    .time_to_live(TimeToLive::unlimited())
    .priority(0);

    let json = serde_json::to_value(&expectation).unwrap();

    // Verify request
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/hello");
    assert_eq!(
        json["httpRequest"]["queryStringParameters"]["q"],
        serde_json::json!(["x"])
    );
    assert_eq!(
        json["httpRequest"]["headers"]["H"],
        serde_json::json!(["v"])
    );

    // Verify response
    assert_eq!(json["httpResponse"]["statusCode"], 200);
    assert_eq!(json["httpResponse"]["body"], "{\"k\":1}");
    assert_eq!(
        json["httpResponse"]["headers"]["Content-Type"],
        serde_json::json!(["application/json"])
    );
    assert_eq!(json["httpResponse"]["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["httpResponse"]["delay"]["value"], 0);

    // Verify times
    assert_eq!(json["times"]["remainingTimes"], 1);
    assert_eq!(json["times"]["unlimited"], false);

    // Verify TTL
    assert_eq!(json["timeToLive"]["unlimited"], true);

    // Verify priority
    assert_eq!(json["priority"], 0);
}

#[test]
fn test_expectation_with_forward() {
    let expectation = Expectation::new(HttpRequest::new().method("GET").path("/proxy"))
        .forward(HttpForward::new("backend", 8080).scheme("HTTP"));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpForward"]["host"], "backend");
    assert_eq!(json["httpForward"]["port"], 8080);
    assert_eq!(json["httpForward"]["scheme"], "HTTP");
    assert!(json.get("httpResponse").is_none());
}

#[test]
fn test_expectation_with_error() {
    let expectation = Expectation::new(HttpRequest::new().path("/fail"))
        .error(HttpError::new().drop_connection(true));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpError"]["dropConnection"], true);
    assert!(json.get("httpResponse").is_none());
    assert!(json.get("httpForward").is_none());
}

#[test]
fn test_expectation_with_id() {
    let expectation = Expectation::new(HttpRequest::new().path("/x"))
        .id("my-expectation-1")
        .respond(HttpResponse::new().status_code(204));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["id"], "my-expectation-1");
}

// ---------------------------------------------------------------------------
// Verification JSON tests
// ---------------------------------------------------------------------------

#[test]
fn test_verification_json() {
    let v = Verification {
        http_request: Some(HttpRequest::new().method("GET").path("/hello")),
        http_response: None,
        times: Some(VerificationTimes::at_least(1)),
        maximum_number_of_request_to_return_in_verification_failure: None,
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/hello");
    assert_eq!(json["times"]["atLeast"], 1);
    // httpResponse should be absent (not null)
    assert!(json.get("httpResponse").is_none());
    // maximumNumberOfRequestToReturnInVerificationFailure should be absent
    assert!(json
        .get("maximumNumberOfRequestToReturnInVerificationFailure")
        .is_none());
}

#[test]
fn test_verification_with_response_json() {
    let v = Verification {
        http_request: Some(HttpRequest::new().method("POST").path("/api")),
        http_response: Some(
            HttpResponse::new()
                .status_code(201)
                .header("Location", "/api/42"),
        ),
        times: Some(VerificationTimes::exactly(1)),
        maximum_number_of_request_to_return_in_verification_failure: None,
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(json["httpRequest"]["method"], "POST");
    assert_eq!(json["httpRequest"]["path"], "/api");
    assert_eq!(json["httpResponse"]["statusCode"], 201);
    assert_eq!(
        json["httpResponse"]["headers"]["Location"],
        serde_json::json!(["/api/42"])
    );
    assert_eq!(json["times"]["atLeast"], 1);
    assert_eq!(json["times"]["atMost"], 1);
}

#[test]
fn test_verification_response_only_json() {
    let v = Verification {
        http_request: None,
        http_response: Some(HttpResponse::new().status_code(500)),
        times: Some(VerificationTimes::at_most(0)),
        maximum_number_of_request_to_return_in_verification_failure: None,
    };
    let json = serde_json::to_value(&v).unwrap();
    // httpRequest should be ABSENT (not null) for response-only verification
    assert!(
        json.get("httpRequest").is_none(),
        "httpRequest must be absent for response-only verification"
    );
    assert_eq!(json["httpResponse"]["statusCode"], 500);
    assert_eq!(json["times"]["atMost"], 0);
}

#[test]
fn test_verification_with_max_failures() {
    let v = Verification {
        http_request: Some(HttpRequest::new().path("/test")),
        http_response: None,
        times: Some(VerificationTimes::at_least(1)),
        maximum_number_of_request_to_return_in_verification_failure: Some(5),
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(
        json["maximumNumberOfRequestToReturnInVerificationFailure"],
        5
    );
}

#[test]
fn test_verification_sequence_json() {
    let vs = VerificationSequence {
        http_requests: Some(vec![
            HttpRequest::new().path("/first"),
            HttpRequest::new().path("/second"),
        ]),
        http_responses: None,
    };
    let json = serde_json::to_value(&vs).unwrap();
    let requests = json["httpRequests"].as_array().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0]["path"], "/first");
    assert_eq!(requests[1]["path"], "/second");
    // httpResponses should be absent
    assert!(json.get("httpResponses").is_none());
}

#[test]
fn test_verification_sequence_with_responses_json() {
    let vs = VerificationSequence {
        http_requests: Some(vec![
            HttpRequest::new().method("GET").path("/a"),
            HttpRequest::new().method("POST").path("/b"),
        ]),
        http_responses: Some(vec![
            HttpResponse::new().status_code(200),
            HttpResponse::new().status_code(201).body("{\"id\":1}"),
        ]),
    };
    let json = serde_json::to_value(&vs).unwrap();

    let requests = json["httpRequests"].as_array().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0]["method"], "GET");
    assert_eq!(requests[1]["method"], "POST");

    let responses = json["httpResponses"].as_array().unwrap();
    assert_eq!(responses.len(), 2);
    assert_eq!(responses[0]["statusCode"], 200);
    assert_eq!(responses[1]["statusCode"], 201);
    assert_eq!(responses[1]["body"], "{\"id\":1}");
}

#[test]
fn test_verification_sequence_responses_only_json() {
    // Edge case: verify a sequence of responses without constraining requests
    let vs = VerificationSequence {
        http_requests: None,
        http_responses: Some(vec![
            HttpResponse::new().status_code(200),
            HttpResponse::new().status_code(404),
        ]),
    };
    let json = serde_json::to_value(&vs).unwrap();
    // httpRequests should be absent
    assert!(
        json.get("httpRequests").is_none(),
        "httpRequests must be absent when None"
    );
    let responses = json["httpResponses"].as_array().unwrap();
    assert_eq!(responses.len(), 2);
    assert_eq!(responses[0]["statusCode"], 200);
    assert_eq!(responses[1]["statusCode"], 404);
}

#[test]
fn test_verification_deserialization_with_response() {
    let json = r#"{
        "httpRequest": {"method": "GET", "path": "/x"},
        "httpResponse": {"statusCode": 200},
        "times": {"atLeast": 1}
    }"#;
    let v: Verification = serde_json::from_str(json).unwrap();
    assert_eq!(
        v.http_request.as_ref().unwrap().path,
        Some("/x".to_string())
    );
    assert_eq!(v.http_response.as_ref().unwrap().status_code, Some(200));
    assert_eq!(v.times.as_ref().unwrap().at_least, Some(1));
}

#[test]
fn test_verification_sequence_deserialization_with_responses() {
    let json = r#"{
        "httpRequests": [{"path": "/a"}],
        "httpResponses": [{"statusCode": 200}]
    }"#;
    let vs: VerificationSequence = serde_json::from_str(json).unwrap();
    assert_eq!(vs.http_requests.as_ref().unwrap().len(), 1);
    assert_eq!(vs.http_responses.as_ref().unwrap().len(), 1);
    assert_eq!(
        vs.http_responses.as_ref().unwrap()[0].status_code,
        Some(200)
    );
}

// ---------------------------------------------------------------------------
// Ports JSON tests
// ---------------------------------------------------------------------------

#[test]
fn test_ports_serialization() {
    let p = Ports {
        ports: vec![1080, 1081],
    };
    let json = serde_json::to_value(&p).unwrap();
    assert_eq!(json["ports"], serde_json::json!([1080, 1081]));
}

#[test]
fn test_ports_deserialization() {
    let json = r#"{"ports":[1080,1081]}"#;
    let p: Ports = serde_json::from_str(json).unwrap();
    assert_eq!(p.ports, vec![1080, 1081]);
}

// ---------------------------------------------------------------------------
// Body deserialization tests
// ---------------------------------------------------------------------------

#[test]
fn test_body_plain_deserialization() {
    let json = r#"{"method":"GET","path":"/x","body":"hello"}"#;
    let req: HttpRequest = serde_json::from_str(json).unwrap();
    assert_eq!(req.body, Some(Body::Plain("hello".to_string())));
}

#[test]
fn test_body_typed_deserialization() {
    let json = r#"{"body":{"type":"JSON","json":"{\"key\":\"value\"}"}}"#;
    let req: HttpRequest = serde_json::from_str(json).unwrap();
    match req.body {
        Some(Body::Typed { body_type, json }) => {
            assert_eq!(body_type, "JSON");
            assert_eq!(json, "{\"key\":\"value\"}");
        }
        other => panic!("Expected Body::Typed, got {:?}", other),
    }
}

// ---------------------------------------------------------------------------
// ClientBuilder URL construction tests
// ---------------------------------------------------------------------------

#[test]
fn test_client_builder_default() {
    // Builder succeeds and produces a valid client
    let client = ClientBuilder::new("localhost", 1080).build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_with_context_path() {
    // Context path with leading slash
    let client = ClientBuilder::new("myhost", 9090)
        .context_path("/api")
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_context_path_without_slash() {
    // Context path without leading slash gets one prepended
    let client = ClientBuilder::new("myhost", 8080)
        .context_path("mock")
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_secure() {
    let client = ClientBuilder::new("secure.local", 443)
        .secure(true)
        .tls_verify(false)
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_connection_refused() {
    // Connecting to a port nothing listens on should produce a transport error
    let client = ClientBuilder::new("127.0.0.1", 19999).build().unwrap();
    let result = client.status();
    assert!(result.is_err());
    let err_msg = format!("{}", result.unwrap_err());
    assert!(
        err_msg.contains("transport")
            || err_msg.contains("error")
            || err_msg.contains("Connection refused"),
        "Should get a transport error: {err_msg}"
    );
}

// ---------------------------------------------------------------------------
// Delay tests
// ---------------------------------------------------------------------------

#[test]
fn test_delay_seconds() {
    let d = Delay::seconds(5);
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["timeUnit"], "SECONDS");
    assert_eq!(json["value"], 5);
}

// ---------------------------------------------------------------------------
// Expectation array serialization (upsert payload)
// ---------------------------------------------------------------------------

#[test]
fn test_expectation_array_serialization() {
    let expectations = vec![
        Expectation::new(HttpRequest::new().path("/a"))
            .respond(HttpResponse::new().status_code(200)),
        Expectation::new(HttpRequest::new().path("/b"))
            .respond(HttpResponse::new().status_code(201)),
    ];
    let json = serde_json::to_value(&expectations).unwrap();
    let arr = json.as_array().unwrap();
    assert_eq!(arr.len(), 2);
    assert_eq!(arr[0]["httpRequest"]["path"], "/a");
    assert_eq!(arr[0]["httpResponse"]["statusCode"], 200);
    assert_eq!(arr[1]["httpRequest"]["path"], "/b");
    assert_eq!(arr[1]["httpResponse"]["statusCode"], 201);
}

// ---------------------------------------------------------------------------
// HttpTemplate builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_http_template_inline() {
    let tmpl = HttpTemplate::new("VELOCITY", "{ statusCode: 200 }");
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(json["templateType"], "VELOCITY");
    assert_eq!(json["template"], "{ statusCode: 200 }");
    assert!(json.get("templateFile").is_none());
}

#[test]
fn test_http_template_from_file() {
    let tmpl = HttpTemplate::from_file("MUSTACHE", "/templates/response.mustache");
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(json["templateType"], "MUSTACHE");
    assert_eq!(json["templateFile"], "/templates/response.mustache");
    assert!(json.get("template").is_none());
}

#[test]
fn test_http_template_with_inline_and_file() {
    let tmpl = HttpTemplate::new("VELOCITY", "inline body").template_file("/path/to/override.vm");
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(json["templateType"], "VELOCITY");
    assert_eq!(json["template"], "inline body");
    assert_eq!(json["templateFile"], "/path/to/override.vm");
}

#[test]
fn test_http_template_roundtrip() {
    let json_str = r#"{"templateType":"VELOCITY","template":"body","templateFile":"/a/b.vm"}"#;
    let tmpl: HttpTemplate = serde_json::from_str(json_str).unwrap();
    assert_eq!(tmpl.template_type, Some("VELOCITY".to_string()));
    assert_eq!(tmpl.template, Some("body".to_string()));
    assert_eq!(tmpl.template_file, Some("/a/b.vm".to_string()));
    // Re-serialize
    let reserialized = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(reserialized["templateFile"], "/a/b.vm");
}

#[test]
fn test_http_template_deserialization_without_template_file() {
    let json_str = r#"{"templateType":"MUSTACHE","template":"{{name}}"}"#;
    let tmpl: HttpTemplate = serde_json::from_str(json_str).unwrap();
    assert_eq!(tmpl.template_type, Some("MUSTACHE".to_string()));
    assert_eq!(tmpl.template, Some("{{name}}".to_string()));
    assert_eq!(tmpl.template_file, None);
}

// ---------------------------------------------------------------------------
// Body::File tests
// ---------------------------------------------------------------------------

#[test]
fn test_body_file_serialization_minimal() {
    let body = Body::file("/data/response.json");
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(json["type"], "FILE");
    assert_eq!(json["filePath"], "/data/response.json");
    assert!(json.get("contentType").is_none());
    assert!(json.get("templateType").is_none());
}

#[test]
fn test_body_file_serialization_with_content_type() {
    let body = Body::file("/data/response.xml").with_content_type("application/xml");
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(json["type"], "FILE");
    assert_eq!(json["filePath"], "/data/response.xml");
    assert_eq!(json["contentType"], "application/xml");
    assert!(json.get("templateType").is_none());
}

#[test]
fn test_body_file_serialization_with_template_type() {
    let body = Body::file("/data/response.vm")
        .with_content_type("application/json")
        .with_template_type("VELOCITY");
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(json["type"], "FILE");
    assert_eq!(json["filePath"], "/data/response.vm");
    assert_eq!(json["contentType"], "application/json");
    assert_eq!(json["templateType"], "VELOCITY");
}

#[test]
fn test_body_file_deserialization() {
    let json_str = r#"{"type":"FILE","filePath":"/data/resp.json","contentType":"application/json","templateType":"MUSTACHE"}"#;
    let req_json = format!(r#"{{"body":{}}}"#, json_str);
    let req: HttpRequest = serde_json::from_str(&req_json).unwrap();
    match req.body {
        Some(Body::File {
            file_path,
            content_type,
            template_type,
        }) => {
            assert_eq!(file_path, "/data/resp.json");
            assert_eq!(content_type, Some("application/json".to_string()));
            assert_eq!(template_type, Some("MUSTACHE".to_string()));
        }
        other => panic!("Expected Body::File, got {:?}", other),
    }
}

#[test]
fn test_body_file_deserialization_minimal() {
    let json_str = r#"{"body":{"type":"FILE","filePath":"/data/x.txt"}}"#;
    let req: HttpRequest = serde_json::from_str(json_str).unwrap();
    match req.body {
        Some(Body::File {
            file_path,
            content_type,
            template_type,
        }) => {
            assert_eq!(file_path, "/data/x.txt");
            assert_eq!(content_type, None);
            assert_eq!(template_type, None);
        }
        other => panic!("Expected Body::File, got {:?}", other),
    }
}

#[test]
fn test_body_file_roundtrip() {
    let body = Body::File {
        file_path: "/templates/resp.vm".to_string(),
        content_type: Some("text/html".to_string()),
        template_type: Some("VELOCITY".to_string()),
    };
    let json = serde_json::to_value(&body).unwrap();
    // Wrap in a request for deserialization
    let req_json = serde_json::json!({"body": json});
    let req: HttpRequest = serde_json::from_value(req_json).unwrap();
    assert_eq!(req.body, Some(body));
}

// ---------------------------------------------------------------------------
// Expectation with template actions
// ---------------------------------------------------------------------------

#[test]
fn test_expectation_with_response_template() {
    let expectation = Expectation::new(HttpRequest::new().path("/templated"))
        .respond_template(HttpTemplate::new("VELOCITY", "{ \"statusCode\": 200 }"));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpResponseTemplate"]["templateType"], "VELOCITY");
    assert_eq!(
        json["httpResponseTemplate"]["template"],
        "{ \"statusCode\": 200 }"
    );
    assert!(json.get("httpResponse").is_none());
    assert!(json.get("httpForwardTemplate").is_none());
}

#[test]
fn test_expectation_with_forward_template() {
    let expectation = Expectation::new(HttpRequest::new().path("/proxy")).forward_template(
        HttpTemplate::from_file("MUSTACHE", "/templates/forward.mustache"),
    );

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpForwardTemplate"]["templateType"], "MUSTACHE");
    assert_eq!(
        json["httpForwardTemplate"]["templateFile"],
        "/templates/forward.mustache"
    );
    assert!(json.get("httpResponseTemplate").is_none());
    assert!(json.get("httpForward").is_none());
}

#[test]
fn test_expectation_with_response_template_and_template_file() {
    let expectation = Expectation::new(HttpRequest::new().path("/t")).respond_template(
        HttpTemplate::new("VELOCITY", "fallback").template_file("/path/to/template.vm"),
    );

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpResponseTemplate"]["templateType"], "VELOCITY");
    assert_eq!(json["httpResponseTemplate"]["template"], "fallback");
    assert_eq!(
        json["httpResponseTemplate"]["templateFile"],
        "/path/to/template.vm"
    );
}

#[test]
fn test_expectation_template_roundtrip() {
    let json_str = r#"{
        "httpRequest": {"path": "/api"},
        "httpResponseTemplate": {
            "templateType": "VELOCITY",
            "template": "body here",
            "templateFile": "/file.vm"
        }
    }"#;
    let exp: Expectation = serde_json::from_str(json_str).unwrap();
    assert_eq!(exp.http_request.path, Some("/api".to_string()));
    let tmpl = exp.http_response_template.unwrap();
    assert_eq!(tmpl.template_type, Some("VELOCITY".to_string()));
    assert_eq!(tmpl.template, Some("body here".to_string()));
    assert_eq!(tmpl.template_file, Some("/file.vm".to_string()));
}

// ---------------------------------------------------------------------------
// HttpRequest file_body builder
// ---------------------------------------------------------------------------

#[test]
fn test_request_file_body_builder() {
    let req = HttpRequest::new().file_body("/data/request.json");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "FILE");
    assert_eq!(json["body"]["filePath"], "/data/request.json");
}

#[test]
fn test_request_body_value_builder() {
    let body = Body::file("/data/resp.vm")
        .with_content_type("text/html")
        .with_template_type("VELOCITY");
    let req = HttpRequest::new().body_value(body);
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "FILE");
    assert_eq!(json["body"]["filePath"], "/data/resp.vm");
    assert_eq!(json["body"]["contentType"], "text/html");
    assert_eq!(json["body"]["templateType"], "VELOCITY");
}

// ---------------------------------------------------------------------------
// RetrieveType / ClearType / RetrieveFormat string values
// ---------------------------------------------------------------------------

#[test]
fn test_retrieve_type_strings() {
    assert_eq!(RetrieveType::Requests.as_str(), "REQUESTS");
    assert_eq!(
        RetrieveType::ActiveExpectations.as_str(),
        "ACTIVE_EXPECTATIONS"
    );
    assert_eq!(
        RetrieveType::RecordedExpectations.as_str(),
        "RECORDED_EXPECTATIONS"
    );
    assert_eq!(RetrieveType::Logs.as_str(), "LOGS");
    assert_eq!(RetrieveType::RequestResponses.as_str(), "REQUEST_RESPONSES");
}

#[test]
fn test_retrieve_format_strings() {
    assert_eq!(RetrieveFormat::Json.as_str(), "JSON");
    assert_eq!(RetrieveFormat::LogEntries.as_str(), "LOG_ENTRIES");
}

#[test]
fn test_clear_type_strings() {
    assert_eq!(ClearType::All.as_str(), "ALL");
    assert_eq!(ClearType::Log.as_str(), "LOG");
    assert_eq!(ClearType::Expectations.as_str(), "EXPECTATIONS");
}

// ---------------------------------------------------------------------------
// HttpSseResponse (Server-Sent Events) tests
// ---------------------------------------------------------------------------

#[test]
fn test_sse_response_serialization() {
    let sse = HttpSseResponse::new()
        .status_code(200)
        .header("Content-Type", "text/event-stream")
        .event(
            SseEvent::new()
                .event("message")
                .data("hello")
                .id("1")
                .retry(3000),
        )
        .close_connection(true);
    let json = serde_json::to_value(&sse).unwrap();
    assert_eq!(json["statusCode"], 200);
    assert_eq!(
        json["headers"]["Content-Type"],
        serde_json::json!(["text/event-stream"])
    );
    let events = json["events"].as_array().unwrap();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0]["event"], "message");
    assert_eq!(events[0]["data"], "hello");
    assert_eq!(events[0]["id"], "1");
    assert_eq!(events[0]["retry"], 3000);
    assert_eq!(json["closeConnection"], true);
}

#[test]
fn test_sse_event_omits_unset_fields() {
    let event = SseEvent::new().data("only-data");
    let json = serde_json::to_value(&event).unwrap();
    assert_eq!(json["data"], "only-data");
    assert!(json.get("event").is_none());
    assert!(json.get("id").is_none());
    assert!(json.get("retry").is_none());
    assert!(json.get("delay").is_none());
}

#[test]
fn test_expectation_with_sse_response() {
    let expectation = Expectation::new(HttpRequest::new().method("GET").path("/events"))
        .respond_sse(HttpSseResponse::new().event(SseEvent::new().data("tick")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpSseResponse"]["events"][0]["data"], "tick");
    assert!(json.get("httpResponse").is_none());
}

// ---------------------------------------------------------------------------
// HttpWebSocketResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_websocket_response_text_message() {
    let ws = HttpWebSocketResponse::new()
        .subprotocol("chat")
        .message(WebSocketMessage::text("hello"))
        .close_connection(true);
    let json = serde_json::to_value(&ws).unwrap();
    assert_eq!(json["subprotocol"], "chat");
    let messages = json["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 1);
    assert_eq!(messages[0]["text"], "hello");
    assert!(messages[0].get("binary").is_none());
    assert_eq!(json["closeConnection"], true);
}

#[test]
fn test_websocket_binary_message_is_base64() {
    // bytes [0x01, 0x02, 0x03] -> base64 "AQID"
    let msg = WebSocketMessage::binary([0x01, 0x02, 0x03]);
    let json = serde_json::to_value(&msg).unwrap();
    assert_eq!(json["binary"], "AQID");
    assert!(json.get("text").is_none());
}

#[test]
fn test_websocket_binary_base64_passthrough() {
    let msg = WebSocketMessage::binary_base64("AQID");
    let json = serde_json::to_value(&msg).unwrap();
    assert_eq!(json["binary"], "AQID");
}

#[test]
fn test_expectation_with_websocket_response() {
    let expectation = Expectation::new(HttpRequest::new().path("/ws"))
        .respond_web_socket(HttpWebSocketResponse::new().message(WebSocketMessage::text("hi")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpWebSocketResponse"]["messages"][0]["text"], "hi");
}

// ---------------------------------------------------------------------------
// DnsResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_dns_response_serialization() {
    let dns = DnsResponse::new()
        .response_code("NOERROR")
        .answer_record(DnsRecord::a("example.com", "1.2.3.4").ttl(300));
    let json = serde_json::to_value(&dns).unwrap();
    assert_eq!(json["responseCode"], "NOERROR");
    let answers = json["answerRecords"].as_array().unwrap();
    assert_eq!(answers.len(), 1);
    assert_eq!(answers[0]["name"], "example.com");
    assert_eq!(answers[0]["type"], "A");
    assert_eq!(answers[0]["value"], "1.2.3.4");
    assert_eq!(answers[0]["ttl"], 300);
}

#[test]
fn test_dns_record_type_field_name() {
    // The Rust field is `record_type` but the wire key must be `type`.
    let record = DnsRecord::cname("www.example.com", "example.com");
    let json = serde_json::to_value(&record).unwrap();
    assert_eq!(json["type"], "CNAME");
    assert!(json.get("recordType").is_none());
}

#[test]
fn test_dns_srv_record_full_fields() {
    let record = DnsRecord::new()
        .name("_sip._tcp.example.com")
        .record_type("SRV")
        .priority(10)
        .weight(60)
        .port(5060)
        .value("sipserver.example.com")
        .dns_class("IN");
    let json = serde_json::to_value(&record).unwrap();
    assert_eq!(json["priority"], 10);
    assert_eq!(json["weight"], 60);
    assert_eq!(json["port"], 5060);
    assert_eq!(json["dnsClass"], "IN");
}

#[test]
fn test_expectation_with_dns_response() {
    let expectation = Expectation::new(HttpRequest::new())
        .respond_dns(DnsResponse::new().answer_record(DnsRecord::a("host", "10.0.0.1")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["dnsResponse"]["answerRecords"][0]["value"], "10.0.0.1");
}

// ---------------------------------------------------------------------------
// BinaryResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_binary_response_is_base64() {
    // bytes [0xDE, 0xAD, 0xBE, 0xEF] -> base64 "3q2+7w=="
    let resp = BinaryResponse::from_bytes([0xDE, 0xAD, 0xBE, 0xEF]);
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["binaryData"], "3q2+7w==");
}

#[test]
fn test_binary_response_from_base64_passthrough() {
    let resp = BinaryResponse::from_base64("3q2+7w==");
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["binaryData"], "3q2+7w==");
}

#[test]
fn test_expectation_with_binary_response() {
    let expectation = Expectation::new(HttpRequest::new().path("/raw"))
        .respond_binary(BinaryResponse::from_bytes([0x00, 0xFF]));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["binaryResponse"]["binaryData"], "AP8=");
}

// ---------------------------------------------------------------------------
// GrpcStreamResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_grpc_stream_response_serialization() {
    let grpc = GrpcStreamResponse::new()
        .status_name("OK")
        .status_message("done")
        .header("grpc-meta", "v")
        .message(GrpcStreamMessage::json("{\"id\":1}"))
        .message(GrpcStreamMessage::json("{\"id\":2}").delay(Delay::milliseconds(50)))
        .close_connection(true);
    let json = serde_json::to_value(&grpc).unwrap();
    assert_eq!(json["statusName"], "OK");
    assert_eq!(json["statusMessage"], "done");
    assert_eq!(json["headers"]["grpc-meta"], serde_json::json!(["v"]));
    let messages = json["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 2);
    assert_eq!(messages[0]["json"], "{\"id\":1}");
    assert_eq!(messages[1]["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(messages[1]["delay"]["value"], 50);
    assert_eq!(json["closeConnection"], true);
}

#[test]
fn test_expectation_with_grpc_stream_response() {
    let expectation = Expectation::new(HttpRequest::new().path("/grpc"))
        .respond_grpc_stream(GrpcStreamResponse::new().message(GrpcStreamMessage::json("{}")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["grpcStreamResponse"]["messages"][0]["json"], "{}");
}

// ---------------------------------------------------------------------------
// `respond_with_*` fluent-alias existence (compile-time guard)
//
// The Python/PHP/.NET clients expose the advanced response builders under
// `respond_with_*` names. This test references each Rust alias inside a
// never-called closure so it only compiles if every alias exists with the
// expected argument type — a regression guard requiring no running server.
// (The aliases consume the chain and call `upsert`, so they cannot be
// invoked without a live server; referencing them is enough to lock the
// public surface.)
// ---------------------------------------------------------------------------

#[test]
fn test_respond_with_aliases_exist_on_fluent_chain() {
    // These helpers are intentionally never invoked; they exist purely so the
    // compiler type-checks that each `respond_with_*` alias is present on
    // `ForwardChainExpectation` and accepts the matching response type. The
    // aliases consume the chain and call `upsert`, so they cannot run without
    // a live server — referencing them is enough to lock the public surface.
    fn _sse(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_sse(HttpSseResponse::new())
    }
    fn _ws(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_web_socket(HttpWebSocketResponse::new())
    }
    fn _dns(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_dns(DnsResponse::new())
    }
    fn _bin(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_binary(BinaryResponse::new())
    }
    fn _grpc(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_grpc_stream(GrpcStreamResponse::new())
    }
}

// ---------------------------------------------------------------------------
// OpenApiExpectation tests
// ---------------------------------------------------------------------------

#[test]
fn test_openapi_expectation_serialization() {
    let exp = OpenApiExpectation::new("https://example.com/petstore.yaml")
        .operation("listPets", "200")
        .operation("showPetById", "default")
        .context_path_prefix("/v1");
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(
        json["specUrlOrPayload"],
        "https://example.com/petstore.yaml"
    );
    assert_eq!(json["operationsAndResponses"]["listPets"], "200");
    assert_eq!(json["operationsAndResponses"]["showPetById"], "default");
    assert_eq!(json["contextPathPrefix"], "/v1");
}

#[test]
fn test_openapi_expectation_minimal() {
    // Only the spec is required; operations and context path are omitted.
    let exp = OpenApiExpectation::new("file:///specs/api.json");
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["specUrlOrPayload"], "file:///specs/api.json");
    assert!(json.get("operationsAndResponses").is_none());
    assert!(json.get("contextPathPrefix").is_none());
}

#[test]
fn test_openapi_expectation_inline_payload() {
    let payload = r#"{"openapi":"3.0.0","info":{"title":"x","version":"1"}}"#;
    let exp = OpenApiExpectation::new(payload);
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["specUrlOrPayload"], payload);
}

#[test]
fn test_retrieve_format_code_query_values() {
    assert_eq!(RetrieveFormat::Java.as_str(), "JAVA");
    assert_eq!(RetrieveFormat::JavaScript.as_str(), "JAVASCRIPT");
    assert_eq!(RetrieveFormat::Python.as_str(), "PYTHON");
    assert_eq!(RetrieveFormat::Go.as_str(), "GO");
    assert_eq!(RetrieveFormat::CSharp.as_str(), "CSHARP");
    assert_eq!(RetrieveFormat::Ruby.as_str(), "RUBY");
    assert_eq!(RetrieveFormat::Rust.as_str(), "RUST");
    assert_eq!(RetrieveFormat::Php.as_str(), "PHP");
}
