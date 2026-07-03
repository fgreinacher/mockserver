"""Cross-language client fidelity harness — Python round-trip test.

Deserialize every shared fixture with the client model, re-serialize it, and
assert that the round-trip is semantically identical to the input, MODULO a
known-gaps ledger of paths the Python model currently drops or mutates.

The comparator (NORM + CANON + DIFFS + `*` matching) is the exact algorithm
shared by every language port; see repo-root `.tmp/roundtrip-spec.md` and the
reference implementation `.tmp/reference_compare.py`.

This is an unmarked (unit) test, so CI runs it under `pytest -m 'not integration'`.

Modes:
- Normal: per-fixture assertion that every unexcused diff path is empty, plus a
  ratchet test that every gap entry excuses >=1 diff across all fixtures.
- Discovery (`FIDELITY_DISCOVER=1`): print the sorted, deduped, `*`-normalized
  union of computed diff paths and pass without asserting. Used to (re)build the
  manifest. Runnable either via the `test_discover` test (needs `-s`) or the
  `__main__` block.

The manifest is read from `FIDELITY_KNOWN_GAPS` when that env var is set and
non-empty, otherwise from the repo-root default. This test NEVER writes the
manifest — the orchestrator owns it.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import pytest

# Make the local `mockserver` package importable when this file is run directly
# as a script (e.g. `python tests/test_roundtrip_fidelity.py` for discovery).
# Under pytest the package is already importable, so this is a harmless no-op.
_PKG_ROOT = str(Path(__file__).resolve().parents[1])
if _PKG_ROOT not in sys.path:
    sys.path.insert(0, _PKG_ROOT)

from mockserver.models import Expectation

LANG = "python"

# From tests/ -> mockserver-client-python -> repo root.
REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = REPO_ROOT / "test-fixtures" / "expectations"
DEFAULT_MANIFEST = FIXTURE_DIR / "known-gaps.json"

# MockServer key/value multimaps with two dual encodings.
MULTI = {"headers", "queryStringParameters", "trailers"}  # keyToMultiValue
SINGLE = {"cookies"}  # keyToValue


# ---------------------------------------------------------------------------
# Comparator (mirrors .tmp/reference_compare.py exactly)
# ---------------------------------------------------------------------------
def _canon_multi(v):
    out = {}
    if isinstance(v, dict):
        for k, val in v.items():
            out[k] = val if isinstance(val, list) else [val]
    elif isinstance(v, list):
        for e in v:
            if isinstance(e, dict) and "name" in e:
                vals = e.get("values", e.get("value"))
                out[e["name"]] = vals if isinstance(vals, list) else [vals]
    return out


def _canon_single(v):
    out = {}
    if isinstance(v, dict):
        out = dict(v)
    elif isinstance(v, list):
        for e in v:
            if isinstance(e, dict) and "name" in e:
                out[e["name"]] = e.get("value")
    return out


def norm(v, key=None):
    """NORM (null==absent) + CANON (dual-encoding) in one pass, keyed by parent key name."""
    if v is None:
        return None
    if key in MULTI:
        return {k: [norm(x) for x in vs] for k, vs in _canon_multi(v).items()}
    if key in SINGLE:
        return {k: norm(x) for k, x in _canon_single(v).items()}
    if isinstance(v, dict):
        return {k: norm(x, k) for k, x in v.items() if x is not None}
    if isinstance(v, list):
        return [norm(x) for x in v]
    return v


def diffs(a, b, path=""):
    res = []
    if isinstance(a, dict):
        if not isinstance(b, dict):
            return [path or "<root>"]
        for k, v in a.items():
            p = f"{path}.{k}" if path else k
            if k not in b:
                res.append(p)
            else:
                res += diffs(v, b[k], p)
        for k in b:
            if k not in a:
                res.append((f"{path}.{k}" if path else k) + " [ADDED]")
        return res
    if isinstance(a, list):
        if not isinstance(b, list):
            return [path or "<root>"]
        for i, v in enumerate(a):
            p = f"{path}.{i}"
            if i >= len(b):
                res.append(p)
            else:
                res += diffs(v, b[i], p)
        return res
    if a != b:
        res.append(path or "<root>")
    return res


def star(p):
    """Replace numeric path segments with `*` for a fixture-length-independent entry."""
    return ".".join("*" if s.isdigit() else s for s in p.split("."))


def excused(path, entries):
    """A gap entry G excuses diff path P iff len(G)<=len(P) and each G segment
    equals the P segment or is `*` matching an all-digit P segment."""
    bare = path.replace(" [ADDED]", "")
    p_segs = bare.split(".")
    for g in entries:
        g_segs = g.split(".")
        if len(g_segs) > len(p_segs):
            continue
        if all(
            g_segs[i] == p_segs[i] or (g_segs[i] == "*" and p_segs[i].isdigit())
            for i in range(len(g_segs))
        ):
            return True
    return False


# ---------------------------------------------------------------------------
# Fixtures & manifest
# ---------------------------------------------------------------------------
def fixture_files():
    return sorted(
        f for f in FIXTURE_DIR.glob("*.json") if f.name != "known-gaps.json"
    )


def load_gaps():
    override = os.environ.get("FIDELITY_KNOWN_GAPS")
    manifest_path = Path(override) if override else DEFAULT_MANIFEST
    manifest = json.loads(manifest_path.read_text())
    return list(manifest.get(LANG, []))


def roundtrip_diffs(fixture_path):
    """Computed diff paths for one fixture (raw, not `*`-normalized)."""
    data = json.loads(fixture_path.read_text())
    rt = Expectation.from_dict(data).to_dict()
    return diffs(norm(data), norm(rt))


FIXTURES = fixture_files()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
def test_fixture_set_present():
    # Guard against a wrong glob/path silently testing nothing (spec: 44 fixtures).
    assert len(FIXTURES) == 44, (
        f"expected 44 fixtures under {FIXTURE_DIR}, found {len(FIXTURES)}"
    )


@pytest.mark.parametrize("fixture", FIXTURES, ids=[f.name for f in FIXTURES])
def test_roundtrip_fidelity(fixture):
    gaps = load_gaps()
    unexcused = [p for p in roundtrip_diffs(fixture) if not excused(p, gaps)]
    assert not unexcused, (
        f"{fixture.name}: unexcused round-trip diffs:\n  "
        + "\n  ".join(unexcused)
    )


def test_ratchet_no_stale_gap_entries():
    """Every entry in the manifest must excuse >=1 diff across all fixtures.

    A stale entry means the model was fixed; CI then fails until it is removed.
    """
    gaps = load_gaps()
    all_star_paths = set()
    for fixture in FIXTURES:
        for p in roundtrip_diffs(fixture):
            all_star_paths.add(star(p.replace(" [ADDED]", "")))

    stale = [g for g in gaps if not any(excused(p, [g]) for p in all_star_paths)]
    assert not stale, (
        "stale known-gaps entries (excuse no diff — remove them):\n  "
        + "\n  ".join(stale)
    )


def _discover():
    gaps = set()
    for fixture in FIXTURES:
        for p in roundtrip_diffs(fixture):
            gaps.add(star(p.replace(" [ADDED]", "")))
    return sorted(gaps)


@pytest.mark.skipif(
    os.environ.get("FIDELITY_DISCOVER") != "1",
    reason="discovery mode (set FIDELITY_DISCOVER=1, run with -s)",
)
def test_discover():
    print("\n" + json.dumps(_discover(), indent=2))


if __name__ == "__main__":
    print(json.dumps(_discover(), indent=2))
