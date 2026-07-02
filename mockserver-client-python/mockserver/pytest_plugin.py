"""pytest plugin providing a ready-to-use ``mockserver`` fixture.

This mirrors the Ruby RSpec helper (``mockserver-client-ruby/lib/mockserver/rspec.rb``):
require nothing in your test code beyond the ``mockserver`` fixture and you get a
fresh, reset :class:`~mockserver.MockServerClient` per test.

Registration
------------
The plugin is registered as a pytest entry point (``pytest11``) in
``pyproject.toml``, so once ``mockserver-client`` is installed the fixtures below
are available automatically to any test that requests them - no ``conftest.py``
wiring required.

Server resolution
-----------------
* If ``MOCKSERVER_HOST`` **and** ``MOCKSERVER_PORT`` are set the fixtures connect
  to that already-running server (nothing is started or stopped).
* Otherwise a self-contained MockServer binary is downloaded on first use and
  started on a free port for the duration of the test session, then stopped.
  Set ``MOCKSERVER_SKIP_BINARY_DOWNLOAD=1`` to fail fast instead of downloading
  (air-gapped CI with a pre-seeded cache).

Usage
-----
::

    def test_records_the_request(mockserver):
        from mockserver import HttpRequest, HttpResponse
        mockserver.when(
            HttpRequest(path="/hello")
        ).respond(HttpResponse(body="world"))

The ``mockserver`` fixture is function-scoped and resets the server before and
after every test, so recorded requests, expectations and logs never leak between
tests. ``mockserver_host`` / ``mockserver_port`` are also exposed if you need the
raw connection details.
"""

from __future__ import annotations

import os
import socket
import time
import urllib.request

import pytest


def _find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _wait_for_mockserver(host: str, port: int, timeout: float = 60.0) -> None:
    """Block until MockServer answers its status endpoint, or raise on timeout."""
    url = f"http://{host}:{port}/mockserver/status"
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            req = urllib.request.Request(url, method="PUT")
            with urllib.request.urlopen(req, timeout=2) as resp:
                if resp.status == 200:
                    return
        except Exception as exc:  # noqa: BLE001 - retry on any connection error
            last_error = exc
            time.sleep(0.5)
    raise RuntimeError(
        f"MockServer did not become ready on {host}:{port} within {timeout}s"
        + (f" (last error: {last_error})" if last_error else "")
    )


@pytest.fixture(scope="session")
def mockserver_server():
    """Session-scoped MockServer endpoint as an ``(host, port)`` tuple.

    Connects to an external server when ``MOCKSERVER_HOST``/``MOCKSERVER_PORT``
    are set, otherwise launches a self-contained binary for the session.
    """
    env_host = os.environ.get("MOCKSERVER_HOST")
    env_port = os.environ.get("MOCKSERVER_PORT")

    if env_host and env_port:
        host, port = env_host, int(env_port)
        _wait_for_mockserver(host, port)
        yield host, port
        return

    # No external server configured - launch a self-contained binary.
    from mockserver.launcher import start as start_server

    host = "127.0.0.1"
    port = _find_free_port()
    process = start_server(port, log=False)
    try:
        _wait_for_mockserver(host, port)
        yield host, port
    finally:
        process.stop()


@pytest.fixture()
def mockserver_host(mockserver_server) -> str:
    """The host MockServer is reachable on for the current test."""
    return mockserver_server[0]


@pytest.fixture()
def mockserver_port(mockserver_server) -> int:
    """The port MockServer is reachable on for the current test."""
    return mockserver_server[1]


@pytest.fixture()
def mockserver(mockserver_server):
    """A fresh, reset :class:`~mockserver.MockServerClient` per test.

    The server is reset before and after each test so state never leaks between
    tests; the underlying WebSocket connection is closed on teardown.
    """
    from mockserver import MockServerClient

    host, port = mockserver_server
    with MockServerClient(host, port) as client:
        client.reset()
        try:
            yield client
        finally:
            client.reset()
