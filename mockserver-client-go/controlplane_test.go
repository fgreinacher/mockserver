package mockserver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

// mustQuery parses a raw query string, failing the test on error.
func mustQuery(t *testing.T, raw string) url.Values {
	t.Helper()
	v, err := url.ParseQuery(raw)
	if err != nil {
		t.Fatalf("parse query %q: %v", raw, err)
	}
	return v
}

// captured records what a stub control-plane server received for one request.
type captured struct {
	method      string
	path        string
	rawQuery    string
	contentType string
	body        []byte
}

// cpStub spins up a stub server that records the request and replies with the
// given status and body. The recorded request is written to *cap.
func cpStub(t *testing.T, status int, respBody string, cap *captured) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cap.method = r.Method
		cap.path = r.URL.Path
		cap.rawQuery = r.URL.RawQuery
		cap.contentType = r.Header.Get("Content-Type")
		cap.body, _ = io.ReadAll(r.Body)
		w.WriteHeader(status)
		if respBody != "" {
			_, _ = w.Write([]byte(respBody))
		}
	}))
}

// ---------------------------------------------------------------------------
// 1. Clock
// ---------------------------------------------------------------------------

func TestClient_FreezeClock_WithInstant(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"status":"freeze"}`, &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).FreezeClock("2024-01-01T00:00:00Z"); err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/clock" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(cap.body, &m); err != nil {
		t.Fatal(err)
	}
	if m["action"] != "freeze" || m["instant"] != "2024-01-01T00:00:00Z" {
		t.Errorf("unexpected body %v", m)
	}
}

func TestClient_FreezeClock_NoInstant(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{}`, &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).FreezeClock(""); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	_ = json.Unmarshal(cap.body, &m)
	if m["action"] != "freeze" {
		t.Errorf("expected action freeze, got %v", m)
	}
	if _, ok := m["instant"]; ok {
		t.Errorf("did not expect instant field, got %v", m)
	}
}

func TestClient_AdvanceClock(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{}`, &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).AdvanceClock(5000); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	_ = json.Unmarshal(cap.body, &m)
	if m["action"] != "advance" {
		t.Errorf("expected action advance, got %v", m)
	}
	if v, _ := m["durationMillis"].(float64); v != 5000 {
		t.Errorf("expected durationMillis 5000, got %v", m["durationMillis"])
	}
}

func TestClient_ResetClock(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{}`, &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).ResetClock(); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	_ = json.Unmarshal(cap.body, &m)
	if m["action"] != "reset" {
		t.Errorf("expected action reset, got %v", m)
	}
}

func TestClient_ClockStatus(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"frozen":true,"currentEpochMillis":1700000000000}`, &cap)
	defer ts.Close()

	status, err := NewFromURL(ts.URL).ClockStatus()
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "GET" || cap.path != "/mockserver/clock" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if status != `{"frozen":true,"currentEpochMillis":1700000000000}` {
		t.Errorf("unexpected status body %q", status)
	}
}

// ---------------------------------------------------------------------------
// 2. Metrics
// ---------------------------------------------------------------------------

func TestClient_RetrieveMetrics(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"EXPECTATION_NOT_MATCHED_COUNT":3,"RECEIVED_REQUESTS_COUNT":10}`, &cap)
	defer ts.Close()

	metrics, err := NewFromURL(ts.URL).RetrieveMetrics()
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/retrieve" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if cap.rawQuery != "type=METRICS" {
		t.Errorf("unexpected query %q", cap.rawQuery)
	}
	if metrics["RECEIVED_REQUESTS_COUNT"] != 10 || metrics["EXPECTATION_NOT_MATCHED_COUNT"] != 3 {
		t.Errorf("unexpected metrics %v", metrics)
	}
}

func TestClient_ScrapeMetrics(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, "# HELP mockserver_requests_total\nmockserver_requests_total 5\n", &cap)
	defer ts.Close()

	text, err := NewFromURL(ts.URL).ScrapeMetrics()
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "GET" || cap.path != "/mockserver/metrics" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if text == "" {
		t.Errorf("expected exposition text")
	}
}

func TestClient_ScrapeMetrics_Disabled(t *testing.T) {
	var cap captured
	ts := cpStub(t, 404, "", &cap)
	defer ts.Close()

	if _, err := NewFromURL(ts.URL).ScrapeMetrics(); err == nil {
		t.Errorf("expected error when metrics disabled (404)")
	}
}

// ---------------------------------------------------------------------------
// 3. Configuration
// ---------------------------------------------------------------------------

func TestClient_RetrieveConfiguration(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"logLevel":"INFO"}`, &cap)
	defer ts.Close()

	cfg, err := NewFromURL(ts.URL).RetrieveConfiguration()
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "GET" || cap.path != "/mockserver/configuration" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if cfg != `{"logLevel":"INFO"}` {
		t.Errorf("unexpected config %q", cfg)
	}
}

func TestClient_UpdateConfiguration(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"logLevel":"DEBUG"}`, &cap)
	defer ts.Close()

	cfg, err := NewFromURL(ts.URL).UpdateConfiguration(`{"logLevel":"DEBUG"}`)
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/configuration" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if string(cap.body) != `{"logLevel":"DEBUG"}` {
		t.Errorf("unexpected sent body %q", string(cap.body))
	}
	if cfg != `{"logLevel":"DEBUG"}` {
		t.Errorf("unexpected returned config %q", cfg)
	}
}

