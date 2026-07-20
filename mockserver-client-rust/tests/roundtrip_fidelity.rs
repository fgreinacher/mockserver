//! Cross-language JSON round-trip fidelity test for the Rust client.
//!
//! For every fixture in repo-root `test-fixtures/expectations/*.json` (excluding
//! the `known-gaps.json` manifest) this test:
//!   1. deserializes the JSON into [`mockserver_client::Expectation`] via serde,
//!   2. re-serializes the model back to a `serde_json::Value`,
//!   3. computes `DIFFS( CANON(NORM(input)), CANON(NORM(output)) )`, and
//!   4. asserts every remaining diff path is excused by the `rust` entry of the
//!      known-gaps ledger.
//!
//! The comparator (NORM null==absent, CANON of the dual header/param/cookie
//! encodings, DIFFS path notation, EXCUSED prefix + `*` matching) is a faithful
//! port of `.tmp/reference_compare.py` / `.tmp/roundtrip-spec.md` — every client
//! language must agree on the same diff paths for the same model behaviour.
//!
//! Environment switches:
//!   * `FIDELITY_DISCOVER=1` — instead of asserting, print the sorted, deduped,
//!     `*`-index-normalized union of computed diff paths (run with `-- --nocapture`)
//!     and pass. Used to (re)build the manifest.
//!   * `FIDELITY_KNOWN_GAPS=/abs/path.json` — read the manifest from this path
//!     instead of the default, for green self-verification against a scratch copy.
//!
//! This test needs no live MockServer — it is pure (de)serialization.

use mockserver_client::Expectation;
use serde_json::{Map, Value};
use std::collections::BTreeSet;
use std::path::PathBuf;

// keyToMultiValue fields — object form and `[{name,value(s)}]` array form are equal.
const MULTI: &[&str] = &["headers", "queryStringParameters", "trailers"];
// keyToValue field — object form and `[{name,value}]` array form are equal.
const SINGLE: &[&str] = &["cookies"];

// ---------------------------------------------------------------------------
// CANON — canonicalize the two dual encodings of MockServer key/value multimaps
// ---------------------------------------------------------------------------

/// Canonicalize a keyToMultiValue value to `{ name -> [values...] }` (values not normalized here).
fn canon_multi(v: &Value) -> Map<String, Value> {
    let mut out = Map::new();
    match v {
        Value::Object(m) => {
            for (k, val) in m {
                out.insert(k.clone(), as_value_list(val));
            }
        }
        Value::Array(a) => {
            for e in a {
                if let Some(name) = e.get("name").and_then(Value::as_str) {
                    let vals = e.get("values").or_else(|| e.get("value"));
                    let vals = vals.cloned().unwrap_or(Value::Null);
                    out.insert(name.to_owned(), as_value_list(&vals));
                }
            }
        }
        _ => {}
    }
    out
}

/// Canonicalize a keyToValue value to `{ name -> value }`.
fn canon_single(v: &Value) -> Map<String, Value> {
    let mut out = Map::new();
    match v {
        Value::Object(m) => out = m.clone(),
        Value::Array(a) => {
            for e in a {
                if let Some(name) = e.get("name").and_then(Value::as_str) {
                    let val = e.get("value").cloned().unwrap_or(Value::Null);
                    out.insert(name.to_owned(), val);
                }
            }
        }
        _ => {}
    }
    out
}

/// Wrap a scalar in a 1-element list; pass a list through unchanged.
fn as_value_list(v: &Value) -> Value {
    match v {
        Value::Array(_) => v.clone(),
        other => Value::Array(vec![other.clone()]),
    }
}

// ---------------------------------------------------------------------------
// NORM (null==absent) + CANON in one pass, keyed by parent key name
// ---------------------------------------------------------------------------

fn norm(v: &Value, key: Option<&str>) -> Value {
    if v.is_null() {
        return Value::Null;
    }
    if let Some(k) = key {
        if MULTI.contains(&k) {
            let mut out = Map::new();
            for (name, vals) in canon_multi(v) {
                let listed = match vals {
                    Value::Array(a) => Value::Array(a.iter().map(|x| norm(x, None)).collect()),
                    other => norm(&other, None),
                };
                out.insert(name, listed);
            }
            return Value::Object(out);
        }
        if SINGLE.contains(&k) {
            let mut out = Map::new();
            for (name, val) in canon_single(v) {
                out.insert(name, norm(&val, None));
            }
            return Value::Object(out);
        }
    }
    match v {
        Value::Object(m) => {
            let mut out = Map::new();
            for (k, x) in m {
                if !x.is_null() {
                    out.insert(k.clone(), norm(x, Some(k)));
                }
            }
            Value::Object(out)
        }
        Value::Array(a) => Value::Array(a.iter().map(|x| norm(x, None)).collect()),
        scalar => scalar.clone(),
    }
}

