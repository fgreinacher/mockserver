#!/usr/bin/env python3
"""Inter-token delay-error reader for the item-12 streaming scenario.

WHY THIS EXISTS (and why it is NOT k6). Item 12's first metric is a FIDELITY
metric: the distribution of actual-minus-requested inter-token delay. The base
grafana/k6 image buffers an SSE response whole, so it cannot time individual
tokens. This is a tiny, single-threaded, dependency-free reader that opens a SSE
stream and records a monotonic timestamp as each `data:` line arrives, so the
inter-arrival gap between consecutive tokens is measured directly.

HONESTY (the four-orders-of-magnitude trap this programme already hit once). A
client-measured gap = the server's true emission gap + the client's own read/
schedule jitter. To keep the metric about the SERVER, this reader is run TWICE by
perf-test-run.sh: once against an IDLE server (the control) and once WHILE k6
drives the concurrency load (the treatment). The idle run is the client-jitter
FLOOR — on loopback with a single stream and an unloaded reader it is sub-
millisecond. If the idle floor is tight and the loaded distribution is inflated,
the growth is attributable to the SERVER (scheduler-thread starvation delaying
the per-token writeEvent tasks), not to this reader, because the reader is
identical and unloaded in both. The reader deliberately opens only a FEW streams
(sequentially) so it is never itself CPU-starved.

The first inter-arrival gap of each stream is DISCARDED: it folds in TCP connect,
the request, TTFB and the first event's own delay, none of which is an inter-token
gap. Every subsequent gap is governed by the same requested per-event delay.

Output: a JSON object on stdout (or --out FILE) with the gap and error
distributions in milliseconds. Exit non-zero only on a usage/connection error, so
the run step can treat an empty/failed read as a validity failure rather than a
silent zero.
"""
import argparse
import http.client
import json
import math
import ssl
import sys
import time


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    # Nearest-rank: the smallest value >= p% of the data.
    k = min(len(ordered) - 1, int(math.ceil(p / 100.0 * len(ordered)) - 1))
    return ordered[max(0, k)]


def read_one_stream(host, port, path, use_tls, max_tokens, timeout):
    """Open one SSE stream; return the list of inter-arrival gaps in ms with the
    first gap discarded (see module docstring)."""
    if use_tls:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        conn = http.client.HTTPSConnection(host, port, timeout=timeout, context=ctx)
    else:
        conn = http.client.HTTPConnection(host, port, timeout=timeout)
    gaps = []
    try:
        conn.request("GET", path)
        resp = conn.getresponse()
        if resp.status != 200:
            body = resp.read(512)
            raise RuntimeError(
                "stream GET %s returned HTTP %d (%r)" % (path, resp.status, body[:200])
            )
        last = None
        seen = 0
        while True:
            line = resp.readline()
            if not line:
                break
            if line.startswith(b"data:"):
                now = time.monotonic()
                if last is not None:
                    gaps.append((now - last) * 1000.0)
                last = now
                seen += 1
                if seen >= max_tokens:
                    break
    finally:
        conn.close()
    return gaps


def main():
    ap = argparse.ArgumentParser(description="SSE inter-token delay-error reader (item 12)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1080)
    ap.add_argument("--path", default="/stream")
    ap.add_argument("--delay-ms", type=float, required=True,
                    help="the requested per-event delay in ms (error = actual - this)")
    ap.add_argument("--streams", type=int, default=3,
                    help="number of sequential probe streams to read")
    ap.add_argument("--max-tokens", type=int, default=100,
                    help="cap the tokens read per stream (bounds reader wall time)")
    ap.add_argument("--tls", action="store_true", help="use HTTPS")
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--label", default="", help="free-form label echoed into the JSON")
    ap.add_argument("--out", default="", help="write JSON here instead of stdout")
    args = ap.parse_args()

    all_gaps = []
    streams_ok = 0
    errors = []
    for _ in range(args.streams):
        try:
            gaps = read_one_stream(args.host, args.port, args.path, args.tls,
                                   args.max_tokens, args.timeout)
            if gaps:
                all_gaps.extend(gaps)
                streams_ok += 1
        except Exception as exc:  # noqa: BLE001 - report, do not crash the run
            errors.append(str(exc))

    if not all_gaps:
        sys.stderr.write(
            "sse-fidelity-reader: no inter-token gaps captured from %d stream(s); errors=%r\n"
            % (args.streams, errors[:3])
        )
        # Emit a valid JSON with nulls so the run step can record the failure as a
        # validity check rather than crash, but exit non-zero.
        result = {
            "label": args.label, "requested_delay_ms": args.delay_ms,
            "streams_ok": 0, "samples": 0,
            "gap_p50_ms": None, "gap_p95_ms": None, "gap_p99_ms": None,
            "error_p50_ms": None, "error_p95_ms": None, "error_p99_ms": None,
            "error_max_ms": None, "error_mean_ms": None, "errors": errors[:3],
        }
        out = json.dumps(result)
        if args.out:
            with open(args.out, "w") as fh:
                fh.write(out)
        else:
            sys.stdout.write(out + "\n")
        return 2

    errs = [g - args.delay_ms for g in all_gaps]
    result = {
        "label": args.label,
        "requested_delay_ms": args.delay_ms,
        "streams_ok": streams_ok,
        "samples": len(all_gaps),
        "gap_p50_ms": round(percentile(all_gaps, 50), 3),
        "gap_p95_ms": round(percentile(all_gaps, 95), 3),
        "gap_p99_ms": round(percentile(all_gaps, 99), 3),
        "error_p50_ms": round(percentile(errs, 50), 3),
        "error_p95_ms": round(percentile(errs, 95), 3),
        "error_p99_ms": round(percentile(errs, 99), 3),
        "error_max_ms": round(max(errs), 3),
        "error_mean_ms": round(sum(errs) / len(errs), 3),
    }
    out = json.dumps(result)
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(out)
    else:
        sys.stdout.write(out + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
