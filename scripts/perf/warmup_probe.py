#!/usr/bin/env python3
"""Validate startupWarmup: time an external request issued 600ms after
port-open (simulating a realistic wait-strategy poll interval), with warmup
on vs off. Expect: on = a few ms, off = hundreds of ms.

Port 22083 is hardcoded (see README.md).
"""
import http.client
import os
import signal
import socket
import subprocess
import sys
import time

JAR = sys.argv[1]
PORT = 22083
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


def run(label, extra_args):
    for i in range(3):
        free_deadline = time.monotonic() + LAUNCH_DEADLINE_S
        while port_open(PORT):
            if time.monotonic() > free_deadline:
                sys.exit(f"port {PORT} still occupied after "
                         f"{LAUNCH_DEADLINE_S}s - stale process holding it?")
            time.sleep(0.05)
        cmd = ["java"] + extra_args + ["-jar", JAR, "-serverPort", str(PORT),
                                       "-logLevel", "WARN"]
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL,
                                start_new_session=True)
        t0 = time.monotonic()
        try:
            while not port_open(PORT):
                if proc.poll() is not None:
                    sys.exit("JVM exited before binding "
                             f"(rc={proc.returncode})")
                if time.monotonic() - t0 > LAUNCH_DEADLINE_S:
                    sys.exit(f"port {PORT} not bound after "
                             f"{LAUNCH_DEADLINE_S}s")
                time.sleep(0.002)
            time.sleep(0.6)
            t1 = time.monotonic()
            try:
                conn = http.client.HTTPConnection("127.0.0.1", PORT,
                                                  timeout=10)
                conn.request("PUT", "/mockserver/status")
                status = conn.getresponse().status
                conn.close()
            except Exception as e:
                status = f"ERR {type(e).__name__}"
            ms = (time.monotonic() - t1) * 1000
            print(f"{label} run {i + 1}: request-after-600ms = "
                  f"{ms:.0f} ms ({status})")
        finally:
            kill_group(proc)


run("warmup-ON (default)", [])
run("warmup-OFF", ["-Dmockserver.startupWarmup=false"])
