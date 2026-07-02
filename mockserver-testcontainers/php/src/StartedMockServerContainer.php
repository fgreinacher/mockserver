<?php

declare(strict_types=1);

namespace MockServer\Testcontainers;

use Docker\Docker;
use MockServer\MockServerClient;
use Testcontainers\Container\StartedGenericContainer;

/**
 * A started MockServer container. Extends the Testcontainers
 * {@see StartedGenericContainer} (so it is a drop-in started container) and adds
 * MockServer-specific connection helpers plus a ready-wired
 * {@see MockServerClient}.
 *
 * Note: the inherited {@see getClient()} returns the underlying Docker client
 * (per the Testcontainers {@code StartedTestContainer} contract). Use
 * {@see getMockServerClient()} for a configured MockServer client — the PHP
 * analogue of the Java {@code MockServerContainer.getClient()}.
 */
class StartedMockServerContainer extends StartedGenericContainer
{
    private ?MockServerClient $mockServerClient = null;

    public function __construct(
        string $id,
        ?Docker $dockerClient = null,
        private readonly int $mockServerPort = MockServerContainer::DEFAULT_PORT
    ) {
        parent::__construct($id, $dockerClient);
    }

    /**
     * Returns the mapped host port for MockServer.
     */
    public function getServerPort(): int
    {
        return $this->getMappedPort($this->mockServerPort);
    }

    /**
     * Returns the HTTP endpoint in the form {@code http://host:port}.
     */
    public function getEndpoint(): string
    {
        return 'http://' . $this->getHost() . ':' . $this->getServerPort();
    }

    /**
     * Returns the HTTPS endpoint in the form {@code https://host:port}.
     * MockServer serves HTTP and HTTPS on the same unified port.
     */
    public function getSecureEndpoint(): string
    {
        return 'https://' . $this->getHost() . ':' . $this->getServerPort();
    }

    /**
     * Returns a {@see MockServerClient} connected to this container. The client
     * is created lazily on first call and cached. This is the PHP analogue of
     * the Java {@code MockServerContainer.getClient()} (the inherited
     * {@see getClient()} name is reserved for the Docker client).
     */
    public function getMockServerClient(): MockServerClient
    {
        return $this->mockServerClient ??= new MockServerClient($this->getHost(), $this->getServerPort());
    }
}
