package mockserver

// Cross-language client fidelity harness — GO port.
//
// For every fixture in repo-root test-fixtures/expectations/*.json (excluding
// the known-gaps.json manifest) this test unmarshals the JSON into the
// Expectation struct and re-marshals it, then compares the input and output as
// generic JSON values under a shared comparator (NORM + CANON + DIFFS) that is
// implemented identically across all client languages. Any path that differs is
// a fidelity gap (Go's pure encoding/json model silently drops unknown keys).
// Gaps are excused via the "go" entry in the known-gaps.json ledger; the ratchet
// subtest fails if a ledger entry no longer excuses any diff (i.e. the model was
// fixed and the entry is now stale).
//
// The exact comparator algorithm is specified in .tmp/roundtrip-spec.md and the
// reference Python is in .tmp/reference_compare.py.
//
// Modes:
//   - default: assert unexcused diffs are empty per fixture; assert ratchet.
//   - FIDELITY_DISCOVER=1: print the sorted, deduped, '*'-normalized union of
//     computed diff paths and PASS (used to (re)build the ledger).
//   - FIDELITY_KNOWN_GAPS=<path>: read the ledger from <path> instead of the
//     default repo-root manifest (used to self-verify against a scratch copy).
//
// This test never contacts a live server.

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

const (
	fidelityLang            = "go"
	fixturesDir             = "../test-fixtures/expectations"
	defaultKnownGapsRelPath = "../test-fixtures/expectations/known-gaps.json"
)

// keyToMultiValue fields (headers/params/trailers) accept an object form or an
// [{name,values|value}] array form; both canonicalize to { name -> [values] }.
var multiValueKeys = map[string]bool{
	"headers":               true,
	"queryStringParameters": true,
	"trailers":              true,
}

// keyToValue fields (cookies) canonicalize to { name -> value }.
var singleValueKeys = map[string]bool{
	"cookies": true,
}

// canonMulti canonicalizes a keyToMultiValue field to map name -> []values
// (values are normalized by the caller).
func canonMulti(v interface{}) map[string]interface{} {
	out := map[string]interface{}{}
	switch vv := v.(type) {
	case map[string]interface{}:
		for k, val := range vv {
			if lst, ok := val.([]interface{}); ok {
				out[k] = lst
			} else {
				out[k] = []interface{}{val}
			}
		}
	case []interface{}:
		for _, e := range vv {
			em, ok := e.(map[string]interface{})
			if !ok {
				continue
			}
			name, ok := em["name"]
			if !ok {
				continue
			}
			nameStr, ok := name.(string)
			if !ok {
				continue
			}
			vals, present := em["values"]
			if !present {
				vals = em["value"] // nil if also absent
			}
			if lst, ok := vals.([]interface{}); ok {
				out[nameStr] = lst
			} else {
				out[nameStr] = []interface{}{vals}
			}
		}
	}
	return out
}

// canonSingle canonicalizes a keyToValue field to map name -> value.
func canonSingle(v interface{}) map[string]interface{} {
	out := map[string]interface{}{}
	switch vv := v.(type) {
	case map[string]interface{}:
		for k, val := range vv {
			out[k] = val
		}
	case []interface{}:
		for _, e := range vv {
			em, ok := e.(map[string]interface{})
			if !ok {
				continue
			}
			name, ok := em["name"]
			if !ok {
				continue
			}
			if nameStr, ok := name.(string); ok {
				out[nameStr] = em["value"]
			}
		}
	}
	return out
}

// norm applies NORM (null==absent) and CANON (dual-encoding) in one recursive
// pass keyed by the parent object's key name.
func norm(v interface{}, key string) interface{} {
	if v == nil {
		return nil
	}
	if multiValueKeys[key] {
		out := map[string]interface{}{}
		for k, vs := range canonMulti(v) {
			lst := vs.([]interface{})
			normed := make([]interface{}, len(lst))
			for i, x := range lst {
				normed[i] = norm(x, "")
			}
			out[k] = normed
		}
		return out
	}
	if singleValueKeys[key] {
		out := map[string]interface{}{}
		for k, x := range canonSingle(v) {
			out[k] = norm(x, "")
		}
		return out
	}
	switch vv := v.(type) {
	case map[string]interface{}:
		out := map[string]interface{}{}
		for k, x := range vv {
			if x == nil {
				continue // drop null entries (absent == null)
			}
			out[k] = norm(x, k)
		}
		return out
	case []interface{}:
		out := make([]interface{}, len(vv))
		for i, x := range vv {
			out[i] = norm(x, "")
		}
		return out
	default:
		return vv
	}
}

