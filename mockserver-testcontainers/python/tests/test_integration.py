"""Integration tests for MockServerContainer.

These tests require a running Docker daemon. They are skipped when Docker is
unavailable, mirroring the Java repo's assumeTrue(isDockerAvailable()) convention.
"""

import pytest

docker_available = False
try:
    import docker

    client = docker.from_env()
    client.ping()
    docker_available = True
except Exception:
    pass

pytestmark = pytest.mark.docker


@pytest.fixture(scope="module")
def mockserver():
    """Start a MockServer container for the module's integration tests."""
    if not docker_available:
        pytest.skip("Docker is not available")

    from testcontainers_mockserver import MockServerContainer

    with MockServerContainer() as container:
        yield container


def test_container_starts_and_is_reachable(mockserver):
    """Verify the container starts and the /mockserver/status endpoint responds."""
    import requests

    url = mockserver.get_url()
    assert url.startswith("http://")

    # MockServer's status endpoint is PUT-only
    response = requests.put(f"{url}/mockserver/status")
    assert response.status_code == 200

    # Emit a stable, intentional evidence marker. The CI step
    # (python-testcontainers-test.sh) greps for this line to fail closed: it
    # proves a real container started and answered /mockserver/status, rather
    # than trusting pytest's exit code (the fixture calls pytest.skip when Docker
    # is unavailable, so a skip would otherwise read as a green pass).
    print(f"PYTHON_TESTCONTAINERS_STATUS_OK url={url} status={response.status_code}")

    body = response.json()
    assert "ports" in body


def test_get_host_returns_non_empty(mockserver):
    """Verify get_host returns a non-empty string."""
    host = mockserver.get_host()
    assert host
    assert isinstance(host, str)


def test_get_port_returns_positive_int(mockserver):
    """Verify get_port returns a positive integer."""
    port = mockserver.get_port()
    assert isinstance(port, int)
    assert port > 0


def test_expectation_lifecycle(mockserver):
    """Verify a basic create-expectation/match/verify lifecycle."""
    import json

    import requests

    url = mockserver.get_url()

    # Create an expectation
    expectation = {
        "httpRequest": {
            "method": "GET",
            "path": "/hello",
        },
        "httpResponse": {
            "statusCode": 200,
            "body": "world",
        },
    }
    resp = requests.put(f"{url}/mockserver/expectation", json=expectation)
    assert resp.status_code == 201

    # Match the expectation
    resp = requests.get(f"{url}/hello")
    assert resp.status_code == 200
    assert resp.text == "world"

    # Verify the request was received
    verify_body = {
        "httpRequest": {
            "method": "GET",
            "path": "/hello",
        },
        "times": {
            "atLeast": 1,
        },
    }
    resp = requests.put(f"{url}/mockserver/verify", json=verify_body)
    assert resp.status_code == 202

    # Reset
    resp = requests.put(f"{url}/mockserver/reset")
    assert resp.status_code == 200
