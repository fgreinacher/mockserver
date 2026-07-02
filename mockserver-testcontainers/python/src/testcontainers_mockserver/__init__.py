"""Testcontainers module for MockServer.

Provides a ``MockServerContainer`` that starts a ``mockserver/mockserver`` Docker
image, waits for readiness, and exposes convenience accessors for the mapped
host, port, and base URL.

Example::

    from testcontainers_mockserver import MockServerContainer

    with MockServerContainer() as mockserver:
        url = mockserver.get_url()
        # url is e.g. "http://localhost:49152"
        # Use requests or any HTTP client to interact with MockServer at this URL.
"""

from importlib import metadata

from testcontainers_mockserver.container import MockServerContainer

__all__ = ["MockServerContainer"]

try:
    __version__ = metadata.version("testcontainers-mockserver")
except metadata.PackageNotFoundError:  # pragma: no cover - source tree without install
    __version__ = "0.0.0"
