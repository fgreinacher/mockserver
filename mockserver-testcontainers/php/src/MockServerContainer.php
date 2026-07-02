<?php

declare(strict_types=1);

namespace MockServer\Testcontainers;

use Composer\InstalledVersions;
use Testcontainers\Container\GenericContainer;
use Testcontainers\Wait\WaitForHttp;

/**
 * A Testcontainers module for MockServer.
 *
 * Starts the official {@code mockserver/mockserver} Docker image, waits for it
 * to answer {@code PUT /mockserver/status} with HTTP 200, and returns a
 * {@see StartedMockServerContainer} exposing connection helpers plus a
 * ready-wired {@see \MockServer\MockServerClient}.
 *
 * MockServer serves HTTP, HTTPS, SOCKS, and HTTP CONNECT on a single unified
 * port (default 1080).
 *
 * @example Basic usage
 *   $container = (new MockServerContainer())->start();
 *   $client = $container->getClient();
 *   $client->when(
 *       HttpRequest::request()->method('GET')->path('/hello')
 *   )->respond(
 *       HttpResponse::response()->statusCode(200)->body('world')
 *   );
 *   // point the system under test at $container->getEndpoint()
 *   $container->stop();
 */
class MockServerContainer extends GenericContainer
{
    /**
     * Default MockServer port (HTTP, HTTPS, SOCKS, and HTTP CONNECT are all
     * served on a single unified port).
     */
    public const DEFAULT_PORT = 1080;

    /** The Docker image name on Docker Hub. */
    public const IMAGE = 'mockserver/mockserver';

    /** The Composer package name of the MockServer PHP client. */
    private const CLIENT_PACKAGE = 'mock-server/mockserver-client';

    private int $mockServerPort = self::DEFAULT_PORT;

    /**
     * @param string|null $image full Docker image reference; when null the tag
     *                           is derived from the installed MockServer client
     *                           version (see {@see defaultImage()})
     */
    public function __construct(?string $image = null)
    {
        parent::__construct($image ?? self::defaultImage());
        $this->applyMockServerDefaults();
    }

    /**
     * Overrides the port MockServer listens on inside the container. Replaces
     * the exposed port so the readiness wait targets the correct port.
     */
    public function withServerPort(int $port): static
    {
        $this->mockServerPort = $port;
        // Replace (not append) the exposed port; withExposedPorts() appends.
        $this->exposedPorts = [];
        $this->applyMockServerDefaults();

        return $this;
    }

    /**
     * Sets the MockServer log level (e.g. "INFO", "DEBUG", "WARN", "ERROR", "TRACE").
     */
    public function withLogLevel(string $level): static
    {
        $this->withEnvironment(['MOCKSERVER_LOG_LEVEL' => $level]);

        return $this;
    }

    /**
     * Sets a single MockServer property as an environment variable. The key
     * must be in MockServer env-var form (e.g. "MOCKSERVER_MAX_EXPECTATIONS").
     */
    public function withMockServerProperty(string $key, string $value): static
    {
        $this->withEnvironment([$key => $value]);

        return $this;
    }

    /**
     * Copies an initialization JSON file into the container and configures
     * MockServer to load its expectations at startup.
     */
    public function withInitializationJson(string $hostInitJsonPath): static
    {
        $containerPath = '/config/initializerJson.json';
        $this->withCopyFilesToContainer([
            ['source' => $hostInitJsonPath, 'target' => $containerPath],
        ]);
        $this->withEnvironment(['MOCKSERVER_INITIALIZATION_JSON_PATH' => $containerPath]);

        return $this;
    }

    /**
     * Starts the container and returns a {@see StartedMockServerContainer}
     * wrapper exposing MockServer-specific connection helpers.
     */
    public function start(): StartedMockServerContainer
    {
        $started = parent::start();

        return new StartedMockServerContainer($started->getId(), $started->getClient(), $this->mockServerPort);
    }

    /**
     * Resolves the default Docker image, deriving the tag from the installed
     * MockServer PHP client version so the container image stays in lockstep
     * with the client. Falls back to {@code :latest} when the version cannot be
     * resolved to a clean release version.
     */
    public static function defaultImage(): string
    {
        $version = null;
        try {
            if (InstalledVersions::isInstalled(self::CLIENT_PACKAGE)) {
                $version = InstalledVersions::getPrettyVersion(self::CLIENT_PACKAGE);
            }
        } catch (\Throwable) {
            $version = null;
        }

        return self::IMAGE . ':' . self::imageTagForVersion($version);
    }

    /**
     * Maps a MockServer client version to a Docker image tag. A clean release
     * version (e.g. "7.3.0" or "v7.3.0") yields "mockserver-7.3.0"; anything
     * else (null, a dev/branch version, a snapshot) falls back to "latest".
     */
    public static function imageTagForVersion(?string $version): string
    {
        if ($version !== null && preg_match('/^v?(\d+\.\d+\.\d+)$/', $version, $m) === 1) {
            return 'mockserver-' . $m[1];
        }

        return 'latest';
    }

    /**
     * Applies the exposed port, SERVER_PORT env var, and the PUT
     * /mockserver/status readiness wait for the current MockServer port.
     */
    private function applyMockServerDefaults(): void
    {
        $this->withExposedPorts($this->mockServerPort);
        $this->withEnvironment(['SERVER_PORT' => (string) $this->mockServerPort]);
        // /mockserver/status requires a PUT and returns 200 with the bound
        // ports once MockServer is ready.
        $this->withWait(
            (new WaitForHttp($this->mockServerPort, 60000))
                ->withMethod('PUT')
                ->withPath('/mockserver/status')
                ->withExpectedStatusCode(200)
        );
    }
}
