//! SRE control-plane tests.
//!
//! Two kinds of tests, no running MockServer required:
//!  * serde round-trip / shape tests proving the JSON matches
//!    `jekyll-www.mock-server.com/mockserver-openapi.yaml`, and
//!  * stub-server tests using `tiny_http` (already a dev-dependency) that assert
//!    the exact HTTP method, path and request body each client method sends, and
//!    that the documented status codes map to the right `Result`.

use std::sync::mpsc;
use std::thread;

use mockserver_client::*;

// ---------------------------------------------------------------------------
// Stub HTTP server harness
// ---------------------------------------------------------------------------

/// What the stub captured about the single request it served.
struct Captured {
    method: String,
    url: String,
    body: String,
}

/// Start a one-shot stub server that replies with `status`/`resp_body` to the
/// first request, capturing its method, url and body. Returns a client pointed
/// at the stub and a receiver that yields the captured request.
fn stub(status: u16, resp_body: &'static str) -> (MockServerClient, mpsc::Receiver<Captured>) {
    let server = tiny_http::Server::http("127.0.0.1:0").expect("bind stub server");
    let port = match server.server_addr() {
        tiny_http::ListenAddr::IP(addr) => addr.port(),
        #[allow(unreachable_patterns)]
        _ => panic!("expected IP listen address"),
    };
    let (tx, rx) = mpsc::channel();

    thread::spawn(move || {
        let mut request = server.recv().expect("stub recv");
        let method = request.method().as_str().to_string();
        let url = request.url().to_string();
        let mut body = String::new();
        request.as_reader().read_to_string(&mut body).ok();
        tx.send(Captured { method, url, body }).ok();

        let response =
            tiny_http::Response::from_string(resp_body).with_status_code(tiny_http::StatusCode(status));
        request.respond(response).ok();
    });

    let client = ClientBuilder::new("127.0.0.1", port)
        .build()
        .expect("build client");
    (client, rx)
}

// ---------------------------------------------------------------------------
// Load scenario — serde shape (matches the OpenAPI LoadScenario example)
// ---------------------------------------------------------------------------

#[test]
fn test_load_scenario_serializes_to_spec_shape() {
    let scenario = LoadScenario::new(
        "checkout-load",
        LoadProfile::of(vec![
            LoadStage::vu_ramp(1, 10, 10_000, RampCurve::Linear),
            LoadStage::vu_hold(10, 30_000),
            LoadStage::pause(5_000),
        ]),
        vec![LoadStep::new(
            HttpRequest::new()
                .method("GET")
                .path("/api/item/$iteration.index")
                .header("Host", "target.svc:8080")
                .socket_address(SocketAddress::new("target.svc", 8080).scheme("HTTP")),
        )
        .think_time(Delay::milliseconds(20))],
    )
    .template_type("VELOCITY")
    .max_requests(5000);

    let json = serde_json::to_value(&scenario).unwrap();

    assert_eq!(json["name"], "checkout-load");
    assert_eq!(json["templateType"], "VELOCITY");
    assert_eq!(json["maxRequests"], 5000);
    let stages = &json["profile"]["stages"];
    assert_eq!(stages[0]["type"], "VU");
    assert_eq!(stages[0]["startVus"], 1);
    assert_eq!(stages[0]["endVus"], 10);
    assert_eq!(stages[0]["durationMillis"], 10000);
    assert_eq!(stages[0]["curve"], "LINEAR");
    assert_eq!(stages[1]["type"], "VU");
    assert_eq!(stages[1]["vus"], 10);
    assert_eq!(stages[1]["durationMillis"], 30000);
    assert!(stages[1].get("curve").is_none());
    assert!(stages[1].get("startVus").is_none());
    assert_eq!(stages[2]["type"], "PAUSE");
    assert_eq!(stages[2]["durationMillis"], 5000);
    assert!(stages[2].get("vus").is_none());

    let step = &json["steps"][0];
    assert_eq!(step["request"]["method"], "GET");
    assert_eq!(step["request"]["path"], "/api/item/$iteration.index");
    assert_eq!(step["request"]["socketAddress"]["host"], "target.svc");
    assert_eq!(step["request"]["socketAddress"]["port"], 8080);
    assert_eq!(step["request"]["socketAddress"]["scheme"], "HTTP");
    assert_eq!(step["thinkTime"]["timeUnit"], "MILLISECONDS");
    assert_eq!(step["thinkTime"]["value"], 20);
}

