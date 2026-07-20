#!/usr/bin/env python3
"""Generate the Postman and Bruno API collections from the MockServer OpenAPI spec.

The OpenAPI spec (jekyll-www.mock-server.com/mockserver-openapi.yaml) is the single
source of truth: edit request-body examples THERE, then run this generator. Both
collections are derived from it so they never diverge.

    python3 scripts/collections/generate_collections.py

Outputs:
    examples/postman/MockServer.postman_collection.json   (Postman v2.1.0)
    examples/bruno/**                                     (Bruno .bru files)

Depends only on PyYAML (already a project dependency); no network access.
"""
import json
import os
import re
import shutil
import sys

import yaml

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SPEC = os.path.join(REPO, "jekyll-www.mock-server.com", "mockserver-openapi.yaml")
POSTMAN_OUT = os.path.join(REPO, "examples", "postman", "MockServer.postman_collection.json")
BRUNO_DIR = os.path.join(REPO, "examples", "bruno")

COLLECTION_NAME = "MockServer Control Plane"
# Stable Postman collection id so Postman tracks history across regenerations.
POSTMAN_ID = "9d7b6a51-2c84-4f0e-9b2a-7f1c3e5a6b40"
DOCS_URL = "https://www.mock-server.com/mock_server/mockserver_clients.html"

# Example values for path parameters ({name} etc.).
PATH_PARAM_EXAMPLES = {"name": "checkout"}
# Query params to enable (with value) rather than leave disabled — needed for the request to work.
ENABLE_QUERY = {("put", "/mockserver/mode"): {"mode": "SIMULATE"}}

# Fallback auth when the OpenAPI spec declares no securitySchemes. MockServer's
# control plane supports JWT bearer authentication
# (controlPlaneJWTAuthenticationRequired) — so a bearer token is the realistic
# default. It stays inert until the user fills in the placeholder variable, so
# importing the collection against an unauthenticated MockServer still works.
DEFAULT_SECURITY_SCHEMES = {
    "bearerAuth": {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "JWT",
        "description": "MockServer control-plane JWT bearer token "
                       "(enabled via controlPlaneJWTAuthenticationRequired).",
    }
}


def load_spec():
    with open(SPEC) as f:
        return yaml.safe_load(f)


def _placeholder_var(scheme_name, kind):
    """Stable collection-variable name for a scheme's placeholder credential."""
    return {"bearer": "bearerToken", "apikey": "apiKey",
            "basic_user": "username", "basic_pass": "password"}.get(kind, scheme_name)


def resolve_auth(spec):
    """Pick the collection-level auth from the spec's securitySchemes.

    Returns (auth_dict, variables) where auth_dict is a normalised, source-agnostic
    description of the chosen scheme and variables is a list of (key, value) collection
    variables holding the placeholder credentials. When the spec declares no
    securitySchemes we fall back to DEFAULT_SECURITY_SCHEMES (a JWT bearer token).

    A collection carries a single top-level auth, so when several schemes exist we
    prefer them in the order bearer/http -> apiKey -> basic, matching how most users
    authenticate against the control plane.
    """
    schemes = (spec.get("components", {}) or {}).get("securitySchemes") or DEFAULT_SECURITY_SCHEMES

    def by_priority(item):
        name, s = item
        t = (s.get("type") or "").lower()
        scheme = (s.get("scheme") or "").lower()
        if t == "http" and scheme == "bearer":
            return 0
        if t == "apikey":
            return 1
        if t == "http" and scheme == "basic":
            return 2
        return 3

    name, scheme = min(schemes.items(), key=by_priority)
    t = (scheme.get("type") or "").lower()
    s = (scheme.get("scheme") or "").lower()

    if t == "http" and s == "bearer":
        var = _placeholder_var(name, "bearer")
        return {"kind": "bearer", "token_var": var}, [(var, "")]
    if t == "apikey":
        var = _placeholder_var(name, "apikey")
        key_name = scheme.get("name", "X-API-Key")
        location = scheme.get("in", "header")
        return ({"kind": "apikey", "header_name": key_name, "in": location, "value_var": var},
                [(var, "")])
    if t == "http" and s == "basic":
        user_var = _placeholder_var(name, "basic_user")
        pass_var = _placeholder_var(name, "basic_pass")
        return ({"kind": "basic", "user_var": user_var, "pass_var": pass_var},
                [(user_var, ""), (pass_var, "")])
    # Unsupported scheme type (oauth2/openIdConnect): leave auth off but don't fail.
    return None, []


