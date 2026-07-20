<?php

declare(strict_types=1);

namespace MockServer\Tests\Integration;

use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\MockServerClient;
use MockServer\Times;
use MockServer\VerificationTimes;
use PHPUnit\Framework\TestCase;

/**
 * Integration tests that require a running MockServer instance.
 *
 * Set the MOCKSERVER_URL environment variable to enable these tests.
 * Example: MOCKSERVER_URL=http://localhost:1080 vendor/bin/phpunit --testsuite Integration
 *
 * If MOCKSERVER_URL is not set, all tests in this class are skipped.
 */
class MockServerIntegrationTest extends TestCase
{
    private ?MockServerClient $client = null;

    protected function setUp(): void
    {
        $url = getenv('MOCKSERVER_URL');
        if ($url === false || $url === '') {
            // A skip reports green, which is indistinguishable from a pass.
            // CI sets MOCKSERVER_REQUIRE_SERVER=true so a missing server fails
            // the build loudly instead of quietly testing nothing.
            if (getenv('MOCKSERVER_REQUIRE_SERVER') === 'true') {
                self::fail('MOCKSERVER_REQUIRE_SERVER=true but MOCKSERVER_URL is not set — refusing to skip.');
            }
            $this->markTestSkipped('MOCKSERVER_URL environment variable not set — skipping integration tests.');
        }

        $parsed = parse_url($url);
        $host = $parsed['host'] ?? 'localhost';
        $port = $parsed['port'] ?? 1080;
        $secure = ($parsed['scheme'] ?? 'http') === 'https';
        $contextPath = trim($parsed['path'] ?? '', '/');

        $this->client = new MockServerClient($host, $port, $contextPath, $secure);

        // Reset before each test for isolation
        $this->client->reset();
    }

    protected function tearDown(): void
    {
        if ($this->client !== null) {
            try {
                $this->client->reset();
            } catch (\Throwable) {
                // Best effort cleanup
            }
        }
    }

    public function testServerHasStarted(): void
    {
        $this->assertTrue($this->client->hasStarted(3, 1.0));
    }

    public function testStatus(): void
    {
        $status = $this->client->status();
        $this->assertArrayHasKey('ports', $status);
        $this->assertNotEmpty($status['ports']);
    }

    public function testCreateExpectationAndVerify(): void
    {
        // Create expectation
        $this->client->when(
            HttpRequest::request()->method('GET')->path('/integration-test')
        )->respond(
            HttpResponse::response()->statusCode(200)->body('integration-response')
        );

        // Make a real request to the mock
        $guzzle = new \GuzzleHttp\Client(['http_errors' => false]);
        $response = $guzzle->get($this->client->getBaseUri() . '/integration-test');

        $this->assertSame(200, $response->getStatusCode());
        $this->assertSame('integration-response', (string) $response->getBody());

        // Verify the request was received
        $this->client->verify(
            HttpRequest::request()->method('GET')->path('/integration-test'),
            VerificationTimes::exactly(1)
        );
    }

    public function testCreateTimeLimitedExpectation(): void
    {
        $this->client->when(
            HttpRequest::request()->method('GET')->path('/once-only'),
            Times::once()
        )->respond(
            HttpResponse::response()->statusCode(200)->body('first')
        );

        $guzzle = new \GuzzleHttp\Client(['http_errors' => false]);

        // First call should match
        $response1 = $guzzle->get($this->client->getBaseUri() . '/once-only');
        $this->assertSame(200, $response1->getStatusCode());
        $this->assertSame('first', (string) $response1->getBody());

        // Second call should not match (404 from MockServer)
        $response2 = $guzzle->get($this->client->getBaseUri() . '/once-only');
        $this->assertSame(404, $response2->getStatusCode());
    }

    public function testVerifySequence(): void
    {
        $this->client->when(
            HttpRequest::request()->path('/seq-a')
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $this->client->when(
            HttpRequest::request()->path('/seq-b')
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $guzzle = new \GuzzleHttp\Client(['http_errors' => false]);
        $guzzle->get($this->client->getBaseUri() . '/seq-a');
        $guzzle->get($this->client->getBaseUri() . '/seq-b');

        // Should pass — requests received in this order
        $this->client->verifySequence(
            HttpRequest::request()->path('/seq-a'),
            HttpRequest::request()->path('/seq-b')
        );

        // Should fail — wrong order
        $this->expectException(\MockServer\Exception\VerificationException::class);
        $this->client->verifySequence(
            HttpRequest::request()->path('/seq-b'),
            HttpRequest::request()->path('/seq-a')
        );
    }

    public function testClear(): void
    {
        $this->client->when(
            HttpRequest::request()->path('/to-clear')
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $this->client->clear(HttpRequest::request()->path('/to-clear'));

        // After clear, the expectation should be gone
        $expectations = $this->client->retrieveActiveExpectations(
            HttpRequest::request()->path('/to-clear')
        );
        $this->assertEmpty($expectations);
    }

    public function testRetrieveRecordedRequests(): void
    {
        $this->client->when(
            HttpRequest::request()->path('/recorded')
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $guzzle = new \GuzzleHttp\Client(['http_errors' => false]);
        $guzzle->get($this->client->getBaseUri() . '/recorded');

        $requests = $this->client->retrieveRecordedRequests(
            HttpRequest::request()->path('/recorded')
        );
        $this->assertNotEmpty($requests);
    }

    public function testRetrieveActiveExpectations(): void
    {
        $this->client->when(
            HttpRequest::request()->path('/active-check')
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $expectations = $this->client->retrieveActiveExpectations();
        $this->assertNotEmpty($expectations);
    }

    public function testReset(): void
    {
        $this->client->when(
            HttpRequest::request()->path('/will-be-reset')
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $this->client->reset();

        $expectations = $this->client->retrieveActiveExpectations();
        $this->assertEmpty($expectations);
    }
}
