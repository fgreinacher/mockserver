"""MockServerContainer — Testcontainers module for MockServer.

This module provides a thin wrapper around testcontainers' ``DockerContainer`` that
starts a ``mockserver/mockserver`` image, waits for readiness via the
``/mockserver/status`` HTTP endpoint (PUT, returns 200), and exposes convenience
methods for the mapped host, port, and URL.
"""

from __future__ import annotations

from importlib import metadata

from testcontainers.core.container import DockerContainer
from testcontainers.core.wait_strategies import HttpWaitStrategy

#: Default port MockServer listens on inside the container (HTTP, HTTPS, SOCKS,
#: and HTTP CONNECT all served on this single unified port).
MOCKSERVER_PORT = 1080

#: Default Docker image name for MockServer.
_IMAGE_NAME = "mockserver/mockserver"


def _default_tag() -> str:
    """Derive the default image tag from the installed MockServer client version.

    Mirrors the Java module: the tag is ``mockserver-<client-version>`` so the
    container image stays in lockstep with the ``mockserver-client`` package.
    Falls back to ``latest`` when the client version cannot be resolved (e.g. the
    client is not installed), matching the Java ``:latest`` fallback.
    """
    try:
        return "mockserver-" + metadata.version("mockserver-client")
    except metadata.PackageNotFoundError:
        return "latest"


#: Default image tag, derived from the installed MockServer client version.
_DEFAULT_TAG = _default_tag()


class MockServerContainer(DockerContainer):
    """A Testcontainers wrapper that starts a MockServer Docker container.

    The container starts the ``mockserver/mockserver`` image on port 1080 and waits
    for the ``PUT /mockserver/status`` endpoint to return HTTP 200 before yielding
    control.

    Parameters
    ----------
    image : str, optional
        Full Docker image reference. Defaults to
        ``mockserver/mockserver:mockserver-7.0.0``.
    port : int, optional
        The port MockServer listens on inside the container. Defaults to 1080.

    Example
    -------
    ::

        from testcontainers_mockserver import MockServerContainer

        with MockServerContainer() as server:
            url = server.get_url()
            # e.g. "http://localhost:49152"
    """

    def __init__(
        self,
        image: str = f"{_IMAGE_NAME}:{_DEFAULT_TAG}",
        port: int = MOCKSERVER_PORT,
        **kwargs,
    ) -> None:
        super().__init__(image=image, **kwargs)
        self._port = port
        self._client = None
        self.with_exposed_ports(self._port)
        self.with_env("SERVER_PORT", str(self._port))

    def _build_wait_strategy(self) -> HttpWaitStrategy:
        """Build the readiness wait strategy.

        MockServer's ``/mockserver/status`` endpoint requires a PUT request and
        returns HTTP 200 with a JSON body containing ``{ "ports": [...] }`` when
        the server is ready.
        """
        return (
            HttpWaitStrategy(self._port, "/mockserver/status")
            .with_method("PUT")
            .with_startup_timeout(60)
        )

    def start(self) -> "MockServerContainer":
        """Start the container and wait for MockServer to become ready."""
        super().start()
        self._build_wait_strategy().wait_until_ready(self)
        return self

    # ------------------------------------------------------------------
    # Convenience accessors
    # ------------------------------------------------------------------

    def get_host(self) -> str:
        """Return the host IP to connect to MockServer from the test process."""
        return self.get_container_host_ip()

    def get_port(self) -> int:
        """Return the mapped host port for MockServer's container port."""
        return int(self.get_exposed_port(self._port))

    def get_url(self) -> str:
        """Return the HTTP base URL for MockServer (e.g. ``http://localhost:49152``)."""
        return f"http://{self.get_host()}:{self.get_port()}"

    def get_secure_url(self) -> str:
        """Return the HTTPS base URL for MockServer (same port, different scheme)."""
        return f"https://{self.get_host()}:{self.get_port()}"

    def get_client(self):
        """Return a ``MockServerClient`` connected to this container.

        The client is created lazily on first call and cached. Mirrors the Java
        module's ``getClient()``. Requires the ``mockserver-client`` package,
        which is declared as a dependency of this module.

        :returns: a ``mockserver.MockServerClient`` pointed at the mapped host/port
        """
        if self._client is None:
            from mockserver import MockServerClient

            self._client = MockServerClient(self.get_host(), self.get_port())
        return self._client

    # ------------------------------------------------------------------
    # Configuration helpers (fluent)
    # ------------------------------------------------------------------

    def with_server_port(self, port: int) -> "MockServerContainer":
        """Override the MockServer listen port inside the container.

        This replaces the exposed port so the wait strategy targets the correct port.
        """
        self._port = port
        # Reset exposed ports to only the new port
        self.ports = {}
        self.with_exposed_ports(port)
        self.with_env("SERVER_PORT", str(port))
        return self

    def with_log_level(self, level: str) -> "MockServerContainer":
        """Set the MockServer log level (e.g. INFO, DEBUG, WARN, ERROR, TRACE)."""
        self.with_env("MOCKSERVER_LOG_LEVEL", level)
        return self

    def with_property(self, key: str, value: str) -> "MockServerContainer":
        """Set a MockServer configuration property as an environment variable.

        The key must be in MockServer env-var form (e.g. ``MOCKSERVER_LOG_LEVEL``).
        """
        self.with_env(key, value)
        return self

    def with_initialization_json(self, container_path: str) -> "MockServerContainer":
        """Configure MockServer to load expectations from a JSON file at startup.

        The file must already be mounted/copied into the container at *container_path*.
        This sets the ``MOCKSERVER_INITIALIZATION_JSON_PATH`` environment variable.
        """
        self.with_env("MOCKSERVER_INITIALIZATION_JSON_PATH", container_path)
        return self
