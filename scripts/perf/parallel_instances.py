#!/usr/bin/env python3
"""Item 17 of the performance programme, CONTAINER shape: N MockServer containers on one host.

The companion to InJvmParallelBench.java (the in-JVM shape). This launches N *separate*
containers — N separate JVMs — and measures the aggregate cost the laptop actually pays, so the
two shapes can be compared directly:

  * in-JVM: one JVM, one baseline (metaspace / GC / JIT), stores sized off the WHOLE host and
    frozen at first read (see InJvmParallelBench + the freeze finding);
  * containers: N JVMs, N baselines, but each `availableProcessors()` is cgroup-aware so each
    instance sizes its pools off ITS OWN limit.

For each N it launches N containers, waits each to genuine readiness (PUT /mockserver/status ==
200 — a listening port is NOT readiness; MockServer accepts then resets during init), then reports:
  * per-container startup (ready ms) — cold first vs warm median, distribution not mean;
  * aggregate RSS (sum of `docker stats` MemUsage) and per-container RSS;
  * per-container live thread count (jvm_threads_current from /mockserver/metrics) — COUNTED;
  * aggregate thread count.

`--cpus` applies a CFS quota per container; `--cpuset` pins each container to a fixed CPU set
(the reliable way to make `availableProcessors()` report a smaller number — verified with
-XshowSettings:system). NOTE: `--cpuset N` pins EVERY container to the SAME cores 0..N-1, so a
multi-container `--cpuset` run demonstrates the cgroup-derived POOL SIZING only — it is not a CPU
isolation model (the containers contend for the same cores). Leave both unset for the
unconstrained profile.

Usage:
  parallel_instances.py --image mockserver/mockserver:7.6.0 --counts 1,4,8,16 [--cpuset 2]
                        [--basePort 28000] [--settle 5] [--out result.json]
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bench_laptop import (  # reuse the CORRECT readiness primitives (status-200, not port-open)
    docker_rm, status_200, wait_port_free, median,
)


def read_threads(port):
    """jvm_threads_current from the Prometheus metrics endpoint (metrics must be enabled)."""
    import http.client
    try:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
        conn.request("GET", "/mockserver/metrics")
        body = conn.getresponse().read().decode("utf-8", "replace")
        conn.close()
    except Exception:
        return None
    for line in body.splitlines():
        if line.startswith("jvm_threads_current"):
            return int(float(line.split()[-1]))
    return None


def read_rss_mb(cid):
    out = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", cid],
        capture_output=True, text=True)
    m = re.match(r"\s*([0-9.]+)\s*([KMG]i?B)", out.stdout)
    if not m:
        return None
    val, unit = float(m.group(1)), m.group(2)
    factor = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024,
              "KB": 1 / 1024, "MB": 1, "GB": 1024}.get(unit, 1)
    return round(val * factor, 1)


def launch_container(image, port, cpuset=None, cpus=None, mem=None):
    cmd = ["docker", "run", "--rm", "-d", "-p", f"{port}:{port}"]
    if cpuset:
        cmd += [f"--cpuset-cpus=0-{int(cpuset) - 1}"]
    if cpus:
        cmd += [f"--cpus={cpus}"]
    if mem:
        cmd += [f"--memory={mem}", f"--memory-swap={mem}"]
    cmd += ["-e", "MOCKSERVER_METRICS_ENABLED=true",
            image, "-serverPort", str(port), "-logLevel", "WARN"]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"docker run failed: {out.stderr.strip()}")
    return out.stdout.strip()


def measure_n(image, n, base_port, cpuset, cpus, mem, settle_s):
    print(f"\n-- N={n} containers "
          f"(cpuset={cpuset or 'none'}, cpus={cpus or 'none'}, mem={mem or 'none'}) --",
          file=sys.stderr, flush=True)
    cids, ports, readys = [], [], []
    t_all0 = time.monotonic()
    try:
        for i in range(n):
            p = base_port + i
            if not wait_port_free(p):
                raise RuntimeError(f"port {p} occupied")
            t0 = time.monotonic()
            cid = launch_container(image, p, cpuset=cpuset, cpus=cpus, mem=mem)
            deadline = t0 + 90
            ready = None
            while time.monotonic() < deadline:
                if status_200(p):
                    ready = (time.monotonic() - t0) * 1000
                    break
                time.sleep(0.002)
            if ready is None:
                raise RuntimeError(f"container {i} (port {p}) never became ready")
            cids.append(cid)
            ports.append(p)
            readys.append(ready)
            if i == 0 or i == n - 1 or n <= 4:
                print(f"   container {i:2d}: ready {ready:6.0f} ms", file=sys.stderr, flush=True)
        wall_all_up = (time.monotonic() - t_all0) * 1000

        # settle then sample footprint with all N up
        time.sleep(settle_s)
        rss = [read_rss_mb(c) for c in cids]
        threads = [read_threads(p) for p in ports]
        rss_ok = [r for r in rss if r is not None]
        thr_ok = [t for t in threads if t is not None]
        agg_rss = round(sum(rss_ok), 1) if rss_ok else None
        agg_thr = sum(thr_ok) if thr_ok else None
        print(f"   ALL {n} UP: wall-to-all-ready {wall_all_up:.0f} ms | "
              f"aggregate RSS {agg_rss} MiB ({agg_rss / n:.1f}/container) | "
              f"per-container threads {min(thr_ok)}..{max(thr_ok)} (median {int(median(thr_ok))}), "
              f"aggregate {agg_thr}", file=sys.stderr, flush=True)

        warm = readys[1:] if len(readys) > 1 else readys
        return {
            "n": n,
            "cold_ready_ms": round(readys[0], 1),
            "warm_ready_median_ms": round(median(warm), 1),
            "warm_ready_max_ms": round(max(warm), 1),
            "wall_all_ready_ms": round(wall_all_up, 1),
            "agg_rss_mb": agg_rss,
            "rss_mb_per_container": round(agg_rss / n, 1) if agg_rss else None,
            "threads_per_container_min": min(thr_ok) if thr_ok else None,
            "threads_per_container_max": max(thr_ok) if thr_ok else None,
            "threads_per_container_median": int(median(thr_ok)) if thr_ok else None,
            "agg_threads": agg_thr,
        }
    finally:
        for c in cids:
            docker_rm(c)
        for p in ports:
            wait_port_free(p)


def opt(args, name, default=None):
    return args[args.index(name) + 1] if name in args else default


def main():
    args = sys.argv[1:]
    image = opt(args, "--image", "mockserver/mockserver:7.6.0")
    counts = [int(x) for x in opt(args, "--counts", "1,4,8,16").split(",")]
    base_port = int(opt(args, "--basePort", "28000"))
    cpuset = opt(args, "--cpuset", None)
    cpus = opt(args, "--cpus", None)
    mem = opt(args, "--mem", None)
    settle_s = int(opt(args, "--settle", "5"))

    print(f"== container shape: {image}, counts={counts}, "
          f"cpuset={cpuset}, cpus={cpus}, mem={mem}", file=sys.stderr, flush=True)
    runs = []
    port = base_port
    for n in counts:
        runs.append(measure_n(image, n, port, cpuset, cpus, mem, settle_s))
        port += n + 5
    block = {"container": {"image": image, "cpuset": cpuset, "cpus": cpus,
                           "mem": mem, "runs": runs}}
    out = opt(args, "--out", None)
    if out:
        with open(out, "w") as f:
            json.dump(block, f, indent=2)
        print(f"wrote {out}", file=sys.stderr)
    print(json.dumps(block, indent=2))


if __name__ == "__main__":
    main()
