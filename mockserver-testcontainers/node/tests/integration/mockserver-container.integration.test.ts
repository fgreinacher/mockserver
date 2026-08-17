import { MockServerContainer, StartedMockServerContainer } from "../../src/mockserver-container";

/**
 * Integration tests for MockServerContainer.
 * These tests require Docker to be available. If Docker is not available,
 * the tests are skipped (matching the repo's assumeTrue(isDockerAvailable()) convention).
 */
describe("MockServerContainer (integration)", () => {
  let isDockerAvailable = false;

  beforeAll(async () => {
    // Probe Docker the way Testcontainers itself connects — directly to the
    // daemon socket — NOT via a `docker` CLI. Slim CI language images (this
    // suite runs inside node:20 with the socket bind-mounted) have no docker
    // binary, so a `docker info` probe would wrongly report "unavailable" and
    // skip every test while reading as a green pass — the exact false positive
    // the CI step's evidence-marker guard exists to catch.
    const { existsSync } = await import("fs");
    const dockerHost = process.env.DOCKER_HOST;
    if (dockerHost && dockerHost.trim() !== "") {
      // A tcp:// or unix:// DOCKER_HOST is set — Testcontainers will use it.
      isDockerAvailable = true;
    } else {
      // Fall back to the conventional daemon socket locations: the bind mount at
      // /var/run/docker.sock in CI, or the Docker Desktop socket on a dev host.
      const home = process.env.HOME ?? "";
      isDockerAvailable =
        existsSync("/var/run/docker.sock") ||
        (home !== "" && existsSync(`${home}/.docker/run/docker.sock`));
    }
  });

  function skipIfNoDocker() {
    if (!isDockerAvailable) {
      console.log("Docker is not available — skipping integration test");
    }
    return !isDockerAvailable;
  }

  it("starts MockServer container and responds to status endpoint", async () => {
    if (skipIfNoDocker()) return;

    let container: StartedMockServerContainer | undefined;
    try {
      container = await MockServerContainer.start();

      // Verify the container is reachable
      const url = container.getUrl();
      expect(url).toMatch(/^http:\/\/.+:\d+$/);

      // Check that MockServer responds to the status endpoint (PUT)
      const response = await fetch(`${url}/mockserver/status`, {
        method: "PUT",
      });
      expect(response.status).toBe(200);

      // Emit a stable, intentional evidence marker. The CI step
      // (node-testcontainers-test.sh) greps for this line to fail closed: it
      // proves a real container started and answered /mockserver/status, rather
      // than trusting Jest's exit code (this test returns early — a green pass —
      // when Docker is unavailable, so a skip would otherwise read as success).
      console.log(`NODE_TESTCONTAINERS_STATUS_OK url=${url} status=${response.status}`);

      const body = (await response.json()) as { ports?: unknown[] };
      expect(body).toHaveProperty("ports");
      expect(Array.isArray(body.ports)).toBe(true);
    } finally {
      if (container) {
        await container.stop();
      }
    }
  });

  it("serves mock expectations after creation", async () => {
    if (skipIfNoDocker()) return;

    let container: StartedMockServerContainer | undefined;
    try {
      container = await MockServerContainer.start();
      const url = container.getUrl();

      // Create an expectation
      const expectation = {
        httpRequest: {
          method: "GET",
          path: "/hello",
        },
        httpResponse: {
          statusCode: 200,
          body: "world",
        },
      };

      const createResp = await fetch(`${url}/mockserver/expectation`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(expectation),
      });
      expect(createResp.status).toBe(201);

      // Issue a matching request
      const matchResp = await fetch(`${url}/hello`, {
        method: "GET",
      });
      expect(matchResp.status).toBe(200);
      const matchBody = await matchResp.text();
      expect(matchBody).toBe("world");
    } finally {
      if (container) {
        await container.stop();
      }
    }
  });

  it("getHost() returns a non-empty string", async () => {
    if (skipIfNoDocker()) return;

    let container: StartedMockServerContainer | undefined;
    try {
      container = await MockServerContainer.start();
      expect(container.getHost()).toBeTruthy();
      expect(typeof container.getHost()).toBe("string");
    } finally {
      if (container) {
        await container.stop();
      }
    }
  });

  it("getPort() returns a valid port number", async () => {
    if (skipIfNoDocker()) return;

    let container: StartedMockServerContainer | undefined;
    try {
      container = await MockServerContainer.start();
      const port = container.getPort();
      expect(port).toBeGreaterThan(0);
      expect(port).toBeLessThanOrEqual(65535);
    } finally {
      if (container) {
        await container.stop();
      }
    }
  });

  it("supports custom environment variables", async () => {
    if (skipIfNoDocker()) return;

    let container: StartedMockServerContainer | undefined;
    try {
      container = await MockServerContainer.start({
        env: { MOCKSERVER_LOG_LEVEL: "WARN" },
      });

      const url = container.getUrl();
      const response = await fetch(`${url}/mockserver/status`, {
        method: "PUT",
      });
      expect(response.status).toBe(200);
    } finally {
      if (container) {
        await container.stop();
      }
    }
  });
});
