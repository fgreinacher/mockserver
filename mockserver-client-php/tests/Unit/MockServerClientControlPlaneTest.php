<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Exception\FeatureNotEnabledException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\MockServerException;
use MockServer\MockServerClient;
use PHPUnit\Framework\TestCase;

/**
 * Tests for the typed control-plane helper methods: clock, metrics,
 * configuration, pact, file store, HAR/Postman import, operating mode and WSDL.
 *
 * Each test asserts the request the client sends (method, path, query, body)
 * and/or the behaviour derived from the server's status code — the live server
 * is replaced with a Guzzle MockHandler that records request history.
 */
class MockServerClientControlPlaneTest extends TestCase
{
    /**
     * @param array<Response> $responses
     * @param array<array> &$history
     */
    private function createClientWithMock(array $responses, array &$history = []): MockServerClient
    {
        $mock = new MockHandler($responses);
        $handlerStack = HandlerStack::create($mock);
        $handlerStack->push(Middleware::history($history));

        $client = new MockServerClient('localhost', 1080);

        $reflection = new \ReflectionClass($client);
        $prop = $reflection->getProperty('httpClient');
        $prop->setAccessible(true);
        $prop->setValue($client, new GuzzleClient([
            'handler' => $handlerStack,
            'http_errors' => false,
            'headers' => ['Content-Type' => 'application/json; charset=utf-8'],
        ]));

        return $client;
    }

    // -----------------------------------------------------------------
    // Clock
    // -----------------------------------------------------------------