func rootOr(path string) string {
	if path == "" {
		return "<root>"
	}
	return path
}

func sortedMapKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// diffs walks the canonicalized/normalized INPUT (a) against the OUTPUT (b) and
// returns the list of differing path strings. A "[ADDED]" suffix marks a key
// present only in the output.
func diffs(a, b interface{}, path string) []string {
	var res []string
	switch av := a.(type) {
	case map[string]interface{}:
		bv, ok := b.(map[string]interface{})
		if !ok {
			return []string{rootOr(path)}
		}
		for _, k := range sortedMapKeys(av) {
			p := k
			if path != "" {
				p = path + "." + k
			}
			if bval, present := bv[k]; !present {
				res = append(res, p)
			} else {
				res = append(res, diffs(av[k], bval, p)...)
			}
		}
		for _, k := range sortedMapKeys(bv) {
			if _, present := av[k]; !present {
				p := k
				if path != "" {
					p = path + "." + k
				}
				res = append(res, p+" [ADDED]")
			}
		}
		return res
	case []interface{}:
		bv, ok := b.([]interface{})
		if !ok {
			return []string{rootOr(path)}
		}
		for i, v := range av {
			p := fmt.Sprintf("%s.%d", path, i)
			if i >= len(bv) {
				res = append(res, p)
			} else {
				res = append(res, diffs(v, bv[i], p)...)
			}
		}
		return res
	default:
		if !reflect.DeepEqual(a, b) {
			res = append(res, rootOr(path))
		}
		return res
	}
}

func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// star replaces numeric path segments with '*' so a ledger entry is independent
// of a fixture's array lengths.
func star(p string) string {
	parts := strings.Split(p, ".")
	for i, s := range parts {
		if isAllDigits(s) {
			parts[i] = "*"
		}
	}
	return strings.Join(parts, ".")
}

// excused reports whether a ledger entry excuses the given diff path: some entry
// G matches path P iff len(G) <= len(P) and each G segment equals the P segment
// or is '*' matching an all-digit P segment.
func excused(path string, entries []string) bool {
	pseg := strings.Split(path, ".")
	for _, g := range entries {
		gseg := strings.Split(g, ".")
		if len(gseg) > len(pseg) {
			continue
		}
		match := true
		for i := 0; i < len(gseg); i++ {
			if gseg[i] == pseg[i] {
				continue
			}
			if gseg[i] == "*" && isAllDigits(pseg[i]) {
				continue
			}
			match = false
			break
		}
		if match {
			return true
		}
	}
	return false
}

func stripAdded(p string) string {
	return strings.TrimSuffix(p, " [ADDED]")
}

// roundTripDiffs computes the stripped ('[ADDED]' removed) diff paths for one
// fixture: unmarshal into Expectation, re-marshal, then compare as generic JSON.
func roundTripDiffs(t *testing.T, data []byte) []string {
	t.Helper()
	var input interface{}
	if err := json.Unmarshal(data, &input); err != nil {
		t.Fatalf("parse fixture: %v", err)
	}
	// Mirror the client's own round-trip: json.Unmarshal(data, &e) with the
	// error deliberately ignored. encoding/json records the first type
	// mismatch but still populates every other field, so a field whose Go type
	// cannot hold the fixture's JSON shape simply drops out — exactly the
	// fidelity gap this harness measures.
	var e Expectation
	_ = json.Unmarshal(data, &e)
	outBytes, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal Expectation: %v", err)
	}
	var output interface{}
	if err := json.Unmarshal(outBytes, &output); err != nil {
		t.Fatalf("reparse round-tripped Expectation: %v", err)
	}
	raw := diffs(norm(input, ""), norm(output, ""), "")
	paths := make([]string, len(raw))
	for i, p := range raw {
		paths[i] = stripAdded(p)
	}
	return paths
}