#[test]
fn test_load_profile_linear_shape() {
    let profile = LoadProfile::linear(1, 50, 60_000);
    let json = serde_json::to_value(&profile).unwrap();
    let stage = &json["stages"][0];
    assert_eq!(stage["type"], "VU");
    assert_eq!(stage["curve"], "LINEAR");
    assert_eq!(stage["startVus"], 1);
    assert_eq!(stage["endVus"], 50);
    assert_eq!(stage["durationMillis"], 60000);
    // Irrelevant fields absent.
    assert!(stage.get("vus").is_none());
    assert!(stage.get("rate").is_none());
    assert!(stage.get("maxVus").is_none());
}

#[test]
fn test_load_profile_rate_stage_shape() {
    let profile = LoadProfile::of(vec![
        LoadStage::rate_hold(100.0, 30_000).max_vus(20),
        LoadStage::rate_ramp(10.0, 200.0, 60_000, RampCurve::Exponential),
    ]);
    let json = serde_json::to_value(&profile).unwrap();

    let hold = &json["stages"][0];
    assert_eq!(hold["type"], "RATE");
    assert_eq!(hold["rate"], 100.0);
    assert_eq!(hold["durationMillis"], 30000);
    assert_eq!(hold["maxVus"], 20);
    assert!(hold.get("curve").is_none());
    assert!(hold.get("vus").is_none());

    let ramp = &json["stages"][1];
    assert_eq!(ramp["type"], "RATE");
    assert_eq!(ramp["curve"], "EXPONENTIAL");
    assert_eq!(ramp["startRate"], 10.0);
    assert_eq!(ramp["endRate"], 200.0);
    assert!(ramp.get("rate").is_none());
    assert!(ramp.get("maxVus").is_none());
}

#[test]
fn test_load_scenario_serializes_start_delay_millis() {
    let scenario = LoadScenario::new(
        "delayed",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    )
    .start_delay_millis(2500);
    let json = serde_json::to_value(&scenario).unwrap();
    assert_eq!(json["startDelayMillis"], 2500);
}

#[test]
fn test_load_scenario_omits_start_delay_millis_when_unset() {
    let scenario = LoadScenario::new(
        "s",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    );
    let json = serde_json::to_value(&scenario).unwrap();
    assert!(json.get("startDelayMillis").is_none());
}

// ---------------------------------------------------------------------------
// Load scenario registry — HTTP plumbing
// ---------------------------------------------------------------------------

#[test]
fn test_load_scenario_registers_with_put() {
    let (client, rx) = stub(200, r#"{"name":"s","state":"LOADED"}"#);
    let scenario = LoadScenario::new(
        "s",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    );

    let result = client.load_scenario(&scenario).unwrap();
    assert_eq!(result["name"], "s");
    assert_eq!(result["state"], "LOADED");

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["name"], "s");
    assert_eq!(sent["profile"]["stages"][0]["type"], "VU");
    assert_eq!(sent["profile"]["stages"][0]["vus"], 1);
}