    public function testFreezeClockAtCurrentTimeSendsFreezeAction(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"status":"freeze"}'),
        ], $history);

        $result = $client->freezeClock();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/clock', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('freeze', $body['action']);
        $this->assertArrayNotHasKey('instant', $body);

        $this->assertSame($client, $result);
    }

    public function testFreezeClockAtInstantSendsInstant(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"status":"freeze"}'),
        ], $history);

        $client->freezeClock('2024-01-01T00:00:00Z');

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('freeze', $body['action']);
        $this->assertSame('2024-01-01T00:00:00Z', $body['instant']);
    }

    public function testAdvanceClockSendsDurationMillis(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"status":"advance"}'),
        ], $history);

        $client->advanceClock(5000);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/clock', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('advance', $body['action']);
        $this->assertSame(5000, $body['durationMillis']);
    }

    public function testAdvanceClockThrowsOnBadRequest(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], '{"error":"durationMillis must be > 0"}'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->advanceClock(-1);
    }

    public function testResetClockSendsResetAction(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"status":"reset"}'),
        ], $history);

        $client->resetClock();

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('reset', $body['action']);
    }

    public function testClockStatusReturnsRawBody(): void
    {
        $history = [];
        $statusJson = '{"currentInstant":"2024-01-01T00:00:00Z","currentEpochMillis":1704067200000,"frozen":true}';
        $client = $this->createClientWithMock([
            new Response(200, [], $statusJson),
        ], $history);

        $result = $client->clockStatus();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/clock', $request->getUri()->getPath());
        $this->assertSame($statusJson, $result);
    }

    // -----------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------

    public function testRetrieveMetricsSendsRetrieveTypeMetrics(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"EXPECTATION_MATCHED_COUNT":3}'),
        ], $history);

        $result = $client->retrieveMetrics();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/retrieve', $request->getUri()->getPath());
        $this->assertSame('type=METRICS', $request->getUri()->getQuery());
        $this->assertSame('{"EXPECTATION_MATCHED_COUNT":3}', $result);
    }

    public function testScrapeMetricsReturnsExpositionText(): void
    {
        $history = [];
        $exposition = "# HELP foo\nfoo 1\n";
        $client = $this->createClientWithMock([
            new Response(200, ['Content-Type' => 'text/plain'], $exposition),
        ], $history);

        $result = $client->scrapeMetrics();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/metrics', $request->getUri()->getPath());
        $this->assertSame($exposition, $result);
    }

    public function testScrapeMetricsThrowsFeatureNotEnabledOn404(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], ''),
        ]);

        $this->expectException(FeatureNotEnabledException::class);
        $client->scrapeMetrics();
    }

    // -----------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------

    public function testRetrieveConfigurationSendsGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"logLevel":"INFO"}'),
        ], $history);

        $result = $client->retrieveConfiguration();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/configuration', $request->getUri()->getPath());
        $this->assertSame('{"logLevel":"INFO"}', $result);
    }

    public function testUpdateConfigurationSendsJsonAndReturnsUpdated(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"logLevel":"DEBUG"}'),
        ], $history);

        $result = $client->updateConfiguration('{"logLevel":"DEBUG"}');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/configuration', $request->getUri()->getPath());
        $this->assertSame('{"logLevel":"DEBUG"}', (string) $request->getBody());
        $this->assertSame('{"logLevel":"DEBUG"}', $result);
    }

    public function testUpdateConfigurationThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'Invalid configuration JSON'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->updateConfiguration('not json');
    }

    // -----------------------------------------------------------------
    // Pact
    // -----------------------------------------------------------------

    public function testPactImportSendsContractAndReturnsBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[{"id":"abc"}]'),
        ], $history);

        $result = $client->pactImport('{"consumer":{"name":"c"}}');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/pact/import', $request->getUri()->getPath());
        $this->assertSame('{"consumer":{"name":"c"}}', (string) $request->getBody());
        $this->assertSame('[{"id":"abc"}]', $result);
    }

    public function testPactImportRejectsBlank(): void
    {
        $client = $this->createClientWithMock([]);

        $this->expectException(\InvalidArgumentException::class);
        $client->pactImport('   ');
    }

    public function testPactExportSendsConsumerAndProviderQuery(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"interactions":[]}'),
        ], $history);

        $result = $client->pactExport('my-consumer', 'my-provider');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/pact', $request->getUri()->getPath());
        parse_str($request->getUri()->getQuery(), $query);
        $this->assertSame('my-consumer', $query['consumer']);
        $this->assertSame('my-provider', $query['provider']);
        $this->assertSame('{"interactions":[]}', $result);
    }

    public function testPactExportOmitsBlankQueryParams(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"interactions":[]}'),
        ], $history);

        $client->pactExport('', '');

        $this->assertSame('', $history[0]['request']->getUri()->getQuery());
    }

    public function testPactVerifyReturnsTrueOn202(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(202, [], '{"verified":true}'),
        ], $history);

        $result = $client->pactVerify('{"interactions":[]}');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/pact/verify', $request->getUri()->getPath());
        $this->assertTrue($result);
    }

    public function testPactVerifyReturnsFalseOn406(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], '{"verified":false}'),
        ]);

        $this->assertFalse($client->pactVerify('{"interactions":[]}'));
    }

    public function testPactVerifyThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], '{"error":"bad contract"}'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->pactVerify('{"bogus":true}');
    }

    public function testPactVerifyRejectsBlank(): void
    {
        $client = $this->createClientWithMock([]);

        $this->expectException(\InvalidArgumentException::class);
        $client->pactVerify('');
    }

    // -----------------------------------------------------------------
    // File store
    // -----------------------------------------------------------------

    public function testStoreFileSendsNameAndContent(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '{"name":"a.txt","size":5}'),
        ], $history);

        $result = $client->storeFile('a.txt', 'hello');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/files/store', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('a.txt', $body['name']);
        $this->assertSame('hello', $body['content']);

        $this->assertSame($client, $result);
    }

    public function testRetrieveFileReturnsRawBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], 'raw-file-bytes'),
        ], $history);

        $result = $client->retrieveFile('a.txt');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/files/retrieve', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('a.txt', $body['name']);
        $this->assertSame('raw-file-bytes', $result);
    }

    public function testRetrieveFileThrowsNotFoundOn404(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], 'file not found: missing.txt'),
        ]);

        // Cross-client consistency: a missing file is a clear error, not content.
        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('file not found: missing.txt');
        $client->retrieveFile('missing.txt');
    }

    public function testListFilesReturnsArrayOfNames(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '["a.txt","b.txt"]'),
        ], $history);

        $result = $client->listFiles();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/files/list', $request->getUri()->getPath());
        $this->assertSame(['a.txt', 'b.txt'], $result);
    }

    public function testDeleteFileSendsName(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], ''),
        ], $history);

        $result = $client->deleteFile('a.txt');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/files/delete', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('a.txt', $body['name']);

        $this->assertSame($client, $result);
    }

    public function testDeleteFileThrowsOn404(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], 'file not found: a.txt'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->deleteFile('a.txt');
    }

    // -----------------------------------------------------------------
    // Import (HAR / Postman)
    // -----------------------------------------------------------------

    public function testImportHarSendsFormatHar(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[{"id":"x"}]'),
        ], $history);

        $result = $client->importHar('{"log":{"entries":[]}}');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/import', $request->getUri()->getPath());
        $this->assertSame('format=har', $request->getUri()->getQuery());
        $this->assertSame('{"log":{"entries":[]}}', (string) $request->getBody());
        $this->assertSame([['id' => 'x']], $result);
    }

    public function testImportPostmanCollectionSendsFormatPostman(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[{"id":"y"}]'),
        ], $history);

        $result = $client->importPostmanCollection('{"info":{},"item":[]}');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/import', $request->getUri()->getPath());
        $this->assertSame('format=postman', $request->getUri()->getQuery());
        $this->assertSame([['id' => 'y']], $result);
    }

    public function testImportHarThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'unable to parse HAR'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->importHar('{}');
    }

    // -----------------------------------------------------------------
    // Operating mode
    // -----------------------------------------------------------------

    public function testModeConstants(): void
    {
        $this->assertSame('SIMULATE', MockServerClient::MODE_SIMULATE);
        $this->assertSame('SPY', MockServerClient::MODE_SPY);
        $this->assertSame('CAPTURE', MockServerClient::MODE_CAPTURE);
    }

    public function testSetModeSendsModeQuery(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"mode":"SPY","proxyUnmatchedRequests":true}'),
        ], $history);

        $result = $client->setMode(MockServerClient::MODE_SPY);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/mode', $request->getUri()->getPath());
        $this->assertSame('mode=SPY', $request->getUri()->getQuery());
        $this->assertSame($client, $result);
    }

    public function testSetModeThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], "unknown mode 'FOO'"),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->setMode('FOO');
    }

    public function testRetrieveModeReturnsDecodedJson(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{"mode":"CAPTURE","proxyUnmatchedRequests":true}'),
        ], $history);

        $result = $client->retrieveMode();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/mode', $request->getUri()->getPath());
        $this->assertSame('CAPTURE', $result['mode']);
        $this->assertTrue($result['proxyUnmatchedRequests']);
    }

    // -----------------------------------------------------------------
    // WSDL
    // -----------------------------------------------------------------

    public function testWsdlExpectationSendsRawXmlBody(): void
    {
        $history = [];
        $wsdl = '<definitions xmlns="http://schemas.xmlsoap.org/wsdl/"></definitions>';
        $client = $this->createClientWithMock([
            new Response(201, [], '[{"id":"w"}]'),
        ], $history);

        $result = $client->wsdlExpectation($wsdl);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/wsdl', $request->getUri()->getPath());
        $this->assertSame('text/xml', $request->getHeaderLine('Content-Type'));
        $this->assertSame($wsdl, (string) $request->getBody());
        $this->assertSame([['id' => 'w']], $result);
    }

    public function testWsdlExpectationRejectsBlank(): void
    {
        $client = $this->createClientWithMock([]);

        $this->expectException(\InvalidArgumentException::class);
        $client->wsdlExpectation('  ');
    }

    public function testWsdlExpectationThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'invalid WSDL'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->wsdlExpectation('<not-wsdl/>');
    }

    public function testMockServerExceptionOnServerError(): void
    {
        $client = $this->createClientWithMock([
            new Response(500, [], 'boom'),
        ]);

        $this->expectException(MockServerException::class);
        $client->retrieveConfiguration();
    }
}
