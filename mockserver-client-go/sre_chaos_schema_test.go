package mockserver

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// ServiceChaosProfile is serialised straight onto the /mockserver/serviceChaos control-plane
// payload, and the server ignores any property it does not recognise. That is a silent failure
// mode: ServiceChaosProfile carried `ConnectionDrop *bool `json:"connectionDrop"“ — a property
// that has never existed server-side (the real one is `dropConnectionProbability`, a 0.0-1.0
// probability) — so every Go user who set it had it quietly discarded.
//
// The expectation-level chaos fixtures did NOT catch this: they exercise the `chaos` block of an
// Expectation, which is a different payload from the service-chaos control-plane body, so a
// fixture-based probe never reaches this struct. This test closes that gap directly by checking
// the struct's JSON tags against the server's own httpChaosProfile.json schema.
func TestServiceChaosProfileTagsExistInServerSchema(t *testing.T) {
	schemaPath := filepath.Join("..", "mockserver", "mockserver-core", "src", "main",
		"resources", "org", "mockserver", "model", "schema", "httpChaosProfile.json")

	raw, err := os.ReadFile(schemaPath)
	if err != nil {
		t.Fatalf("cannot read server chaos schema at %s: %v", schemaPath, err)
	}

	var schema struct {
		Properties map[string]json.RawMessage `json:"properties"`
	}
	if err := json.Unmarshal(raw, &schema); err != nil {
		t.Fatalf("cannot parse %s: %v", schemaPath, err)
	}
	if len(schema.Properties) == 0 {
		t.Fatalf("server chaos schema declares no properties — wrong file?")
	}

	typ := reflect.TypeOf(ServiceChaosProfile{})
	for i := 0; i < typ.NumField(); i++ {
		field := typ.Field(i)
		tag := field.Tag.Get("json")
		if tag == "" || tag == "-" {
			continue
		}
		name := strings.Split(tag, ",")[0]
		if name == "" {
			continue
		}
		if _, ok := schema.Properties[name]; !ok {
			t.Errorf("ServiceChaosProfile.%s sends JSON property %q, which the server's "+
				"HttpChaosProfile schema does not declare — the server will silently ignore it",
				field.Name, name)
		}
	}
}
