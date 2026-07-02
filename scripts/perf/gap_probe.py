#!/usr/bin/env python3
"""Decompose the port-open -> ready gap: is the first request slow (server-side
one-time warmup) or is it many fast-failing requests (server not accepting)?

Launches the jar, waits for TCP accept, then issues sequential PUT
/mockserver/status requests with a generous timeout, timing each.

Port 22082 is hardcoded (see README.md).
"""
import http.client
import os
import signal
import socket
import subprocess
import sys
import time

JAR = sys.argv[1]
PORT = 22082
RUNS = 3
LAUNCH_DEADLINE_S = 30


def port_open(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.05)
    try:
        return s.connect_ex(("127.0.0.1", port)) == 0
    finally:
        s.close()


def kill_group(proc):
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    proc.wait()


for run in range(RUNS):
    free_deadline = time.monotonic() + LAUNCH_DEADLINE_S
    while port_open(PORT):
        if time.monotonic() > free_deadline:
            sys.exit(f"port {PORT} still occupied after {LAUNCH_DEADLINE_S}s "
                     "- is a stale process holding it?")
        time.sleep(0.05)
    proc = subprocess.Popen(
        ["java", "-jar", JAR, "-serverPort", str(PORT), "-logLevel", "WARN"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True)
    t0 = time.monotonic()
    try:
        while not port_open(PORT):
            if proc.poll() is not None:
                sys.exit(f"JVM exited before binding (rc={proc.returncode})")
            if time.monotonic() - t0 > LAUNCH_DEADLINE_S:
                sys.exit(f"port {PORT} not bound after {LAUNCH_DEADLINE_S}s")
            time.sleep(0.002)
        t_port = (time.monotonic() - t0) * 1000
        times = []
        for i in range(4):
            t1 = time.monotonic()
            try:
                conn = http.client.HTTPConnection("127.0.0.1", PORT,
                                                  timeout=10)
                conn.request("PUT", "/mockserver/status")
                status = conn.getresponse().status
                conn.close()
            except Exception as e:
                status = f"ERR {type(e).__name__}"
            times.append(((time.monotonic() - t1) * 1000, status))
        print(f"run {run + 1}: port {t_port:.0f} ms; requests: "
              + ", ".join(f"#{i + 1} {t:.0f} ms ({s})"
                          for i, (t, s) in enumerate(times)))
    finally:
        kill_group(proc)
