#!/usr/bin/env python3
"""Launch-to-ready benchmark harness for MockServer startup variants.

Measures, per variant, over N repetitions:
  - t_port:  process launch -> TCP port accepts connections
  - t_ready: process launch -> PUT /mockserver/status returns 200

Variants are defined in a JSON file:
[
  {"name": "baseline", "kind": "java",
   "cmd": ["java", "-jar", "JAR", "-serverPort", "PORT"]},
  {"name": "docker-standard", "kind": "docker",
   "image": "mockserver/mockserver:7.3.0", "args": ["-serverPort", "PORT"]}
]
Placeholders JAR and PORT are substituted. For kind=docker the harness runs
`docker run --rm -d -p PORT:PORT <image> <args>` and measures from the moment
`docker run` is invoked (includes container start overhead, matching what a
Testcontainers user experiences with a pre-pulled image).

Usage: bench_startup.py variants.json [--jar path] [--port 22080] [--runs 5]
Output: per-variant min/median/max table + raw CSV alongside the JSON file.
"""
import http.client
import json
import os
import signal
import socket
import statistics
import subprocess
import sys
import time


def port_open(port, timeout=0.05):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return s.connect_ex(("127.0.0.1", port)) == 0
    finally:
        s.close()


def status_200(port):
    try:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=0.25)
        conn.request("PUT", "/mockserver/status")
        ok = conn.getresponse().status == 200
        conn.close()
        return ok
    except Exception:
        return False


def wait_port_free(port, deadline_s=15):
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        if not port_open(port):
            return True
        time.sleep(0.05)
    return False


def substitute(tokens, jar, port):
    return [t.replace("JAR", jar).replace("PORT", str(port)) for t in tokens]


def run_once(variant, jar, port, timeout_s=60):
    if not wait_port_free(port):
        raise RuntimeError(f"port {port} still occupied before run")
    container_id = None
    if variant.get("kind") == "docker":
        cmd = ["docker", "run", "--rm", "-d", "-p", f"{port}:{port}"]
        cmd += variant.get("docker_opts", [])
        cmd += [variant["image"]] + substitute(variant.get("args", []), jar, port)
    else:
        cmd = substitute(variant["cmd"], jar, port)

    t0 = time.monotonic()
    if variant.get("kind") == "docker":
        out = subprocess.run(cmd, capture_output=True, text=True)
        if out.returncode != 0:
            raise RuntimeError(f"docker run failed: {out.stderr.strip()}")
        container_id = out.stdout.strip()
        proc = None
    else:
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL,
                                start_new_session=True)
    t_port = t_ready = None
    try:
        deadline = t0 + timeout_s
        while time.monotonic() < deadline and t_port is None:
            if proc is not None and proc.poll() is not None:
                raise RuntimeError(f"process exited early rc={proc.returncode}")
            if port_open(port):
                t_port = time.monotonic() - t0
            else:
                time.sleep(0.002)
        while time.monotonic() < deadline and t_ready is None:
            if status_200(port):
                t_ready = time.monotonic() - t0
            else:
                time.sleep(0.002)
        if t_ready is None:
            raise RuntimeError("timed out waiting for readiness")
        return t_port * 1000, t_ready * 1000
    finally:
        if container_id:
            subprocess.run(["docker", "rm", "-f", container_id],
                           capture_output=True)
        elif proc is not None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait()
        wait_port_free(port)


def main():
    variants_file = sys.argv[1]
    args = sys.argv[2:]

    def opt(name, default):
        return args[args.index(name) + 1] if name in args else default

    jar = opt("--jar", "")
    port = int(opt("--port", "22080"))
    runs = int(opt("--runs", "5"))

    with open(variants_file) as f:
        variants = json.load(f)

    csv_path = os.path.splitext(variants_file)[0] + "-results.csv"
    results = {}
    with open(csv_path, "w", buffering=1) as csv:
        csv.write("variant,run,t_port_ms,t_ready_ms\n")
        for v in variants:
            name = v["name"]
            samples = []
            print(f"== {name} ({runs} runs)", flush=True)
            for i in range(runs):
                try:
                    tp, tr = run_once(v, jar, port)
                except RuntimeError as e:
                    print(f"   run {i + 1}: FAILED ({e})", flush=True)
                    csv.write(f"{name},{i + 1},,\n")
                    continue
                samples.append((tp, tr))
                csv.write(f"{name},{i + 1},{tp:.0f},{tr:.0f}\n")
                print(f"   run {i + 1}: port {tp:.0f} ms, ready {tr:.0f} ms",
                      flush=True)
            results[name] = samples

    print(f"\n{'variant':<40}{'runs':>5}{'ready med':>11}{'min':>9}"
          f"{'max':>9}{'port med':>10}")
    for name, samples in results.items():
        if not samples:
            print(f"{name:<40}{'0':>5}{'ALL FAILED':>29}")
            continue
        ports = sorted(s[0] for s in samples)
        readys = sorted(s[1] for s in samples)
        print(f"{name:<40}{len(samples):>5}"
              f"{statistics.median(readys):>9.0f}ms{readys[0]:>7.0f}ms"
              f"{readys[-1]:>7.0f}ms{statistics.median(ports):>8.0f}ms")
    print(f"\nraw: {csv_path}")


if __name__ == "__main__":
    main()