func knownGapsPath() string {
	if p := os.Getenv("FIDELITY_KNOWN_GAPS"); p != "" {
		return p
	}
	return defaultKnownGapsRelPath
}

func loadGaps(t *testing.T, lang string) []string {
	t.Helper()
	data, err := os.ReadFile(knownGapsPath())
	if err != nil {
		t.Fatalf("read known-gaps manifest: %v", err)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("parse known-gaps manifest: %v", err)
	}
	entry, ok := raw[lang]
	if !ok {
		return nil
	}
	var gaps []string
	if err := json.Unmarshal(entry, &gaps); err != nil {
		t.Fatalf("parse %q gap list: %v", lang, err)
	}
	return gaps
}

func loadFixtures(t *testing.T) []string {
	t.Helper()
	matches, err := filepath.Glob(filepath.Join(fixturesDir, "*.json"))
	if err != nil {
		t.Fatalf("glob fixtures: %v", err)
	}
	var files []string
	for _, f := range matches {
		if filepath.Base(f) == "known-gaps.json" {
			continue // the manifest is not a fixture
		}
		files = append(files, f)
	}
	sort.Strings(files)
	if len(files) == 0 {
		t.Fatalf("no fixtures found under %s", fixturesDir)
	}
	return files
}

func TestRoundTripFidelity(t *testing.T) {
	fixtures := loadFixtures(t)

	// Discovery mode: print the sorted, deduped, '*'-normalized union of all
	// computed diff paths and PASS (used to (re)build the ledger).
	if os.Getenv("FIDELITY_DISCOVER") == "1" {
		union := map[string]bool{}
		for _, f := range fixtures {
			data, err := os.ReadFile(f)
			if err != nil {
				t.Fatalf("read fixture %s: %v", f, err)
			}
			for _, p := range roundTripDiffs(t, data) {
				union[star(p)] = true
			}
		}
		out := make([]string, 0, len(union))
		for p := range union {
			out = append(out, p)
		}
		sort.Strings(out)
		rendered, _ := json.MarshalIndent(out, "", "  ")
		fmt.Printf("FIDELITY_DISCOVER go gaps (%d):\n%s\n", len(out), string(rendered))
		return
	}

	gaps := loadGaps(t, fidelityLang)

	// Per-fixture: assert every diff is excused by the ledger.
	// Also record which ledger entries actually excused something (for the ratchet).
	usedEntry := make([]bool, len(gaps))
	for _, f := range fixtures {
		f := f
		t.Run(filepath.Base(f), func(t *testing.T) {
			data, err := os.ReadFile(f)
			if err != nil {
				t.Fatalf("read fixture: %v", err)
			}
			var unexcused []string
			for _, p := range roundTripDiffs(t, data) {
				matched := false
				for i, g := range gaps {
					if excused(p, []string{g}) {
						usedEntry[i] = true
						matched = true
					}
				}
				if !matched {
					unexcused = append(unexcused, p)
				}
			}
			if len(unexcused) > 0 {
				sort.Strings(unexcused)
				t.Errorf("unexcused round-trip fidelity diffs (%d):\n  %s\nAdd these to known-gaps.json[%q] (with '*' for array indices) or fix the model.",
					len(unexcused), strings.Join(unexcused, "\n  "), fidelityLang)
			}
		})
	}

	// Ratchet: every ledger entry must excuse at least one diff across all
	// fixtures. A stale entry means the model was fixed and the entry must be
	// removed.
	t.Run("__ratchet__", func(t *testing.T) {
		var stale []string
		for i, g := range gaps {
			if !usedEntry[i] {
				stale = append(stale, g)
			}
		}
		if len(stale) > 0 {
			sort.Strings(stale)
			t.Errorf("stale known-gaps.json[%q] entries (excuse no diff; remove them):\n  %s",
				fidelityLang, strings.Join(stale, "\n  "))
		}
	})
}
