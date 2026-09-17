#!/usr/bin/env python3
"""Laptop-profile startup and footprint harness — performance-programme item 8.

Measures the four sub-items and emits a single `laptop` result block (schema
matches perf-test-run.sh's `behaviours`: {variant: {metric: value}}) that
perf-test-compare.sh consumes NON-GATING once the `laptop.*` budget wildcards
exist in perf-budgets.json:

  8a  docker run -> first PUT /mockserver/status == 200, median of 9 after
      discarding one warm-up launch; plus idle RSS + thread count 30 s after
      ready at --memory 256m / 512m / 1g.
  8b  the IN-JVM path (ClientAndServer.startClientAndServer) — delegated to
      InJvmStartupBench.java. This is the number the "500 test classes" mandate
      asks for; a suite pays it 500 times and the docker cost zero times.
  8c  in-JVM startup with initializationJsonPath at 0 / 1,000 / 10,000
      expectations.
  8d  compressed image size (docker save | gzip | wc -c) — a deterministic
      counter the median-of-9 (pre-pulled) can never see.

CRITICAL: readiness is `PUT /mockserver/status` == 200, NEVER an open TCP port.
MockServer accepts a connection and then RESETS it during initialisation, so a
port-open probe reports ready far too early. `readiness-demo` proves the gap.

Usage:
  bench_laptop.py all       --jar JAR --image IMAGE [--out laptop-result.json]
  bench_laptop.py ready     --image IMAGE [--runs 9 --warmups 1]
  bench_laptop.py footprint --image IMAGE [--settle 30]
  bench_laptop.py initscale --jar JAR
  bench_laptop.py imagesize --image IMAGE [--image IMAGE ...]
  bench_laptop.py readiness-demo --image IMAGE
"""
import http.client
import json
import os
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))


# ---------- readiness primitives (shared) ------------------------------------

def port_open(port, host="127.0.0.1", timeout=0.05):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return s.connect_ex((host, port)) == 0
    finally:
        s.close()


def status_200(port, host="127.0.0.1"):
    try:
        conn = http.client.HTTPConnection(host, port, timeout=0.25)
        conn.request("PUT", "/mockserver/status")
        ok = conn.getresponse().status == 200
        conn.close()
        return ok
    except Exception:
        return False


def wait_port_free(port, deadline_s=20):
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        if not port_open(port):
            return True
        time.sleep(0.05)
    return False


def median(xs):
    return statistics.median(xs) if xs else None


# ---------- docker launch ----------------------------------------------------

def docker_run(image, port, mem=None, metrics=False, extra_args=None, volumes=None):
    cmd = ["docker", "run", "--rm", "-d", "-p", f"{port}:{port}"]
    if mem:
        cmd += [f"--memory={mem}", f"--memory-swap={mem}"]
    if metrics:
        cmd += ["-e", "MOCKSERVER_METRICS_ENABLED=true"]
    for v in (volumes or []):
        cmd += ["-v", v]
    cmd += [image, "-serverPort", str(port), "-logLevel", "WARN"]
    cmd += (extra_args or [])
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"docker run failed: {out.stderr.strip()}")
    return out.stdout.strip()


def docker_rm(cid):
    subprocess.run(["docker", "rm", "-f", cid], capture_output=True)


def launch_to_ready(image, port, mem=None, metrics=False, timeout_s=90,
                    volumes=None):
    """Return (t_port_ms, t_ready_ms, container_id). Caller removes the container."""
    if not wait_port_free(port):
        raise RuntimeError(f"port {port} still occupied before run")
    t0 = time.monotonic()
    cid = docker_run(image, port, mem=mem, metrics=metrics, volumes=volumes)
    t_port = t_ready = None
    deadline = t0 + timeout_s
    while time.monotonic() < deadline and t_port is None:
        if port_open(port):
            t_port = (time.monotonic() - t0) * 1000
        else:
            time.sleep(0.002)
    while time.monotonic() < deadline and t_ready is None:
        if status_200(port):
            t_ready = (time.monotonic() - t0) * 1000
        else:
            time.sleep(0.002)
    if t_ready is None:
        docker_rm(cid)
        raise RuntimeError("timed out waiting for readiness (status 200)")
    return t_port, t_ready, cid


# ---------- 8a: docker ready median ------------------------------------------

def measure_ready(image, port, warmups, runs):
    print(f"== 8a docker ready: {warmups} warm-up + {runs} measured launches "
          f"({image})", file=sys.stderr, flush=True)
    ports, readys = [], []
    for i in range(warmups + runs):
        tp, tr, cid = launch_to_ready(image, port)
        docker_rm(cid)
        wait_port_free(port)
        warm = i >= warmups
        tag = f"measured {i - warmups + 1}" if warm else "warm-up"
        print(f"   launch {i + 1:2d} ({tag:>10}): port {tp:6.0f} ms, "
              f"ready {tr:6.0f} ms", file=sys.stderr, flush=True)
        if warm:
            ports.append(tp)
            readys.append(tr)
    return {
        "ready_ms": round(median(readys), 1),
        "ready_min_ms": round(min(readys), 1),
        "ready_max_ms": round(max(readys), 1),
        "port_open_ms": round(median(ports), 1),
        "measured": len(readys),
    }


