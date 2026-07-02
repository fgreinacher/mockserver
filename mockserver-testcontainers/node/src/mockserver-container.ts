import { GenericContainer, Wait, StartedTestContainer } from "testcontainers";

/**
 * Default port MockServer listens on inside the container.
 * HTTP, HTTPS, SOCKS, and HTTP CONNECT are all served on this single unified port.
 */
const DEFAULT_PORT = 1080;

/**
 * Default Docker image name for MockServer.
 */
const DEFAULT_IMAGE = "mockserver/mockserver";

/**
 * Default image tag, derived from the installed `mockserver-client` version so
 * the container image stays in lockstep with the client (mirroring the Java
 * module). Falls back to `latest` when the client version cannot be resolved.
 * The tag format used by the official image is `mockserver-<version>`.
 */
function defaultTag(): string {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const version = require("mockserver-client/package.json").version as string;
    if (version) {
      return `mockserver-${version}`;
    }
  } catch {
    // mockserver-client not resolvable — fall back to the mutable latest tag
  }
  return "latest";
}

const DEFAULT_TAG = defaultTag();

/**
 * Options for configuring the MockServerContainer.
 */
export interface MockServerContainerOptions {
  /**
   * Full Docker image name with tag (e.g. "mockserver/mockserver:mockserver-7.0.0").
   * Overrides the default pinned image.
   */
  image?: string;

  /**
   * The port MockServer should listen on inside the container.
   * @default 1080
   */
  serverPort?: number;

  /**
   * MockServer environment variables to pass to the container.
   * Keys should be in MockServer env-var form (e.g. MOCKSERVER_LOG_LEVEL).
   */
  env?: Record<string, string>;
}

/**
 * A Testcontainers module for MockServer.
 *
 * Starts a `mockserver/mockserver` Docker container, waits for readiness,
 * and exposes convenience methods for connecting to it.
 *
 * @example
 * ```typescript
 * import { MockServerContainer } from "@mockserver/testcontainers";
 *
 * const container = await MockServerContainer.start();
 * console.log(container.getUrl()); // http://localhost:32789
 * // ... use the mock server ...
 * await container.stop();
 * ```
 */
export class MockServerContainer {
  private readonly options: Required<Pick<MockServerContainerOptions, "serverPort">> &
    MockServerContainerOptions;

  private startedContainer: StartedTestContainer | null = null;

  constructor(options: MockServerContainerOptions = {}) {
    this.options = {
      serverPort: DEFAULT_PORT,
      ...options,
    };
  }

  /**
   * Starts the MockServer container and waits for it to be ready.
   * Readiness is determined by the "started on port" log message OR
   * the HTTP status endpoint responding.
   *
   * @returns A started MockServerContainer with connection details available
   */
  static async start(
    options: MockServerContainerOptions = {}
  ): Promise<StartedMockServerContainer> {
    const instance = new MockServerContainer(options);
    return instance.start();
  }

  /**
   * Starts the MockServer container.
   */
  async start(): Promise<StartedMockServerContainer> {
    const imageName = this.options.image ?? `${DEFAULT_IMAGE}:${DEFAULT_TAG}`;
    const serverPort = this.options.serverPort ?? DEFAULT_PORT;

    let container = new GenericContainer(imageName)
      .withExposedPorts(serverPort)
      .withEnvironment({
        SERVER_PORT: String(serverPort),
        ...(this.options.env ?? {}),
      })
      .withWaitStrategy(
        Wait.forHttp("/mockserver/status", serverPort)
          .withMethod("PUT")
          .forStatusCode(200)
      );

    const startedContainer = await container.start();
    this.startedContainer = startedContainer;

    return new StartedMockServerContainer(startedContainer, serverPort);
  }
}

/**
 * Represents a started MockServer container with methods to retrieve
 * connection details and stop the container.
 */
export class StartedMockServerContainer {
  private readonly container: StartedTestContainer;
  private readonly serverPort: number;
  private client: unknown;

  /** @internal */
  constructor(container: StartedTestContainer, serverPort: number) {
    this.container = container;
    this.serverPort = serverPort;
  }

  /**
   * Returns the host that the MockServer container is reachable on.
   * This is typically "localhost" but may differ in CI environments.
   */
  getHost(): string {
    return this.container.getHost();
  }

  /**
   * Returns the mapped port on the host that routes to MockServer's port inside the container.
   */
  getPort(): number {
    return this.container.getMappedPort(this.serverPort);
  }

  /**
   * Returns the full HTTP URL to reach the MockServer container.
   * Format: `http://<host>:<port>`
   */
  getUrl(): string {
    return `http://${this.getHost()}:${this.getPort()}`;
  }

  /**
   * Returns the HTTP endpoint URL (alias for getUrl).
   * Format: `http://<host>:<port>`
   */
  getEndpoint(): string {
    return this.getUrl();
  }

  /**
   * Returns the HTTPS endpoint URL.
   * MockServer serves HTTP and HTTPS on the same unified port.
   * Format: `https://<host>:<port>`
   */
  getSecureEndpoint(): string {
    return `https://${this.getHost()}:${this.getPort()}`;
  }

  /**
   * Returns the mapped port for a specific internal container port.
   * Useful when exposing additional ports (e.g. DNS, HTTP/3).
   */
  getMappedPort(containerPort: number): number {
    return this.container.getMappedPort(containerPort);
  }

  /**
   * Returns a `mockserver-client` instance connected to this container,
   * created lazily on first call and cached. Mirrors the Java module's
   * `getClient()`. Requires the `mockserver-client` package, which is declared
   * as a dependency of this module.
   *
   * The client is untyped (the `mockserver-client` package ships no TypeScript
   * types), so the return type is `unknown` — cast it at the call site.
   */
  getClient(): unknown {
    if (this.client === undefined) {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const { mockServerClient } = require("mockserver-client");
      this.client = mockServerClient(this.getHost(), this.getPort());
    }
    return this.client;
  }

  /**
   * Stops and removes the MockServer container.
   */
  async stop(): Promise<void> {
    await this.container.stop();
  }
}
