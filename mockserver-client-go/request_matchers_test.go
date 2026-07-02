package mockserver

import (
	"encoding/json"
	"reflect"
	"testing"
)

// assertJSONEqual marshals got and compares it (semantically, ignoring key order)
// against the expected JSON string.
func assertJSONEqual(t *testing.T, got interface{}, wantJSON string) {
	t.Helper()

	gotBytes, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}

	var gotAny, wantAny interface{}
	if err := json.Unmarshal(gotBytes, &gotAny); err != nil {
		t.Fatalf("unmarshal actual failed: %v (json=%s)", err, gotBytes)
	}
	if err := json.Unmarshal([]byte(wantJSON), &wantAny); err != nil {
		t.Fatalf("unmarshal expected failed: %v", err)
	}
	if !reflect.DeepEqual(gotAny, wantAny) {
		t.Errorf("JSON mismatch\n got: %s\nwant: %s", gotBytes, wantJSON)
	}
}

// TestJwtMatcherJSON asserts the typed JWT matcher serialises to the wire shape
// documented in httpRequest.json, including a regex claim and a negated claim.
func TestJwtMatcherJSON(t *testing.T) {
	req := Request().
		Method("GET").
		Path("/secure").
		Jwt(NewJwt().
			Claim("role", "^admin.*$"). // regex claim
			Claim("scope", "!internal"). // negated claim
			WithIssuer("https://issuer.example.com").
			WithAudience("api").
			WithAlgorithm("RS256")).
		Build()

	assertJSONEqual(t, req, `{
		"method": "GET",
		"path": "/secure",
		"jwt": {
			"claims": {
				"role": "^admin.*$",
				"scope": "!internal"
			},
			"issuer": "https://issuer.example.com",
			"audience": "api",
			"algorithm": "RS256"
		}
	}`)
}

// TestJwtMatcherHeaderScheme asserts the optional header/scheme fields serialise
// when set.
func TestJwtMatcherHeaderScheme(t *testing.T) {
	jwt := NewJwt().Claim("sub", "1234").WithHeader("X-Auth").WithScheme("Token")

	assertJSONEqual(t, jwt, `{
		"claims": {"sub": "1234"},
		"header": "X-Auth",
		"scheme": "Token"
	}`)
}

// TestAllOfBodyJSON asserts an ALL_OF body composed of a JSON_PATH matcher and a
// REGEX matcher serialises to the bodyAllOf wire shape documented in body.json.
func TestAllOfBodyJSON(t *testing.T) {
	req := Request().
		Method("POST").
		Path("/orders").
		AllOfBody(
			JSONPathBody("$.items[?(@.qty > 0)]"),
			RegexBody(".*\"currency\":\"GBP\".*"),
		).
		Build()

	assertJSONEqual(t, req, `{
		"method": "POST",
		"path": "/orders",
		"body": {
			"type": "ALL_OF",
			"bodyAllOf": [
				{"type": "JSON_PATH", "jsonPath": "$.items[?(@.qty > 0)]"},
				{"type": "REGEX", "regex": ".*\"currency\":\"GBP\".*"}
			]
		}
	}`)
}

// TestAllOfBodyConstructor asserts the standalone AllOf constructor produces the
// same shape and accepts a plain-string body matcher element.
func TestAllOfBodyConstructor(t *testing.T) {
	body := AllOf(
		JSONPathBody("$.id"),
		"literal-substring",
	)

	assertJSONEqual(t, body, `{
		"type": "ALL_OF",
		"bodyAllOf": [
			{"type": "JSON_PATH", "jsonPath": "$.id"},
			"literal-substring"
		]
	}`)
}