def media_and_example(op):
    """Return (mediaType, example, requestBodyDescription) or (None, None, None)."""
    rb = op.get("requestBody")
    if not rb:
        return None, None, None
    content = rb.get("content", {})
    if not content:
        return None, None, rb.get("description")
    mt = next(iter(content))
    return mt, content[mt].get("example"), rb.get("description")


def body_language(mt):
    if mt is None:
        return None
    if "json" in mt:
        return "json"
    if "xml" in mt:
        return "xml"
    return "text"


def raw_body(example, lang):
    if example is None:
        return None
    if lang == "json":
        return json.dumps(example, indent=2)
    return example  # xml / text are already strings


def request_docs(op, rb_desc):
    parts = []
    summary = op.get("summary")
    if op.get("description"):
        parts.append(op["description"].strip())
    elif summary:
        parts.append(summary.strip().capitalize() + ".")
    if rb_desc:
        parts.append(rb_desc.strip())
    parts.append(f"Full API reference: {DOCS_URL}")
    return "\n\n".join(parts)


def substitute_path(path):
    return re.sub(r"\{(\w+)\}", lambda m: PATH_PARAM_EXAMPLES.get(m.group(1), m.group(1)), path)


def query_params(op, method, path):
    """Return list of (key, value, enabled) from the spec, enabling only what's needed."""
    enable = ENABLE_QUERY.get((method, path), {})
    out = []
    for p in op.get("parameters", []) or []:
        if p.get("in") != "query":
            continue
        key = p["name"]
        sch = p.get("schema", {})
        val = sch.get("default")
        if val is None and sch.get("enum"):
            val = sch["enum"][0]
        if val is None:
            val = p.get("example", "")
        if key in enable:
            out.append((key, enable[key], True))
        else:
            out.append((key, _query_value(val), False))
    return out


def _query_value(val):
    """Render a spec default/example as it must appear on the wire.

    `str(True)` is Python's `repr`, "True" — which is not what any of MockServer's handlers parse.
    They compare against "true"/"false" (`"true".equalsIgnoreCase(...)`), so a boolean default has to
    be lower-cased or the generated collections carry a value the server does not recognise. This is
    cosmetic for the handlers that treat "not false" as true, but it is still wrong in a user-facing
    artifact, and a user copying it into their own tooling inherits the error.
    """
    if val is None:
        return ""
    if isinstance(val, bool):
        return "true" if val else "false"
    return str(val)


def iter_ops(spec):
    """Yield (tag, method, path, op) in spec order."""
    for path, methods in spec["paths"].items():
        for method, op in methods.items():
            if method not in ("get", "put", "post", "delete", "patch"):
                continue
            tag = (op.get("tags") or ["other"])[0]
            yield tag, method, path, op


def tag_order(spec):
    order = [t["name"] for t in spec.get("tags", [])]
    return order


# ---------------- Postman ----------------

def postman_auth(auth):
    """Translate the normalised auth into a Postman v2.1.0 collection-level auth block."""
    if not auth:
        return None
    if auth["kind"] == "bearer":
        return {"type": "bearer",
                "bearer": [{"key": "token", "value": "{{%s}}" % auth["token_var"], "type": "string"}]}
    if auth["kind"] == "apikey":
        return {"type": "apikey",
                "apikey": [
                    {"key": "key", "value": auth["header_name"], "type": "string"},
                    {"key": "value", "value": "{{%s}}" % auth["value_var"], "type": "string"},
                    {"key": "in", "value": auth["in"], "type": "string"},
                ]}
    if auth["kind"] == "basic":
        return {"type": "basic",
                "basic": [
                    {"key": "username", "value": "{{%s}}" % auth["user_var"], "type": "string"},
                    {"key": "password", "value": "{{%s}}" % auth["pass_var"], "type": "string"},
                ]}
    return None