// ---------------------------------------------------------------------------
// DIFFS(a, b) -> list of path strings
// ---------------------------------------------------------------------------

fn join(path: &str, seg: &str) -> String {
    if path.is_empty() {
        seg.to_owned()
    } else {
        format!("{path}.{seg}")
    }
}

fn diffs(a: &Value, b: &Value, path: &str, out: &mut Vec<String>) {
    match a {
        Value::Object(am) => {
            let Value::Object(bm) = b else {
                out.push(root_or(path));
                return;
            };
            for (k, v) in am {
                let p = join(path, k);
                match bm.get(k) {
                    Some(bv) => diffs(v, bv, &p, out),
                    None => out.push(p),
                }
            }
            for k in bm.keys() {
                if !am.contains_key(k) {
                    out.push(format!("{} [ADDED]", join(path, k)));
                }
            }
        }
        Value::Array(aa) => {
            let Value::Array(ba) = b else {
                out.push(root_or(path));
                return;
            };
            for (i, v) in aa.iter().enumerate() {
                let p = format!("{path}.{i}");
                match ba.get(i) {
                    Some(bv) => diffs(v, bv, &p, out),
                    None => out.push(p),
                }
            }
        }
        scalar => {
            if scalar != b {
                out.push(root_or(path));
            }
        }
    }
}

fn root_or(path: &str) -> String {
    if path.is_empty() {
        "<root>".to_owned()
    } else {
        path.to_owned()
    }
}

// ---------------------------------------------------------------------------
// EXCUSED + star normalization + manifest loading
// ---------------------------------------------------------------------------

fn is_all_digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

/// Replace numeric path segments with `*` for a fixture-length-independent entry.
fn star(p: &str) -> String {
    p.split('.')
        .map(|s| if is_all_digits(s) { "*" } else { s })
        .collect::<Vec<_>>()
        .join(".")
}

/// A manifest entry `g` excuses a diff path `p` iff, splitting both on `.`,
/// `len(g) <= len(p)` and each segment matches literally or via `*` on a digit.
fn excuses(g: &str, p: &str) -> bool {
    let gs: Vec<&str> = g.split('.').collect();
    let ps: Vec<&str> = p.split('.').collect();
    if gs.len() > ps.len() {
        return false;
    }
    gs.iter().zip(ps.iter()).all(|(gseg, pseg)| {
        gseg == pseg || (*gseg == "*" && is_all_digits(pseg))
    })
}

fn is_excused(path: &str, entries: &[String]) -> bool {
    entries.iter().any(|g| excuses(g, path))
}

fn expectations_dir() -> PathBuf {
    // cargo runs with CWD = package root; fixtures live at repo-root sibling.
    PathBuf::from(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../test-fixtures/expectations"
    ))
}

fn load_fixtures() -> Vec<(String, Value)> {
    let dir = expectations_dir();
    let mut files: Vec<PathBuf> = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("cannot read fixtures dir {}: {e}", dir.display()))
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.extension().map(|x| x == "json").unwrap_or(false)
                && p.file_name().and_then(|n| n.to_str()) != Some("known-gaps.json")
        })
        .collect();
    files.sort();
    assert!(
        !files.is_empty(),
        "no fixtures found under {}",
        dir.display()
    );
    files
        .into_iter()
        .map(|p| {
            let name = p
                .file_name()
                .and_then(|n| n.to_str())
                .expect("fixture file name")
                .to_owned();
            let text = std::fs::read_to_string(&p)
                .unwrap_or_else(|e| panic!("cannot read fixture {name}: {e}"));
            let value: Value = serde_json::from_str(&text)
                .unwrap_or_else(|e| panic!("fixture {name} is not valid JSON: {e}"));
            (name, value)
        })
        .collect()
}

fn load_gaps() -> Vec<String> {
    let path = match std::env::var("FIDELITY_KNOWN_GAPS") {
        Ok(p) if !p.is_empty() => PathBuf::from(p),
        _ => expectations_dir().join("known-gaps.json"),
    };
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read known-gaps manifest {}: {e}", path.display()));
    let manifest: Value = serde_json::from_str(&text)
        .unwrap_or_else(|e| panic!("known-gaps manifest {} is not valid JSON: {e}", path.display()));
    manifest
        .get("rust")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|v| v.as_str().map(str::to_owned))
                .collect()
        })
        .unwrap_or_default()
}