#[test]
fn test_load_scenarios_lists_with_get() {
    let (client, rx) = stub(200, r#"{"scenarios":[{"name":"s","state":"LOADED"}]}"#);
    let result = client.load_scenarios().unwrap();
    assert_eq!(result["scenarios"][0]["name"], "s");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/loadScenario");
}

#[test]
fn test_get_load_scenario_sends_get_to_named_path() {
    let (client, rx) = stub(200, r#"{"name":"checkout","state":"RUNNING"}"#);
    let result = client.get_load_scenario("checkout").unwrap();
    assert_eq!(result["state"], "RUNNING");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/loadScenario/checkout");
}

#[test]
fn test_get_load_scenario_404_is_not_found() {
    let (client, _rx) = stub(404, r#"{"error":"no scenario named bogus"}"#);
    match client.get_load_scenario("bogus") {
        Err(Error::NotFound(msg)) => assert!(msg.contains("bogus")),
        other => panic!("expected NotFound, got {other:?}"),
    }
}

#[test]
fn test_delete_load_scenario_sends_delete_to_named_path() {
    let (client, rx) = stub(200, r#"{"name":"checkout","state":"STOPPED"}"#);
    let result = client.delete_load_scenario("checkout").unwrap();
    assert_eq!(result["name"], "checkout");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "DELETE");
    assert_eq!(cap.url, "/mockserver/loadScenario/checkout");
}

#[test]
fn test_clear_load_scenarios_sends_delete_to_collection() {
    let (client, rx) = stub(200, "");
    let result = client.clear_load_scenarios().unwrap();
    assert!(result.is_null());
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "DELETE");
    assert_eq!(cap.url, "/mockserver/loadScenario");
}

#[test]
fn test_start_load_scenarios_sends_put_with_names() {
    let (client, rx) = stub(
        200,
        r#"{"started":[{"name":"a","state":"PENDING"}],"status":"started"}"#,
    );
    let result = client.start_load_scenarios(&["a", "b"]).unwrap();
    assert_eq!(result["status"], "started");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario/start");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["names"][0], "a");
    assert_eq!(sent["names"][1], "b");
}

#[test]
fn test_start_load_scenarios_403_is_feature_disabled() {
    let (client, _rx) = stub(403, r#"{"error":"load generation not enabled"}"#);
    match client.start_load_scenarios(&["a"]) {
        Err(Error::FeatureDisabled(msg)) => assert!(msg.contains("not enabled")),
        other => panic!("expected FeatureDisabled, got {other:?}"),
    }
}

#[test]
fn test_start_load_scenarios_404_is_not_found() {
    let (client, _rx) = stub(404, r#"{"error":"unknown scenario nope"}"#);
    match client.start_load_scenarios(&["nope"]) {
        Err(Error::NotFound(msg)) => assert!(msg.contains("nope")),
        other => panic!("expected NotFound, got {other:?}"),
    }
}

#[test]
fn test_stop_load_scenarios_with_names_sends_names_body() {
    let (client, rx) = stub(200, r#"{"stopped":["a"],"status":"stopped"}"#);
    let result = client.stop_load_scenarios(&["a"]).unwrap();
    assert_eq!(result["status"], "stopped");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario/stop");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["names"][0], "a");
}

#[test]
fn test_stop_load_scenarios_empty_sends_empty_body() {
    let (client, rx) = stub(200, r#"{"stopped":["a","b"],"status":"stopped"}"#);
    let empty: &[&str] = &[];
    let result = client.stop_load_scenarios(empty).unwrap();
    assert_eq!(result["status"], "stopped");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario/stop");
    assert_eq!(cap.body, "");
}

#[test]
fn test_run_load_scenario_registers_then_starts() {
    // Two requests: PUT register, then PUT start. Use a two-shot stub.
    let server = tiny_http::Server::http("127.0.0.1:0").expect("bind stub server");
    let port = match server.server_addr() {
        tiny_http::ListenAddr::IP(addr) => addr.port(),
        #[allow(unreachable_patterns)]
        _ => panic!("expected IP listen address"),
    };
    let (tx, rx) = mpsc::channel();
    thread::spawn(move || {
        for (i, body) in [
            r#"{"name":"run-me","state":"LOADED"}"#,
            r#"{"started":[{"name":"run-me","state":"PENDING"}],"status":"started"}"#,
        ]
        .iter()
        .enumerate()
        {
            let mut request = server.recv().expect("stub recv");
            let method = request.method().as_str().to_string();
            let url = request.url().to_string();
            let mut req_body = String::new();
            request.as_reader().read_to_string(&mut req_body).ok();
            tx.send((i, method, url, req_body)).ok();
            request
                .respond(tiny_http::Response::from_string(*body))
                .ok();
        }
    });
    let client = ClientBuilder::new("127.0.0.1", port)
        .build()
        .expect("build client");

    let scenario = LoadScenario::new(
        "run-me",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    );
    let result = client.run_load_scenario(&scenario).unwrap();
    assert_eq!(result["status"], "started");

    let (_, m0, u0, _) = rx.recv().unwrap();
    assert_eq!(m0, "PUT");
    assert_eq!(u0, "/mockserver/loadScenario");
    let (_, m1, u1, b1) = rx.recv().unwrap();
    assert_eq!(m1, "PUT");
    assert_eq!(u1, "/mockserver/loadScenario/start");
    let sent: serde_json::Value = serde_json::from_str(&b1).unwrap();
    assert_eq!(sent["names"][0], "run-me");
}

// ---------------------------------------------------------------------------
// Load scenario — new parity fields (thresholds, abort, pacing, feeder,
// stepSelection, shape, captures, weight)
// ---------------------------------------------------------------------------

#[test]
fn test_load_scenario_serializes_thresholds_and_abort() {
    let scenario = LoadScenario::new(
        "thresholded",
        LoadProfile::constant(5, 10_000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    )
    .threshold(LoadThreshold::new(
        LoadThresholdMetric::LatencyP99,
        LoadComparator::LessThan,
        500.0,
    ))
    .threshold(LoadThreshold::new(
        LoadThresholdMetric::ErrorRate,
        LoadComparator::LessThanOrEqual,
        0.01,
    ))
    .abort_on_fail(true)
    .abort_grace_millis(3000);

    let json = serde_json::to_value(&scenario).unwrap();
    assert_eq!(json["thresholds"][0]["metric"], "LATENCY_P99");
    assert_eq!(json["thresholds"][0]["comparator"], "LESS_THAN");
    assert_eq!(json["thresholds"][0]["threshold"], 500.0);
    assert_eq!(json["thresholds"][1]["metric"], "ERROR_RATE");
    assert_eq!(json["thresholds"][1]["comparator"], "LESS_THAN_OR_EQUAL");
    assert_eq!(json["thresholds"][1]["threshold"], 0.01);
    assert_eq!(json["abortOnFail"], true);
    assert_eq!(json["abortGraceMillis"], 3000);
}

#[test]
fn test_load_scenario_omits_new_fields_when_unset() {
    // Existing scenarios serialize unchanged: none of the new keys appear.
    let scenario = LoadScenario::new(
        "plain",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    );
    let json = serde_json::to_value(&scenario).unwrap();
    assert!(json.get("thresholds").is_none());
    assert!(json.get("abortOnFail").is_none());
    assert!(json.get("abortGraceMillis").is_none());
    assert!(json.get("pacing").is_none());
    assert!(json.get("feeder").is_none());
    assert!(json.get("stepSelection").is_none());
    // Step new fields absent too.
    let step = &json["steps"][0];
    assert!(step.get("captures").is_none());
    assert!(step.get("weight").is_none());
}

#[test]
fn test_load_scenario_serializes_pacing_and_step_selection() {
    let scenario = LoadScenario::new(
        "paced",
        LoadProfile::constant(5, 10_000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    )
    .pacing(LoadPacing::constant_throughput(2.0))
    .step_selection(LoadStepSelection::Weighted);

    let json = serde_json::to_value(&scenario).unwrap();
    assert_eq!(json["pacing"]["mode"], "CONSTANT_THROUGHPUT");
    assert_eq!(json["pacing"]["value"], 2.0);
    assert_eq!(json["stepSelection"], "WEIGHTED");
}

#[test]
fn test_load_scenario_serializes_feeder_rows() {
    let mut row = std::collections::HashMap::new();
    row.insert("user".to_string(), "alice".to_string());
    let scenario = LoadScenario::new(
        "fed",
        LoadProfile::constant(1, 1000),
        vec![LoadStep::new(HttpRequest::new().method("GET").path("/x"))],
    )
    .feeder(LoadFeeder::rows(vec![row]).strategy(LoadFeederStrategy::Sequential));

    let json = serde_json::to_value(&scenario).unwrap();
    assert_eq!(json["feeder"]["rows"][0]["user"], "alice");
    assert_eq!(json["feeder"]["strategy"], "SEQUENTIAL");
    assert!(json["feeder"].get("data").is_none());
    assert!(json["feeder"].get("format").is_none());
}

#[test]
fn test_load_scenario_serializes_feeder_raw_data() {
    let feeder = LoadFeeder::data("user\nalice\nbob", LoadFeederFormat::Csv);
    let json = serde_json::to_value(&feeder).unwrap();
    assert_eq!(json["data"], "user\nalice\nbob");
    assert_eq!(json["format"], "CSV");
    assert!(json.get("rows").is_none());
}

#[test]
fn test_load_step_serializes_captures_and_weight() {
    let step = LoadStep::new(HttpRequest::new().method("POST").path("/login"))
        .capture(
            LoadCapture::new("token", LoadCaptureSource::BodyJsonpath, "$.token")
                .default_value("anon"),
        )
        .weight(7.0);
    let json = serde_json::to_value(&step).unwrap();
    assert_eq!(json["captures"][0]["name"], "token");
    assert_eq!(json["captures"][0]["source"], "BODY_JSONPATH");
    assert_eq!(json["captures"][0]["expression"], "$.token");
    assert_eq!(json["captures"][0]["defaultValue"], "anon");
    assert_eq!(json["weight"], 7.0);
}

#[test]
fn test_load_profile_shape_serializes() {
    let profile = LoadProfile::shaped(
        LoadShape::spike(1.0, 100.0, 5_000, 30_000, 5_000)
            .metric(LoadShapeMetric::Vu)
            .curve(RampCurve::Linear)
            .recovery_hold_millis(10_000),
    );
    let json = serde_json::to_value(&profile).unwrap();
    let shape = &json["shape"];
    assert_eq!(shape["type"], "SPIKE");
    assert_eq!(shape["metric"], "VU");
    assert_eq!(shape["curve"], "LINEAR");
    assert_eq!(shape["baseline"], 1.0);
    assert_eq!(shape["peak"], 100.0);
    assert_eq!(shape["rampUpMillis"], 5000);
    assert_eq!(shape["holdMillis"], 30000);
    assert_eq!(shape["rampDownMillis"], 5000);
    assert_eq!(shape["recoveryHoldMillis"], 10000);
    // Shape-only profile omits an empty stages array.
    assert!(json.get("stages").is_none());
    // STAIRS / RAMP_HOLD irrelevant fields absent.
    assert!(shape.get("start").is_none());
    assert!(shape.get("target").is_none());
}

#[test]
fn test_load_shape_ramp_hold_and_stairs() {
    let ramp_hold = serde_json::to_value(LoadShape::ramp_hold(50.0, 10_000, 60_000)).unwrap();
    assert_eq!(ramp_hold["type"], "RAMP_HOLD");
    assert_eq!(ramp_hold["target"], 50.0);
    assert_eq!(ramp_hold["rampMillis"], 10000);
    assert_eq!(ramp_hold["holdMillis"], 60000);
    assert!(ramp_hold.get("baseline").is_none());

    let stairs = serde_json::to_value(LoadShape::stairs(10.0, 10.0, 5, 15_000)).unwrap();
    assert_eq!(stairs["type"], "STAIRS");
    assert_eq!(stairs["start"], 10.0);
    assert_eq!(stairs["step"], 10.0);
    assert_eq!(stairs["steps"], 5);
    assert_eq!(stairs["stepDurationMillis"], 15000);
}

// ---------------------------------------------------------------------------
// Load scenario report + generate endpoints — HTTP plumbing
// ---------------------------------------------------------------------------

#[test]
fn test_get_load_scenario_report_json_sends_get() {
    let (client, rx) = stub(200, r#"{"scenario":"s","verdict":"PASS"}"#);
    let result = client.get_load_scenario_report("s", None).unwrap();
    assert!(result.contains("\"verdict\":\"PASS\""));
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/loadScenario/s/report");
}

#[test]
fn test_get_load_scenario_report_junit_appends_format() {
    let (client, rx) = stub(
        200,
        r#"<testsuite name="s"><testcase name="run"/></testsuite>"#,
    );
    let result = client.get_load_scenario_report("s", Some("junit")).unwrap();
    assert!(result.contains("<testsuite"));
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/loadScenario/s/report?format=junit");
}

#[test]
fn test_get_load_scenario_report_404_is_not_found() {
    let (client, _rx) = stub(404, r#"{"error":"no run for s"}"#);
    match client.get_load_scenario_report("s", None) {
        Err(Error::NotFound(msg)) => assert!(msg.contains("no run")),
        other => panic!("expected NotFound, got {other:?}"),
    }
}

#[test]
fn test_generate_load_scenario_from_openapi_sends_put() {
    let (client, rx) = stub(
        200,
        r#"{"status":"loaded","name":"petstore-load","state":"LOADED"}"#,
    );
    let body = serde_json::json!({
        "name": "petstore-load",
        "specUrlOrPayload": "https://example.com/petstore.yaml",
    });
    let result = client.generate_load_scenario_from_openapi(&body).unwrap();
    assert_eq!(result["status"], "loaded");
    assert_eq!(result["name"], "petstore-load");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario/generateFromOpenAPI");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["name"], "petstore-load");
    assert_eq!(
        sent["specUrlOrPayload"],
        "https://example.com/petstore.yaml"
    );
}

#[test]
fn test_generate_load_scenario_from_recording_sends_put() {
    let (client, rx) = stub(
        200,
        r#"{"status":"loaded","name":"replay","state":"LOADED"}"#,
    );
    let body = serde_json::json!({ "name": "replay" });
    let result = client.generate_load_scenario_from_recording(&body).unwrap();
    assert_eq!(result["status"], "loaded");
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/loadScenario/generateFromRecording");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["name"], "replay");
}

#[test]
fn test_generate_load_scenario_from_openapi_403_is_feature_disabled() {
    let (client, _rx) = stub(403, r#"{"error":"disabled"}"#);
    let body = serde_json::json!({ "name": "x", "specUrlOrPayload": "y" });
    match client.generate_load_scenario_from_openapi(&body) {
        Err(Error::FeatureDisabled(_)) => {}
        other => panic!("expected FeatureDisabled, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// Service chaos
// ---------------------------------------------------------------------------

#[test]
fn test_set_service_chaos_sends_put_with_host_and_chaos() {
    let (client, rx) = stub(200, "{}");
    let profile = HttpChaosProfile::new()
        .error_status(503)
        .error_probability(0.3)
        .latency(Delay::milliseconds(200));

    client
        .set_service_chaos("payments.internal:8443", &profile, Some(60_000))
        .unwrap();

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/serviceChaos");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["host"], "payments.internal:8443");
    assert_eq!(sent["chaos"]["errorStatus"], 503);
    assert_eq!(sent["chaos"]["errorProbability"], 0.3);
    assert_eq!(sent["chaos"]["latency"]["timeUnit"], "MILLISECONDS");
    assert_eq!(sent["chaos"]["latency"]["value"], 200);
    assert_eq!(sent["ttlMillis"], 60000);
}

#[test]
fn test_set_service_chaos_without_ttl_omits_field() {
    let (client, rx) = stub(200, "{}");
    let profile = HttpChaosProfile::new().error_status(500);
    client.set_service_chaos("h", &profile, None).unwrap();
    let cap = rx.recv().unwrap();
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert!(sent.get("ttlMillis").is_none());
}

#[test]
fn test_remove_and_clear_service_chaos() {
    let (client, rx) = stub(200, "{}");
    client.remove_service_chaos("h").unwrap();
    let cap = rx.recv().unwrap();
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["host"], "h");
    assert_eq!(sent["remove"], true);

    let (client, rx) = stub(200, "{}");
    client.clear_service_chaos().unwrap();
    let cap = rx.recv().unwrap();
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["clear"], true);
}

// ---------------------------------------------------------------------------
// SLO verdict
// ---------------------------------------------------------------------------

#[test]
fn test_slo_criteria_serializes_to_spec_shape() {
    let criteria = SloCriteria::new(vec![
        SloObjective::new("LATENCY_P95", "LESS_THAN", 250.0).scope("FORWARD"),
        SloObjective::new("ERROR_RATE", "LESS_THAN_OR_EQUAL", 0.01),
    ])
    .name("checkout-slo")
    .window(SloWindow::lookback(60_000))
    .minimum_sample_count(20)
    .upstream_hosts(vec!["payments.svc".to_string()]);

    let json = serde_json::to_value(&criteria).unwrap();
    assert_eq!(json["name"], "checkout-slo");
    assert_eq!(json["window"]["type"], "LOOKBACK");
    assert_eq!(json["window"]["lookbackMillis"], 60000);
    assert_eq!(json["minimumSampleCount"], 20);
    assert_eq!(json["upstreamHosts"][0], "payments.svc");
    assert_eq!(json["objectives"][0]["sli"], "LATENCY_P95");
    assert_eq!(json["objectives"][0]["comparator"], "LESS_THAN");
    assert_eq!(json["objectives"][0]["threshold"], 250.0);
    assert_eq!(json["objectives"][0]["scope"], "FORWARD");
    assert_eq!(json["objectives"][1]["sli"], "ERROR_RATE");
    assert!(json["objectives"][1].get("scope").is_none());
}

#[test]
fn test_verify_slo_200_is_pass_verdict() {
    let (client, rx) = stub(
        200,
        r#"{"name":"checkout-slo","result":"PASS","sampleCount":42,
            "objectiveResults":[{"sli":"LATENCY_P95","result":"PASS","observedValue":120.0}]}"#,
    );
    let criteria = SloCriteria::new(vec![SloObjective::new("LATENCY_P95", "LESS_THAN", 250.0)]);
    let verdict = client.verify_slo(&criteria).unwrap();
    assert!(verdict.is_pass());
    assert_eq!(verdict.name.as_deref(), Some("checkout-slo"));
    assert_eq!(verdict.sample_count, Some(42));
    assert_eq!(verdict.objective_results.len(), 1);

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/verifySLO");
}

#[test]
fn test_verify_slo_406_is_fail_verdict_not_error() {
    let (client, _rx) = stub(406, r#"{"name":"s","result":"FAIL","sampleCount":10}"#);
    let criteria = SloCriteria::new(vec![SloObjective::new("ERROR_RATE", "LESS_THAN", 0.01)]);
    // 406 must deserialize to a verdict (so a CI gate can branch), not Err.
    let verdict = client.verify_slo(&criteria).unwrap();
    assert!(verdict.is_fail());
}

#[test]
fn test_verify_slo_403_is_feature_disabled() {
    // 403 is "SLO tracking is off", matching PUT /mockserver/loadScenario/start. It used to share
    // 400 with malformed criteria, so a typo in the criteria was reported as a disabled feature.
    let (client, _rx) = stub(403, r#"{"error":"SLO tracking not enabled"}"#);
    let criteria = SloCriteria::new(vec![SloObjective::new("ERROR_RATE", "LESS_THAN", 0.01)]);
    match client.verify_slo(&criteria) {
        Err(Error::FeatureDisabled(msg)) => assert!(msg.contains("not enabled")),
        other => panic!("expected FeatureDisabled, got {other:?}"),
    }
}

#[test]
fn test_verify_slo_400_is_invalid_request() {
    let (client, _rx) = stub(400, r#"{"error":"objectives must be an array"}"#);
    let criteria = SloCriteria::new(vec![SloObjective::new("ERROR_RATE", "LESS_THAN", 0.01)]);
    match client.verify_slo(&criteria) {
        Err(Error::InvalidRequest(msg)) => assert!(msg.contains("objectives must be an array")),
        other => panic!("expected InvalidRequest, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// Preemption
// ---------------------------------------------------------------------------

#[test]
fn test_preemption_request_serializes_to_spec_shape() {
    let req = PreemptionRequest::new()
        .mode("both")
        .drain_millis(10_000)
        .ttl_millis(60_000)
        .last_stream_id(3);
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["mode"], "both");
    assert_eq!(json["drainMillis"], 10000);
    assert_eq!(json["ttlMillis"], 60000);
    assert_eq!(json["lastStreamId"], 3);
}

#[test]
fn test_empty_preemption_request_serializes_to_empty_object() {
    let json = serde_json::to_value(PreemptionRequest::new()).unwrap();
    assert_eq!(json, serde_json::json!({}));
}

#[test]
fn test_set_preemption_returns_status() {
    let (client, rx) = stub(200, r#"{"state":"draining","inFlight":2,"mode":"both"}"#);
    let status = client.set_preemption(&PreemptionRequest::new().mode("both")).unwrap();
    assert_eq!(status.state.as_deref(), Some("draining"));
    assert_eq!(status.in_flight, Some(2));
    assert_eq!(status.mode.as_deref(), Some("both"));

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/preemption");
}

#[test]
fn test_preemption_status_sends_get() {
    let (client, rx) = stub(200, r#"{"state":"inactive","inFlight":0}"#);
    let status = client.preemption_status().unwrap();
    assert_eq!(status.state.as_deref(), Some("inactive"));
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "GET");
    assert_eq!(cap.url, "/mockserver/preemption");
}

#[test]
fn test_clear_preemption_sends_delete() {
    let (client, rx) = stub(200, r#"{"state":"inactive"}"#);
    client.clear_preemption().unwrap();
    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "DELETE");
    assert_eq!(cap.url, "/mockserver/preemption");
}

// ---------------------------------------------------------------------------
// Chaos experiment
// ---------------------------------------------------------------------------

#[test]
fn test_chaos_experiment_serializes_to_spec_shape() {
    let experiment = ChaosExperiment::new(vec![
        ChaosStage::new(10_000).profile(
            "api.example.com",
            HttpChaosProfile::new().error_status(500).error_probability(0.1),
        ),
        ChaosStage::new(10_000).profile(
            "api.example.com",
            HttpChaosProfile::new().error_status(500).error_probability(0.5),
        ),
    ])
    .name("gradual-degradation")
    .loop_back(true);

    let json = serde_json::to_value(&experiment).unwrap();
    assert_eq!(json["name"], "gradual-degradation");
    // `loop` is a reserved-ish field name on the wire (not loopBack).
    assert_eq!(json["loop"], true);
    assert!(json.get("loopBack").is_none());
    assert_eq!(json["stages"][0]["durationMillis"], 10000);
    assert_eq!(
        json["stages"][0]["profiles"]["api.example.com"]["errorStatus"],
        500
    );
    assert_eq!(
        json["stages"][1]["profiles"]["api.example.com"]["errorProbability"],
        0.5
    );
}

#[test]
fn test_start_chaos_experiment_sends_put() {
    let (client, rx) = stub(200, r#"{"status":"started","name":"e"}"#);
    let experiment = ChaosExperiment::new(vec![ChaosStage::new(1000)
        .profile("h", HttpChaosProfile::new().error_status(500))])
    .name("e");
    let result = client.start_chaos_experiment(&experiment).unwrap();
    assert_eq!(result["status"], "started");

    let cap = rx.recv().unwrap();
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/chaosExperiment");
    let sent: serde_json::Value = serde_json::from_str(&cap.body).unwrap();
    assert_eq!(sent["stages"][0]["durationMillis"], 1000);
}

#[test]
fn test_start_chaos_experiment_400_is_invalid_request() {
    let (client, _rx) = stub(400, r#"{"error":"empty stages"}"#);
    let experiment = ChaosExperiment::new(vec![]);
    match client.start_chaos_experiment(&experiment) {
        Err(Error::InvalidRequest(msg)) => assert!(msg.contains("stages")),
        other => panic!("expected InvalidRequest, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// SloVerdict deserialization from the documented wire shape
// ---------------------------------------------------------------------------

#[test]
fn test_slo_verdict_deserializes_full_wire_shape() {
    let wire = r#"{
        "name":"checkout-slo",
        "result":"FAIL",
        "windowFromEpochMillis":1000,
        "windowToEpochMillis":2000,
        "sampleCount":30,
        "objectiveResults":[
            {"sli":"LATENCY_P95","comparator":"LESS_THAN","threshold":250.0,
             "observedValue":310.5,"result":"FAIL","detail":"observed 310.5 > 250.0"}
        ]
    }"#;
    let verdict: SloVerdict = serde_json::from_str(wire).unwrap();
    assert!(verdict.is_fail());
    assert_eq!(verdict.window_from_epoch_millis, Some(1000));
    assert_eq!(verdict.window_to_epoch_millis, Some(2000));
    assert_eq!(verdict.sample_count, Some(30));
    let r = &verdict.objective_results[0];
    assert_eq!(r.sli.as_deref(), Some("LATENCY_P95"));
    assert_eq!(r.observed_value, Some(310.5));
    assert_eq!(r.result.as_deref(), Some("FAIL"));
}
