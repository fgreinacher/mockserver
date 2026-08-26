#!/usr/bin/env python3
"""Validate every example in the generated Postman collection against a live MockServer.

Walks examples/postman/MockServer.postman_collection.json and fires each request at a
running MockServer, asserting the example body is ACCEPTED (i.e. not a malformed-request
rejection). A 400/415/500 means the example body is wrong and fails the run. State-dependent
codes (404/406/409) still prove the body parsed, so they pass. Binary uploads, the
server-stopping /stop call, and not-on-classpath 501s are skipped (logged, never hidden).

    python3 scripts/collections/test_collections.py                 # starts a Docker MockServer
    python3 scripts/collections/test_collections.py --base-url URL  # use an already-running server

Exit 0 if all examples are accepted; 1 otherwise.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
POSTMAN = os.path.join(REPO, "examples", "postman", "MockServer.postman_collection.json")

# A 400/415/500 is a genuine "the example body is wrong" failure.
FAIL_CODES = {400, 415, 500}
# Endpoints excluded from auto-testing (with reason) — binary uploads + the kill switch.
SKIP_PATHS = {
    "/mockserver/stop": "stops the server",
    "/mockserver/grpc/descriptors": "binary upload (FileDescriptorSet)",
    "/mockserver/wasm/modules": "binary upload (disabled by default)",
}

# Examples that are KNOWN to be rejected today. This is a ratchet, not an exemption list: the gate
# stays green on these so it can be wired into CI now and catch any NEW broken example, while these
# remain visible in the output as KNOWN-FAIL and are expected to shrink to nothing.
#
# Two rules keep this from decaying into a place where breakage hides:
#   1. An entry that STOPS failing fails the run ("unexpectedly passing"), so the list cannot silently
#      outlive the bug it documents — you are forced to delete the entry.
#   2. Every entry carries a reason. An entry without one is drift wearing an exemption.
#
# The one remaining entry is state-dependent rather than a broken example: the request body is
# well-formed and the handler parses it, but a freshly-started container has no recorded traffic for
# it to convert. Clearing it needs the harness to record traffic through the proxy first, not a
# change to the example.
#
# The four entries that used to sit here were each a real defect, and each is now fixed rather than
# excused:
#   - /mockserver/graphql            application/graphql was not in MediaType.isString(), so the SDL
#                                    arrived base64-encoded (same for application/yaml)
#   - /mockserver/asyncapi/http      the OpenAPI spec declared no requestBody example at all, so the
#                                    generated collection sent an empty body
#   - .../generateFromOpenAPI        the example fetched its spec from a URL; it is now inline, so the
#                                    published example works without egress
#   - /mockserver/verifySLO          a disabled feature answered 400 (malformed body) instead of 403
KNOWN_FAILING = {
    "/mockserver/loadScenario/generateFromRecording": "requires recorded traffic that does not exist yet",
}


def flatten(items):
    for it in items:
        if "item" in it:
            yield from flatten(it["item"])
        else:
            yield it


def request_of(item):
    r = item["request"]
    method = r["method"]
    url = r["url"]
    raw = url["raw"]
    path = "/" + "/".join(url.get("path", []))
    enabled_query = "&".join(
        f"{q['key']}={q['value']}" for q in url.get("query", []) if not q.get("disabled"))
    headers = {h["key"]: h["value"] for h in r.get("header", [])}
    body = r.get("body", {}).get("raw")
    return method, path, enabled_query, headers, body


def wait_ready(base, timeout=40):
    for _ in range(timeout):
        try:
            req = urllib.request.Request(base + "/mockserver/status", method="PUT")
            with urllib.request.urlopen(req, timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def fire(base, method, path, query, headers, body):
    url = base + path + (("?" + query) if query else "")
    data = body.encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        return f"ERR:{e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", help="use an already-running MockServer instead of Docker")
    ap.add_argument("--image", default=os.environ.get("MOCKSERVER_IMAGE", "mockserver/mockserver:snapshot"))
    ap.add_argument("--port", type=int, default=1108)
    args = ap.parse_args()

    with open(POSTMAN) as f:
        coll = json.load(f)
    items = list(flatten(coll["item"]))

    container = None
    base = args.base_url
    if not base:
        container = "ms-collection-test"
        subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        print(f"starting {args.image} on :{args.port} ...")
        rc = subprocess.run(["docker", "run", "-d", "--rm", "--name", container,
                             "-p", f"{args.port}:1080", args.image], capture_output=True, text=True)
        if rc.returncode != 0:
            print("docker run failed:", rc.stderr, file=sys.stderr)
            return 2
        base = f"http://localhost:{args.port}"

    try:
        if not wait_ready(base):
            print("server did not become ready", file=sys.stderr)
            return 2

        # order: keep collection order but run /reset last-ish is unnecessary; we tolerate state codes.
        passed, failed, skipped, known_fail, unexpected_pass = [], [], [], [], []
        for it in items:
            method, path, query, headers, body = request_of(it)
            name = it["name"]
            if path in SKIP_PATHS:
                skipped.append((name, path, SKIP_PATHS[path]))
                continue
            code = fire(base, method, path, query, headers, body)
            tag = f"{method} {path}" + (f"?{query}" if query else "")
            if isinstance(code, int) and code == 501:
                skipped.append((name, path, "501 feature not on classpath"))
                continue
            # 502 = unmatched/forwarded => the endpoint is not present on this build at all.
            rejected = not isinstance(code, int) or code in FAIL_CODES or code == 502
            if path in KNOWN_FAILING:
                if rejected:
                    known_fail.append((name, tag, code, KNOWN_FAILING[path]))
                else:
                    unexpected_pass.append((name, tag, code))
            elif rejected:
                detail = f"{code} (endpoint missing on this build?)" if code == 502 else code
                failed.append((name, tag, detail))
            else:
                passed.append((name, tag, code))

        print(f"\n=== results: {len(passed)} passed, {len(failed)} failed, "
              f"{len(known_fail)} known-fail, {len(skipped)} skipped ===")
        for n, t, c in passed:
            print(f"  PASS [{c}] {t}  ({n})")
        for n, p, why in skipped:
            print(f"  SKIP {p}  ({why})")
        for n, t, c, why in known_fail:
            print(f"  KNOWN-FAIL [{c}] {t}  ({n}) -- {why}")
        for n, t, c in failed:
            print(f"  FAIL [{c}] {t}  ({n})")
        for n, t, c in unexpected_pass:
            print(f"  UNEXPECTEDLY PASSING [{c}] {t}  ({n})")

        if unexpected_pass:
            print("\nFAIL: examples listed in KNOWN_FAILING are now accepted. This is good news -- the"
                  "\n      underlying issue is fixed. Remove them from KNOWN_FAILING in"
                  "\n      scripts/collections/test_collections.py so the ratchet cannot loosen.")
        return 1 if (failed or unexpected_pass) else 0
    finally:
        if container:
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)


if __name__ == "__main__":
    sys.exit(main())