// ---------------------------------------------------------------------------
// 4. Pact
// ---------------------------------------------------------------------------

func TestClient_PactImport(t *testing.T) {
	var cap captured
	ts := cpStub(t, 201, `[{"id":"abc"}]`, &cap)
	defer ts.Close()

	exps, err := NewFromURL(ts.URL).PactImport(`{"interactions":[]}`)
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/pact/import" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if len(exps) != 1 || exps[0].ID != "abc" {
		t.Errorf("unexpected expectations %v", exps)
	}
}

func TestClient_PactImport_EmptyRejected(t *testing.T) {
	if _, err := New("localhost", 1080).PactImport(""); err == nil {
		t.Errorf("expected error for empty pact JSON")
	}
}

func TestClient_PactExport(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"consumer":{"name":"c"}}`, &cap)
	defer ts.Close()

	contract, err := NewFromURL(ts.URL).PactExport("my-consumer", "my-provider")
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/pact" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	q := mustQuery(t, cap.rawQuery)
	if q.Get("consumer") != "my-consumer" || q.Get("provider") != "my-provider" {
		t.Errorf("unexpected query %q", cap.rawQuery)
	}
	if contract != `{"consumer":{"name":"c"}}` {
		t.Errorf("unexpected contract %q", contract)
	}
}

func TestClient_PactExport_OmitsBlankParams(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{}`, &cap)
	defer ts.Close()

	if _, err := NewFromURL(ts.URL).PactExport("", ""); err != nil {
		t.Fatal(err)
	}
	if cap.rawQuery != "" {
		t.Errorf("expected no query params, got %q", cap.rawQuery)
	}
}

func TestClient_PactVerify_Pass(t *testing.T) {
	var cap captured
	ts := cpStub(t, 202, `{"verified":true}`, &cap)
	defer ts.Close()

	passed, report, err := NewFromURL(ts.URL).PactVerify(`{"interactions":[]}`)
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/pact/verify" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if !passed {
		t.Errorf("expected passed=true on 202")
	}
	if report != `{"verified":true}` {
		t.Errorf("unexpected report %q", report)
	}
}

func TestClient_PactVerify_Fail(t *testing.T) {
	var cap captured
	ts := cpStub(t, 406, `{"verified":false}`, &cap)
	defer ts.Close()

	passed, report, err := NewFromURL(ts.URL).PactVerify(`{"interactions":[]}`)
	if err != nil {
		t.Fatalf("406 should not be an error, got %v", err)
	}
	if passed {
		t.Errorf("expected passed=false on 406")
	}
	if report != `{"verified":false}` {
		t.Errorf("unexpected report %q", report)
	}
}

func TestClient_PactVerify_BadRequestIsError(t *testing.T) {
	var cap captured
	ts := cpStub(t, 400, `{"error":"bad"}`, &cap)
	defer ts.Close()

	if _, _, err := NewFromURL(ts.URL).PactVerify(`{}`); err == nil {
		t.Errorf("expected error on 400")
	}
}

// ---------------------------------------------------------------------------
// 5. File store
// ---------------------------------------------------------------------------

func TestClient_StoreFile(t *testing.T) {
	var cap captured
	ts := cpStub(t, 201, `{"name":"a.txt","size":5}`, &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).StoreFile("a.txt", "hello"); err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/files/store" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	var m map[string]interface{}
	_ = json.Unmarshal(cap.body, &m)
	if m["name"] != "a.txt" || m["content"] != "hello" {
		t.Errorf("unexpected body %v", m)
	}
}

func TestClient_StoreFileBytes(t *testing.T) {
	var cap captured
	ts := cpStub(t, 201, `{}`, &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).StoreFileBytes("b.bin", []byte{0x01, 0x02}); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	_ = json.Unmarshal(cap.body, &m)
	if m["base64"] != true {
		t.Errorf("expected base64 true, got %v", m)
	}
	if m["content"] != "AQI=" {
		t.Errorf("expected base64 content AQI=, got %v", m["content"])
	}
}

func TestClient_RetrieveFile(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, "file-contents", &cap)
	defer ts.Close()

	data, err := NewFromURL(ts.URL).RetrieveFile("a.txt")
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/files/retrieve" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	var m map[string]string
	_ = json.Unmarshal(cap.body, &m)
	if m["name"] != "a.txt" {
		t.Errorf("unexpected body %v", m)
	}
	if string(data) != "file-contents" {
		t.Errorf("expected raw 200 body, got %q", string(data))
	}
}

func TestClient_RetrieveFile_NotFound(t *testing.T) {
	var cap captured
	ts := cpStub(t, 404, "file not found: a.txt", &cap)
	defer ts.Close()

	if _, err := NewFromURL(ts.URL).RetrieveFile("a.txt"); err == nil {
		t.Errorf("expected error on 404")
	}
}