/// Sentinel diff path emitted when the model cannot deserialize a fixture at all
/// (serde hard-errors — e.g. a body/matcher shape the struct's field types reject,
/// which loses the WHOLE expectation, not just one field). Kept as a single opaque
/// token (no `.` so it is one path segment) so it is excused in isolation and never
/// masks the precise per-field drops of OTHER, parseable fixtures.
///
/// SCOPED PER FIXTURE (`<unrepresentable-expectation>:<fixture-stem>`). An unscoped token would be
/// excused globally by one ledger entry, so a SECOND fixture becoming unparseable later would be
/// silently absorbed and the ratchet would never re-arm — a gap entry that quietly widens its own
/// scope over time.
const UNREPRESENTABLE: &str = "<unrepresentable-expectation>";

/// Build the per-fixture sentinel for `name` (a fixture file name such as `foo.json`).
fn unrepresentable_for(name: &str) -> String {
    format!("{}:{}", UNREPRESENTABLE, name.trim_end_matches(".json"))
}

/// Deserialize a fixture into the model and re-serialize it back to a JSON value.
/// Returns `None` when the model cannot even parse the fixture (see `UNREPRESENTABLE`).
fn round_trip(input: &Value) -> Option<Value> {
    serde_json::from_value::<Expectation>(input.clone())
        .ok()
        .map(|model| serde_json::to_value(&model).expect("Expectation serializes to JSON"))
}

/// Compute raw diff paths (with any `[ADDED]` suffix stripped) per fixture.
fn all_diffs() -> Vec<(String, Vec<String>)> {
    load_fixtures()
        .into_iter()
        .map(|(name, input)| match round_trip(&input) {
            None => {
                let sentinel = unrepresentable_for(&name);
                (name, vec![sentinel])
            }
            Some(output) => {
                let mut raw = Vec::new();
                diffs(&norm(&input, None), &norm(&output, None), "", &mut raw);
                let paths = raw
                    .into_iter()
                    .map(|d| d.replace(" [ADDED]", ""))
                    .collect();
                (name, paths)
            }
        })
        .collect()
}

fn discover_mode() -> bool {
    std::env::var("FIDELITY_DISCOVER").map(|v| !v.is_empty()).unwrap_or(false)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[test]
fn round_trip_fidelity() {
    let per_fixture = all_diffs();

    if discover_mode() {
        let mut union: BTreeSet<String> = BTreeSet::new();
        for (_, paths) in &per_fixture {
            for p in paths {
                union.insert(star(p));
            }
        }
        let list: Vec<&String> = union.iter().collect();
        println!(
            "FIDELITY_DISCOVER rust gaps:\n{}",
            serde_json::to_string_pretty(&list).expect("serialize discovered gaps")
        );
        return;
    }

    let gaps = load_gaps();
    let mut failures: Vec<String> = Vec::new();
    for (name, paths) in &per_fixture {
        let unexcused: Vec<&String> =
            paths.iter().filter(|p| !is_excused(p, &gaps)).collect();
        if !unexcused.is_empty() {
            let mut sorted: Vec<&String> = unexcused;
            sorted.sort();
            failures.push(format!(
                "  {name}:\n{}",
                sorted
                    .iter()
                    .map(|p| format!("    - {p}"))
                    .collect::<Vec<_>>()
                    .join("\n")
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "round-trip fidelity failures (unexcused dropped/mutated paths):\n{}",
        failures.join("\n")
    );
}

#[test]
fn known_gaps_ratchet() {
    if discover_mode() {
        return;
    }
    let gaps = load_gaps();
    let per_fixture = all_diffs();
    let all_paths: Vec<&String> =
        per_fixture.iter().flat_map(|(_, paths)| paths.iter()).collect();

    let stale: Vec<&String> = gaps
        .iter()
        .filter(|g| !all_paths.iter().any(|p| excuses(g, p)))
        .collect();

    assert!(
        stale.is_empty(),
        "stale known-gaps `rust` entries that no longer excuse any diff (remove them):\n{}",
        stale
            .iter()
            .map(|g| format!("  - {g}"))
            .collect::<Vec<_>>()
            .join("\n")
    );
}