# ---------- 8a: idle footprint at memory limits ------------------------------

def read_rss_mb(cid):
    out = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", cid],
        capture_output=True, text=True)
    # e.g. "123.4MiB / 256MiB"
    m = re.match(r"\s*([0-9.]+)\s*([KMG]i?B)", out.stdout)
    if not m:
        return None
    val, unit = float(m.group(1)), m.group(2)
    factor = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024,
              "KB": 1 / 1024, "MB": 1, "GB": 1024}.get(unit, 1)
    return round(val * factor, 1)


def read_threads(port):
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


def measure_footprint(image, port, settle_s):
    result = {}
    for mem in ("256m", "512m", "1g"):
        key = "mem_" + mem
        print(f"== 8a footprint @ --memory={mem}: launch, wait ready, settle "
              f"{settle_s}s ({image})", file=sys.stderr, flush=True)
        tp, tr, cid = launch_to_ready(image, port, mem=mem, metrics=True)
        try:
            time.sleep(settle_s)
            rss = read_rss_mb(cid)
            threads = read_threads(port)
            print(f"   {mem}: ready {tr:.0f} ms, RSS {rss} MiB, "
                  f"threads {threads}", file=sys.stderr, flush=True)
            result[key] = {"rss_mb": rss, "threads": threads,
                           "ready_ms": round(tr, 1)}
        finally:
            docker_rm(cid)
            wait_port_free(port)
    return result


# ---------- 8b: in-JVM (delegate to Java harness) ----------------------------

def run_injvm(jar, port, warmups, runs, init=None, label="injvm"):
    cmd = ["java", "-cp", jar, os.path.join(HERE, "InJvmStartupBench.java"),
           "--port", str(port), "--warmups", str(warmups), "--runs", str(runs),
           "--label", label]
    if init:
        cmd += ["--init", init]
    out = subprocess.run(cmd, capture_output=True, text=True)
    sys.stderr.write(out.stderr)
    for line in out.stdout.splitlines():
        if line.startswith("BENCH_JSON "):
            return json.loads(line[len("BENCH_JSON "):])
    raise RuntimeError(f"in-JVM harness produced no BENCH_JSON (rc={out.returncode})")


# ---------- 8c: init-file scale ----------------------------------------------

def make_init_file(n):
    """Write an initializationJsonPath file with n simple expectations."""
    exps = [{
        "httpRequest": {"method": "GET", "path": f"/item/{i}"},
        "httpResponse": {"statusCode": 200,
                         "body": f"item-{i}"},
    } for i in range(n)]
    fd, path = tempfile.mkstemp(prefix=f"init-{n}-", suffix=".json")
    with os.fdopen(fd, "w") as f:
        json.dump(exps, f)
    return path


def measure_initscale(jar, port, warmups, runs):
    result = {}
    for n in (0, 1000, 10000):
        path = make_init_file(n)
        try:
            print(f"== 8c in-JVM startup with {n} pre-loaded expectations",
                  file=sys.stderr, flush=True)
            b = run_injvm(jar, port, warmups, runs, init=path, label=f"init_{n}")
            result[f"init_{n}"] = {
                "ready_ms": b["ready_median_ms"],
                "ready_min_ms": b["ready_min_ms"],
                "ready_max_ms": b["ready_max_ms"],
                "cold_ready_ms": b["first_ready_ms"],
                "expectations": n,
            }
        finally:
            os.unlink(path)
        port += runs + warmups + 5
    return result


# ---------- 8d: compressed image size ----------------------------------------

def measure_imagesize(images):
    result = {}
    for image in images:
        print(f"== 8d compressed image size: docker save | gzip | wc -c "
              f"({image})", file=sys.stderr, flush=True)
        save = subprocess.Popen(["docker", "save", image], stdout=subprocess.PIPE)
        gz = subprocess.Popen(["gzip", "-c"], stdin=save.stdout,
                              stdout=subprocess.PIPE)
        save.stdout.close()
        data = gz.communicate()[0]
        save.wait()
        n = len(data)
        key = image.replace("/", "_").replace(":", "_")
        # inspect uncompressed for context
        insp = subprocess.run(
            ["docker", "image", "inspect", "--format", "{{.Size}}", image],
            capture_output=True, text=True)
        uncompressed = int(insp.stdout.strip()) if insp.returncode == 0 else None
        print(f"   {image}: compressed {n} bytes ({n/1e6:.1f} MB), "
              f"uncompressed {uncompressed}", file=sys.stderr, flush=True)
        result[key] = {"compressed_bytes": n, "uncompressed_bytes": uncompressed,
                       "image": image}
    return result


# ---------- readiness demonstration ------------------------------------------