func TestClient_ListFiles(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `["a.txt","b.bin"]`, &cap)
	defer ts.Close()

	names, err := NewFromURL(ts.URL).ListFiles()
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/files/list" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if len(names) != 2 || names[0] != "a.txt" || names[1] != "b.bin" {
		t.Errorf("unexpected names %v", names)
	}
}

func TestClient_DeleteFile(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, "", &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).DeleteFile("a.txt"); err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/files/delete" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	var m map[string]string
	_ = json.Unmarshal(cap.body, &m)
	if m["name"] != "a.txt" {
		t.Errorf("unexpected body %v", m)
	}
}

func TestClient_DeleteFile_NotFound(t *testing.T) {
	var cap captured
	ts := cpStub(t, 404, "file not found", &cap)
	defer ts.Close()

	if err := NewFromURL(ts.URL).DeleteFile("a.txt"); err == nil {
		t.Errorf("expected error on 404")
	}
}

// ---------------------------------------------------------------------------
// 6. Import
// ---------------------------------------------------------------------------

func TestClient_ImportHar(t *testing.T) {
	var cap captured
	ts := cpStub(t, 201, `[{"id":"har1"}]`, &cap)
	defer ts.Close()

	exps, err := NewFromURL(ts.URL).ImportHar(`{"log":{"entries":[]}}`)
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/import" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if mustQuery(t, cap.rawQuery).Get("format") != "har" {
		t.Errorf("unexpected query %q", cap.rawQuery)
	}
	if len(exps) != 1 || exps[0].ID != "har1" {
		t.Errorf("unexpected expectations %v", exps)
	}
}

func TestClient_ImportPostmanCollection(t *testing.T) {
	var cap captured
	ts := cpStub(t, 201, `[{"id":"pm1"}]`, &cap)
	defer ts.Close()

	exps, err := NewFromURL(ts.URL).ImportPostmanCollection(`{"info":{},"item":[]}`)
	if err != nil {
		t.Fatal(err)
	}
	if mustQuery(t, cap.rawQuery).Get("format") != "postman" {
		t.Errorf("unexpected query %q", cap.rawQuery)
	}
	if len(exps) != 1 || exps[0].ID != "pm1" {
		t.Errorf("unexpected expectations %v", exps)
	}
}

func TestClient_ImportHar_EmptyRejected(t *testing.T) {
	if _, err := New("localhost", 1080).ImportHar(""); err == nil {
		t.Errorf("expected error for empty HAR")
	}
}

// ---------------------------------------------------------------------------
// 7. Operating mode
// ---------------------------------------------------------------------------

func TestClient_SetMode(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"mode":"SPY","proxyUnmatchedRequests":true}`, &cap)
	defer ts.Close()

	status, err := NewFromURL(ts.URL).SetMode(ModeSpy)
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/mode" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if mustQuery(t, cap.rawQuery).Get("mode") != "SPY" {
		t.Errorf("unexpected query %q", cap.rawQuery)
	}
	if status.Mode != ModeSpy || !status.ProxyUnmatchedRequests {
		t.Errorf("unexpected status %+v", status)
	}
}

func TestClient_RetrieveMode(t *testing.T) {
	var cap captured
	ts := cpStub(t, 200, `{"mode":"SIMULATE","proxyUnmatchedRequests":false}`, &cap)
	defer ts.Close()

	status, err := NewFromURL(ts.URL).RetrieveMode()
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "GET" || cap.path != "/mockserver/mode" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if status.Mode != ModeSimulate || status.ProxyUnmatchedRequests {
		t.Errorf("unexpected status %+v", status)
	}
}

// ---------------------------------------------------------------------------
// 8. WSDL
// ---------------------------------------------------------------------------

func TestClient_WsdlExpectation(t *testing.T) {
	var cap captured
	ts := cpStub(t, 201, `[{"id":"wsdl1"}]`, &cap)
	defer ts.Close()

	wsdl := `<?xml version="1.0"?><definitions></definitions>`
	exps, err := NewFromURL(ts.URL).WsdlExpectation(wsdl)
	if err != nil {
		t.Fatal(err)
	}
	if cap.method != "PUT" || cap.path != "/mockserver/wsdl" {
		t.Errorf("unexpected request %s %s", cap.method, cap.path)
	}
	if string(cap.body) != wsdl {
		t.Errorf("expected raw WSDL body, got %q", string(cap.body))
	}
	if cap.contentType != "application/xml; charset=utf-8" {
		t.Errorf("expected XML content type, got %q", cap.contentType)
	}
	if len(exps) != 1 || exps[0].ID != "wsdl1" {
		t.Errorf("unexpected expectations %v", exps)
	}
}

func TestClient_WsdlExpectation_EmptyRejected(t *testing.T) {
	if _, err := New("localhost", 1080).WsdlExpectation(""); err == nil {
		t.Errorf("expected error for empty WSDL")
	}
}
