"""Tests for the shipped pytest plugin (``mockserver.pytest_plugin``)."""

import pytest

from mockserver import pytest_plugin


def _is_pytest_fixture(obj) -> bool:
    # pytest marks fixtures differently across versions: older releases attach a
    # ``_pytestfixturefunction`` marker to the wrapped function, while pytest 8/9
    # return a dedicated ``FixtureFunctionDefinition`` wrapper object.
    return (
        hasattr(obj, "_pytestfixturefunction")
        or "fixture" in type(obj).__name__.lower()
    )


def test_plugin_exposes_expected_fixtures():
    # the plugin must expose the documented fixtures so pip-installed users can
    # request them without any conftest wiring
    for name in ("mockserver", "mockserver_host", "mockserver_port", "mockserver_server"):
        fixture = getattr(pytest_plugin, name)
        assert _is_pytest_fixture(fixture), f"{name} must be a pytest fixture"


def test_find_free_port_returns_valid_port():
    port = pytest_plugin._find_free_port()
    assert isinstance(port, int)
    assert 1 <= port <= 65535


def test_wait_for_mockserver_times_out_on_dead_port():
    dead_port = pytest_plugin._find_free_port()
    with pytest.raises(RuntimeError, match="did not become ready"):
        pytest_plugin._wait_for_mockserver("127.0.0.1", dead_port, timeout=0.5)


@pytest.mark.integration
def test_mockserver_fixture_round_trip(mockserver, mockserver_server):
    """End-to-end check: the fixture yields a live client that mocks + verifies.

    Runs only when a server is reachable (``MOCKSERVER_HOST``/``MOCKSERVER_PORT``)
    or a self-contained binary can be launched.

    Uses the plugin's own ``mockserver_server`` tuple (not ``mockserver_host`` /
    ``mockserver_port``, which a project ``conftest.py`` may legitimately override)
    so the HTTP call targets the very server the ``mockserver`` client is bound to.
    """
    import urllib.request

    from mockserver import HttpRequest, HttpResponse

    host, port = mockserver_server

    mockserver.when(HttpRequest(path="/plugin-check")).respond(
        HttpResponse(status_code=200, body="ok")
    )

    url = f"http://{host}:{port}/plugin-check"
    with urllib.request.urlopen(url, timeout=5) as resp:
        assert resp.status == 200
        assert resp.read().decode("utf-8") == "ok"

    mockserver.verify(HttpRequest(path="/plugin-check"))
