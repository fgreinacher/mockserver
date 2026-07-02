<?php

declare(strict_types=1);

namespace MockServer\Testcontainers\Tests\Integration;

use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\Testcontainers\MockServerContainer;
use MockServer\Testcontainers\StartedMockServerContainer;
use MockServer\VerificationTimes;
use PHPUnit\Framework\TestCase;

/**
 * Integration test that starts a real MockServer Docker container via
 * Testcontainers. Requires a running Docker daemon.
 *
 * SKIPPED by default — enable by setting MOCKSERVER_TC_IT=1:
 *   MOCKSERVER_TC_IT=1 vendor/bin/phpunit --testsuite Integration
 */
class MockServerContainerIntegrationTest extends TestCase
{
    private ?StartedMockServerContainer $container = null;

    protected function setUp(): void
    {
        $flag = getenv('MOCKSERVER_TC_IT');
        if ($flag === false || $flag === '' || $flag === '0') {
            $this->markTestSkipped('MOCKSERVER_TC_IT not set — skipping Testcontainers integration test.');
        }

        $image = getenv('MOCKSERVER_IMAGE') ?: MockServerContainer::defaultImage();
        $this->container = (new MockServerContainer($image))->withLogLevel('WARN')->start();
    }

    protected function tearDown(): void
    {
        try {
            $this->container?->stop();
        } catch (\Throwable) {
            // best-effort teardown
        }
    }

    public function testServesExpectationsThroughWiredClient(): void
    {
        $client = $this->container->getMockServerClient();
        $client->when(
            HttpRequest::request()->method('GET')->path('/hello')
        )->respond(
            HttpResponse::response()->statusCode(200)->body('world')
        );

        $body = file_get_contents($this->container->getEndpoint() . '/hello');
        self::assertSame('world', $body);

        $client->verify(HttpRequest::request()->path('/hello'), VerificationTimes::atLeast(1));
        $client->reset();
    }
}