def readiness_demo(image, port):
    """Prove port-open and status-200 give materially different answers."""
    print(f"== readiness demo: port-open vs PUT /mockserver/status ({image})",
          file=sys.stderr, flush=True)
    if not wait_port_free(port):
        raise RuntimeError(f"port {port} occupied")
    t0 = time.monotonic()
    cid = docker_run(image, port)
    try:
        t_port = None
        while t_port is None:
            if port_open(port):
                t_port = (time.monotonic() - t0) * 1000
            else:
                time.sleep(0.001)
        # From the instant the port accepts, probe BOTH and record when each first
        # says "ready". Also count how many status probes fail after the port opened.
        status_ready = None
        fails_after_port = 0
        while status_ready is None:
            if status_200(port):
                status_ready = (time.monotonic() - t0) * 1000
            else:
                fails_after_port += 1
                time.sleep(0.001)
        gap = status_ready - t_port
        print(f"   port-open probe says READY at   {t_port:7.0f} ms", file=sys.stderr)
        print(f"   status-200 probe says READY at  {status_ready:7.0f} ms", file=sys.stderr)
        print(f"   GAP (port-open is early by):    {gap:7.0f} ms", file=sys.stderr)
        print(f"   status probes that FAILED after the port was already open: "
              f"{fails_after_port}", file=sys.stderr)
        return {"port_open_ms": round(t_port, 1),
                "status_200_ms": round(status_ready, 1),
                "gap_ms": round(gap, 1),
                "status_fails_after_port_open": fails_after_port}
    finally:
        docker_rm(cid)
        wait_port_free(port)


# ---------- CLI --------------------------------------------------------------

def opt(args, name, default=None):
    return args[args.index(name) + 1] if name in args else default


def opts(args, name):
    vals = []
    for i, a in enumerate(args):
        if a == name and i + 1 < len(args):
            vals.append(args[i + 1])
    return vals


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    cmd = sys.argv[1]
    args = sys.argv[2:]
    jar = opt(args, "--jar", "")
    image = opt(args, "--image", "mockserver/mockserver:7.6.0")
    port = int(opt(args, "--port", "23080"))
    warmups = int(opt(args, "--warmups", "1"))
    runs = int(opt(args, "--runs", "9"))

    if cmd == "ready":
        print(json.dumps(measure_ready(image, port, warmups, runs), indent=2))
    elif cmd == "footprint":
        print(json.dumps(measure_footprint(image, port, int(opt(args, "--settle", "30"))), indent=2))
    elif cmd == "initscale":
        print(json.dumps(measure_initscale(jar, port, warmups, runs), indent=2))
    elif cmd == "imagesize":
        imgs = opts(args, "--image") or [image]
        print(json.dumps(measure_imagesize(imgs), indent=2))
    elif cmd == "readiness-demo":
        print(json.dumps(readiness_demo(image, port), indent=2))
    elif cmd == "all":
        laptop = {}
        # The in-JVM sub-items (8b/8c) need BOTH a readable shaded jar and a `java`
        # on PATH; the docker sub-items (8a/8d) need only docker + the image. In CI
        # the jar is docker-cp'd from the image and java may be absent on the perf
        # agent, so run the in-JVM parts only when both are present and SKIP them
        # (loudly, not fatally) otherwise — a partial laptop block is better than
        # none, and every emitted metric still resolves to a laptop.* budget key.
        have_java = shutil.which("java") is not None
        jar_ok = bool(jar) and os.path.isfile(jar)
        if jar_ok and have_java:
            try:  # source-launch needs a full JDK (javac); a JRE-only agent fails here
                b = run_injvm(jar, port, warmups, runs, label="injvm")
                laptop["injvm"] = {"ready_ms": b["ready_median_ms"],
                                   "ready_min_ms": b["ready_min_ms"],
                                   "ready_max_ms": b["ready_max_ms"],
                                   "cold_ready_ms": b["first_ready_ms"],
                                   "call_ms": b["call_median_ms"]}
                laptop.update(measure_initscale(jar, port + 80, warmups, runs))
            except Exception as e:
                print(f"SKIP in-JVM sub-items 8b/8c (harness failed, likely JRE-only "
                      f"agent — source-launch needs a JDK): {e}", file=sys.stderr, flush=True)
        else:
            print(f"SKIP in-JVM sub-items 8b/8c: jar={'ok' if jar_ok else 'missing'}, "
                  f"java={'present' if have_java else 'absent'}", file=sys.stderr, flush=True)
        laptop["docker_ready"] = measure_ready(image, port + 40, warmups, runs)
        laptop.update(measure_footprint(image, port + 60, int(opt(args, "--settle", "30"))))
        # Pin the image variant to a STABLE key ("image") so the metric name does not
        # change when the tag bumps — a tag-derived key would reset its baseline every
        # release. (The standalone `imagesize` command keeps per-image keys for A/B.)
        laptop["image"] = list(measure_imagesize([image]).values())[0]
        block = {"laptop": laptop}
        out = opt(args, "--out", "")
        if out:
            with open(out, "w") as f:
                json.dump(block, f, indent=2)
            print(f"wrote {out}", file=sys.stderr)
        print(json.dumps(block, indent=2))
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