def build_postman(spec, auth=None, auth_vars=()):
    folders = {}
    order = tag_order(spec)
    for tag, method, path, op in iter_ops(spec):
        mt, example, rb_desc = media_and_example(op)
        lang = body_language(mt)
        raw = raw_body(example, lang)
        real_path = substitute_path(path)
        segments = [s for s in real_path.split("/") if s]
        qp = query_params(op, method, path)
        url = {
            "raw": "{{baseUrl}}" + real_path + (
                "?" + "&".join(f"{k}={v}" for k, v, en in qp if en) if any(en for _, _, en in qp) else ""),
            "host": ["{{baseUrl}}"],
            "path": segments,
        }
        if qp:
            url["query"] = [{"key": k, "value": v, **({"disabled": True} if not en else {})}
                            for k, v, en in qp]
        req = {
            "method": method.upper(),
            "header": ([{"key": "Content-Type", "value": mt}] if mt else []),
            "url": url,
            "description": request_docs(op, rb_desc),
        }
        if raw is not None:
            req["body"] = {"mode": "raw", "raw": raw,
                           "options": {"raw": {"language": lang if lang in ("json", "xml") else "text"}}}
        item = {"name": op.get("summary", f"{method.upper()} {path}"), "request": req}
        folders.setdefault(tag, []).append(item)

    items = []
    seen = []
    for tag in order + [t for t in folders if t not in order]:
        if tag in folders and tag not in seen:
            seen.append(tag)
            items.append({"name": tag, "item": folders[tag]})

    collection = {
        "info": {
            "_postman_id": POSTMAN_ID,
            "name": COLLECTION_NAME,
            "description": (
                "MockServer REST control plane — every endpoint, generated from the "
                "OpenAPI spec (mockserver-openapi.yaml). Set the baseUrl variable to your "
                f"MockServer instance. {DOCS_URL}"),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "item": items,
        "variable": [{"key": "baseUrl", "value": "http://localhost:1080"}]
                    + [{"key": k, "value": v} for k, v in auth_vars],
    }
    pm_auth = postman_auth(auth)
    if pm_auth:
        # Inserted after info so Postman applies it as the collection default; each
        # request inherits it unless overridden.
        collection["auth"] = pm_auth
    return collection


# ---------------- Bruno ----------------

def bru_escape_name(name):
    return name.replace("\n", " ")


def bruno_collection_auth(auth):
    """Render the collection-level auth { ... } + auth:<kind> { ... } block for collection.bru."""
    if not auth:
        return None, "none"
    if auth["kind"] == "bearer":
        return (f"auth {{\n  mode: bearer\n}}\n\n"
                f"auth:bearer {{\n  token: {{{{{auth['token_var']}}}}}\n}}\n"), "inherit"
    if auth["kind"] == "apikey":
        return (f"auth {{\n  mode: apikey\n}}\n\n"
                f"auth:apikey {{\n  key: {auth['header_name']}\n"
                f"  value: {{{{{auth['value_var']}}}}}\n  placement: {auth['in']}\n}}\n"), "inherit"
    if auth["kind"] == "basic":
        return (f"auth {{\n  mode: basic\n}}\n\n"
                f"auth:basic {{\n  username: {{{{{auth['user_var']}}}}}\n"
                f"  password: {{{{{auth['pass_var']}}}}}\n}}\n"), "inherit"
    return None, "none"


def build_bruno(spec, auth=None, auth_vars=()):
    if os.path.isdir(BRUNO_DIR):
        for entry in os.listdir(BRUNO_DIR):
            p = os.path.join(BRUNO_DIR, entry)
            if entry in ("bruno.json", "environments") or os.path.isdir(p):
                if os.path.isdir(p) and entry != "environments":
                    shutil.rmtree(p)
    os.makedirs(BRUNO_DIR, exist_ok=True)

    # bruno.json
    with open(os.path.join(BRUNO_DIR, "bruno.json"), "w") as f:
        json.dump({"version": "1", "name": COLLECTION_NAME, "type": "collection",
                   "ignore": ["node_modules", ".git"]}, f, indent=2)
        f.write("\n")

    # collection.bru — collection-level auth that requests inherit.
    coll_auth_block, request_auth = bruno_collection_auth(auth)
    if coll_auth_block:
        with open(os.path.join(BRUNO_DIR, "collection.bru"), "w") as f:
            f.write(coll_auth_block)

    # environment — baseUrl plus any auth placeholder variables.
    env_dir = os.path.join(BRUNO_DIR, "environments")
    os.makedirs(env_dir, exist_ok=True)
    with open(os.path.join(env_dir, "Local.bru"), "w") as f:
        var_lines = "  baseUrl: http://localhost:1080\n" + "".join(
            f"  {k}: {v}\n" for k, v in auth_vars)
        f.write(f"vars {{\n{var_lines}}}\n")

    order = tag_order(spec)
    folders = {}
    for tag, method, path, op in iter_ops(spec):
        folders.setdefault(tag, []).append((method, path, op))

    folder_seq = 0
    for tag in order + [t for t in folders if t not in order]:
        if tag not in folders:
            continue
        folder_seq += 1
        fdir = os.path.join(BRUNO_DIR, tag)
        os.makedirs(fdir, exist_ok=True)
        with open(os.path.join(fdir, "folder.bru"), "w") as f:
            f.write(f"meta {{\n  name: {tag}\n  seq: {folder_seq}\n}}\n")
        seq = 0
        for method, path, op in folders[tag]:
            seq += 1
            mt, example, rb_desc = media_and_example(op)
            lang = body_language(mt)
            raw = raw_body(example, lang)
            real_path = substitute_path(path)
            qp = query_params(op, method, path)
            enabled_q = [(k, v) for k, v, en in qp if en]
            url = "{{baseUrl}}" + real_path
            if enabled_q:
                url += "?" + "&".join(f"{k}={v}" for k, v in enabled_q)
            body_kind = "none" if raw is None else ("json" if lang == "json" else "text")
            name = bru_escape_name(op.get("summary", f"{method.upper()} {path}"))
            fname = re.sub(r"[\\/:*?\"<>|]", "-", name)[:120]

            lines = [f"meta {{\n  name: {name}\n  type: http\n  seq: {seq}\n}}\n"]
            lines.append(f"{method} {{\n  url: {url}\n  body: {body_kind}\n  auth: {request_auth}\n}}\n")
            if mt:
                lines.append(f"headers {{\n  Content-Type: {mt}\n}}\n")
            if qp:
                qlines = "".join(
                    (f"  ~{k}: {v}\n" if not en else f"  {k}: {v}\n") for k, v, en in qp)
                lines.append(f"params:query {{\n{qlines}}}\n")
            if raw is not None:
                indented = "\n".join("  " + ln for ln in raw.splitlines())
                lines.append(f"body:{('json' if lang=='json' else 'text')} {{\n{indented}\n}}\n")
            docs = request_docs(op, rb_desc)
            docs_indented = "\n".join("  " + ln for ln in docs.splitlines())
            lines.append(f"docs {{\n{docs_indented}\n}}\n")
            with open(os.path.join(fdir, f"{fname}.bru"), "w") as f:
                f.write("\n".join(lines))


def main():
    spec = load_spec()
    n_ops = sum(1 for _ in iter_ops(spec))
    auth, auth_vars = resolve_auth(spec)
    pm = build_postman(spec, auth, auth_vars)
    os.makedirs(os.path.dirname(POSTMAN_OUT), exist_ok=True)
    with open(POSTMAN_OUT, "w") as f:
        json.dump(pm, f, indent=2)
        f.write("\n")
    build_bruno(spec, auth, auth_vars)
    n_pm = sum(len(fl["item"]) for fl in pm["item"])
    auth_desc = auth["kind"] if auth else "none"
    print(f"collection auth: {auth_desc}")
    print(f"spec operations: {n_ops}")
    print(f"postman requests: {n_pm} across {len(pm['item'])} folders -> {POSTMAN_OUT}")
    n_bru = sum(len([x for x in os.listdir(os.path.join(BRUNO_DIR, d)) if x.endswith('.bru') and x != 'folder.bru'])
                for d in os.listdir(BRUNO_DIR)
                if os.path.isdir(os.path.join(BRUNO_DIR, d)) and d != "environments")
    print(f"bruno requests: {n_bru} -> {BRUNO_DIR}")
    if not (n_ops == n_pm == n_bru):
        print(f"ERROR: parity mismatch ops={n_ops} postman={n_pm} bruno={n_bru}", file=sys.stderr)
        sys.exit(1)
    print("parity OK")


if __name__ == "__main__":
    main()
