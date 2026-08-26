<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Delay;
use MockServer\Exception\FeatureNotEnabledException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\VerificationException;
use MockServer\HttpRequest;
use MockServer\LoadCapture;
use MockServer\LoadFeeder;
use MockServer\LoadPacing;
use MockServer\LoadProfile;
use MockServer\LoadScenario;
use MockServer\LoadShape;
use MockServer\LoadStage;
use MockServer\LoadThreshold;
use MockServer\MockServerClient;
use PHPUnit\Framework\TestCase;

/**
 * Tests for the SRE control-plane methods: load scenarios, service chaos,
 * SLO verdicts, preemption and chaos experiments.
 */
class MockServerClientSreTest extends TestCase
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
    // Load scenario
    // -----------------------------------------------------------------

    public function testLoadScenarioSendsCorrectJsonFromValueObject(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'checkout-load', 'state' => 'LOADED'])),
        ], $history);

        $scenario = LoadScenario::scenario('checkout-load')
            ->templateType('velocity')
            ->maxRequests(5000)
            ->labels(['team' => 'checkout'])
            ->profile(LoadProfile::of(
                LoadStage::vuRamp(1, 10, 10000),
                LoadStage::vuHold(10, 30000),
                LoadStage::pause(5000),
            ))
            ->addStep(
                HttpRequest::request()->method('GET')->path('/api/item/$iteration.index'),
                Delay::milliseconds(20),
                'fetch-item',
                ['route' => 'item'],
            );

        $result = $client->loadScenario($scenario);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('checkout-load', $body['name']);
        $this->assertSame('VELOCITY', $body['templateType']);
        $this->assertSame(5000, $body['maxRequests']);
        $this->assertSame(['team' => 'checkout'], $body['labels']);

        // profile — staged (Load Profile v2)
        $stages = $body['profile']['stages'];
        $this->assertCount(3, $stages);
        $this->assertSame('VU', $stages[0]['type']);
        $this->assertSame(1, $stages[0]['startVus']);
        $this->assertSame(10, $stages[0]['endVus']);
        $this->assertSame(10000, $stages[0]['durationMillis']);
        $this->assertSame('LINEAR', $stages[0]['curve']);
        $this->assertSame('VU', $stages[1]['type']);
        $this->assertSame(10, $stages[1]['vus']);
        $this->assertSame(30000, $stages[1]['durationMillis']);
        $this->assertArrayNotHasKey('curve', $stages[1]);
        $this->assertArrayNotHasKey('startVus', $stages[1]);
        $this->assertSame('PAUSE', $stages[2]['type']);
        $this->assertSame(5000, $stages[2]['durationMillis']);
        $this->assertArrayNotHasKey('vus', $stages[2]);

        // steps
        $this->assertCount(1, $body['steps']);
        $step = $body['steps'][0];
        $this->assertSame('GET', $step['request']['method']);
        $this->assertSame('/api/item/$iteration.index', $step['request']['path']);
        $this->assertSame('MILLISECONDS', $step['thinkTime']['timeUnit']);
        $this->assertSame(20, $step['thinkTime']['value']);
        $this->assertSame('fetch-item', $step['name']);
        $this->assertSame(['route' => 'item'], $step['labels']);

        $this->assertSame('checkout-load', $result['name']);
        $this->assertSame('LOADED', $result['state']);
    }

    public function testLinearLoadProfileShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('ramp')
                ->profile(LoadProfile::linear(1, 20, 60000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $stage = $body['profile']['stages'][0];
        $this->assertSame('VU', $stage['type']);
        $this->assertSame('LINEAR', $stage['curve']);
        $this->assertSame(1, $stage['startVus']);
        $this->assertSame(20, $stage['endVus']);
        $this->assertSame(60000, $stage['durationMillis']);
        $this->assertArrayNotHasKey('vus', $stage);
        $this->assertArrayNotHasKey('rate', $stage);
    }

    public function testRateLoadStageShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('rate')
                ->profile(LoadProfile::of(
                    LoadStage::rateHold(100.0, 30000)->maxVus(20),
                    LoadStage::rateRamp(10.0, 200.0, 60000, 'EXPONENTIAL'),
                ))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $hold = $body['profile']['stages'][0];
        $this->assertSame('RATE', $hold['type']);
        $this->assertEquals(100.0, $hold['rate']);
        $this->assertSame(30000, $hold['durationMillis']);
        $this->assertSame(20, $hold['maxVus']);
        $this->assertArrayNotHasKey('curve', $hold);
        $this->assertArrayNotHasKey('vus', $hold);

        $ramp = $body['profile']['stages'][1];
        $this->assertSame('RATE', $ramp['type']);
        $this->assertSame('EXPONENTIAL', $ramp['curve']);
        $this->assertEquals(10.0, $ramp['startRate']);
        $this->assertEquals(200.0, $ramp['endRate']);
        $this->assertArrayNotHasKey('rate', $ramp);
        $this->assertArrayNotHasKey('maxVus', $ramp);
    }

    public function testLoadScenarioAcceptsPlainArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario([
            'name' => 'raw',
            'profile' => ['type' => 'CONSTANT', 'vus' => 2, 'durationMillis' => 1000],
            'steps' => [['request' => ['method' => 'GET', 'path' => '/raw']]],
        ]);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('raw', $body['name']);
        $this->assertSame('/raw', $body['steps'][0]['request']['path']);
    }

    public function testLoadScenarioRegistersAndReturnsRef(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'x', 'state' => 'LOADED'])),
        ], $history);

        $result = $client->loadScenario(LoadScenario::scenario('x')
            ->profile(LoadProfile::constant(1, 1000))
            ->addStep(HttpRequest::request()->path('/x')));

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
        $this->assertSame('x', $result['name']);
        $this->assertSame('LOADED', $result['state']);
    }

    public function testLoadScenarioSerialisesStartDelayMillis(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('delayed')
                ->startDelayMillis(250)
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        // PHP json_encode renders whole-number floats as ints; assertEquals (not assertSame).
        $this->assertEquals(250, $body['startDelayMillis']);
    }

    public function testLoadScenarioRegistrationAllowedWhenGenerationDisabled(): void
    {
        // Registration is NOT gated — a 403 is surfaced generically, not as
        // FeatureNotEnabledException, because the registration endpoint never
        // gates on loadGenerationEnabled.
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'x', 'state' => 'LOADED'])),
        ], $history);

        $result = $client->loadScenario(LoadScenario::scenario('x')
            ->profile(LoadProfile::constant(1, 1000))
            ->addStep(HttpRequest::request()->path('/x')));

        $this->assertSame('LOADED', $result['state']);
    }

    public function testLoadScenariosListsAllViaGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'scenarios' => [
                    ['name' => 'a', 'state' => 'RUNNING'],
                    ['name' => 'b', 'state' => 'LOADED'],
                ],
            ])),
        ], $history);

        $result = $client->loadScenarios();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
        $this->assertCount(2, $result['scenarios']);
        $this->assertSame('a', $result['scenarios'][0]['name']);
        $this->assertSame('RUNNING', $result['scenarios'][0]['state']);
    }

    public function testGetLoadScenarioUsesNamedGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'check out', 'state' => 'COMPLETED'])),
        ], $history);

        $result = $client->getLoadScenario('check out');

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/check%20out', $request->getUri()->getPath());
        $this->assertSame('COMPLETED', $result['state']);
    }

    public function testGetLoadScenarioThrowsOn404(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], 'no such scenario'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->getLoadScenario('missing');
    }

    public function testDeleteLoadScenarioUsesNamedDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->deleteLoadScenario('checkout-load');

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/checkout-load', $request->getUri()->getPath());
    }

    public function testClearLoadScenariosUsesUnscopedDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->clearLoadScenarios();

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
    }

    public function testStartLoadScenariosWithSingleNameSendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'started' => [['name' => 'a', 'state' => 'RUNNING']],
                'status' => 'started',
            ])),
        ], $history);

        $result = $client->startLoadScenarios('a');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/start', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame(['names' => ['a']], $body);
        $this->assertSame('started', $result['status']);
        $this->assertSame('a', $result['started'][0]['name']);
    }

    public function testStartLoadScenariosWithArraySendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->startLoadScenarios(['a', 'b']);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame(['names' => ['a', 'b']], $body);
    }

    public function testStartLoadScenariosThrowsFeatureNotEnabledOn403(): void
    {
        $client = $this->createClientWithMock([
            new Response(403, [], 'load generation disabled'),
        ]);

        $this->expectException(FeatureNotEnabledException::class);
        $this->expectExceptionMessage('loadGenerationEnabled=true');

        $client->startLoadScenarios('a');
    }

    public function testStartLoadScenariosThrowsOn404UnknownName(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], 'unknown scenario'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->startLoadScenarios('ghost');
    }

    public function testStopLoadScenariosWithNoArgsStopsAllWithEmptyBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['stopped' => [], 'status' => 'stopped'])),
        ], $history);

        $result = $client->stopLoadScenarios();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/stop', $request->getUri()->getPath());
        $this->assertSame('', (string) $request->getBody());
        $this->assertSame('stopped', $result['status']);
    }

    public function testStopLoadScenariosWithNamesSendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->stopLoadScenarios(['a', 'b']);

        $request = $history[0]['request'];
        $this->assertSame('/mockserver/loadScenario/stop', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame(['names' => ['a', 'b']], $body);
    }

    public function testStopLoadScenariosWithSingleNameSendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->stopLoadScenarios('a');

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame(['names' => ['a']], $body);
    }

    public function testRunLoadScenarioRegistersThenStarts(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'run-me', 'state' => 'LOADED'])),
            new Response(200, [], json_encode([
                'started' => [['name' => 'run-me', 'state' => 'RUNNING']],
                'status' => 'started',
            ])),
        ], $history);

        $result = $client->runLoadScenario(
            LoadScenario::scenario('run-me')
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $this->assertCount(2, $history);
        $register = $history[0]['request'];
        $this->assertSame('PUT', $register->getMethod());
        $this->assertSame('/mockserver/loadScenario', $register->getUri()->getPath());

        $start = $history[1]['request'];
        $this->assertSame('PUT', $start->getMethod());
        $this->assertSame('/mockserver/loadScenario/start', $start->getUri()->getPath());
        $startBody = json_decode((string) $start->getBody(), true);
        $this->assertSame(['names' => ['run-me']], $startBody);

        $this->assertSame('started', $result['status']);
    }

    public function testRunLoadScenarioAcceptsPlainArrayAndStartsByName(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
            new Response(200, [], '{}'),
        ], $history);

        $client->runLoadScenario([
            'name' => 'raw-run',
            'profile' => ['stages' => [['type' => 'VU', 'vus' => 1, 'durationMillis' => 1000]]],
            'steps' => [['request' => ['method' => 'GET', 'path' => '/raw']]],
        ]);

        $startBody = json_decode((string) $history[1]['request']->getBody(), true);
        $this->assertSame(['names' => ['raw-run']], $startBody);
    }

    // -----------------------------------------------------------------
    // Service chaos
    // -----------------------------------------------------------------

    public function testSetServiceChaosSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->setServiceChaos(
            'payments.internal:8443',
            [
                'errorStatus' => 503,
                'errorProbability' => 0.3,
                'latency' => ['timeUnit' => 'MILLISECONDS', 'value' => 200],
            ],
            60000,
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/serviceChaos', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('payments.internal:8443', $body['host']);
        $this->assertSame(503, $body['chaos']['errorStatus']);
        $this->assertSame(0.3, $body['chaos']['errorProbability']);
        $this->assertSame('MILLISECONDS', $body['chaos']['latency']['timeUnit']);
        $this->assertSame(200, $body['chaos']['latency']['value']);
        $this->assertSame(60000, $body['ttlMillis']);
    }

    public function testSetServiceChaosOmitsTtlWhenNull(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->setServiceChaos('api.local', ['errorStatus' => 500]);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('api.local', $body['host']);
        $this->assertArrayNotHasKey('ttlMillis', $body);
    }

    public function testSetServiceChaosThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'invalid chaos profile'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->setServiceChaos('api.local', ['errorStatus' => 9999]);
    }

    // -----------------------------------------------------------------
    // SLO verdicts
    // -----------------------------------------------------------------

    public function testVerifySloSendsCorrectPayloadAndReturnsVerdict(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'checkout-slo', 'result' => 'PASS'])),
        ], $history);

        $verdict = $client->verifySlo([
            'name' => 'checkout-slo',
            'window' => ['type' => 'LOOKBACK', 'lookbackMillis' => 60000],
            'minimumSampleCount' => 20,
            'upstreamHosts' => ['payments.svc'],
            'objectives' => [
                ['sli' => 'LATENCY_P95', 'comparator' => 'LESS_THAN', 'threshold' => 250.0, 'scope' => 'FORWARD'],
                ['sli' => 'ERROR_RATE', 'comparator' => 'LESS_THAN_OR_EQUAL', 'threshold' => 0.01],
            ],
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/verifySLO', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('checkout-slo', $body['name']);
        $this->assertSame('LATENCY_P95', $body['objectives'][0]['sli']);
        // PHP's json_encode renders 250.0 as "250", which json_decodes back to int 250; the server
        // accepts either, so assert numeric equality rather than the exact int/float type.
        $this->assertEquals(250.0, $body['objectives'][0]['threshold']);

        $this->assertSame('PASS', $verdict['result']);
    }

    public function testVerifySloThrowsVerificationExceptionOn406Fail(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], json_encode(['result' => 'FAIL'])),
        ]);

        $this->expectException(VerificationException::class);
        $client->verifySlo(['objectives' => []]);
    }

    public function testVerifySloThrowsFeatureNotEnabledOn403Disabled(): void
    {
        // 403 is "SLO tracking is off"; it used to share 400 with malformed criteria, so a typo in
        // the criteria was reported to callers as a disabled feature and vice versa.
        $client = $this->createClientWithMock([
            new Response(403, [], 'SLO tracking not enabled'),
        ]);

        $this->expectException(FeatureNotEnabledException::class);
        $this->expectExceptionMessage('sloTrackingEnabled=true');

        $client->verifySlo(['objectives' => []]);
    }

    public function testVerifySloThrowsInvalidRequestOn400MalformedCriteria(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'objectives must be an array'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('Invalid SLO criteria');

        $client->verifySlo(['objectives' => []]);
    }

    // -----------------------------------------------------------------
    // Preemption
    // -----------------------------------------------------------------

    public function testSetPreemptionSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'draining', 'inFlight' => 2])),
        ], $history);

        $result = $client->setPreemption([
            'mode' => 'both',
            'drainMillis' => 10000,
            'ttlMillis' => 60000,
            'lastStreamId' => 3,
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('both', $body['mode']);
        $this->assertSame(10000, $body['drainMillis']);
        $this->assertSame(60000, $body['ttlMillis']);
        $this->assertSame(3, $body['lastStreamId']);

        $this->assertSame('draining', $result['state']);
    }

    public function testSetPreemptionEmptyBodySendsJsonObject(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'draining'])),
        ], $history);

        $client->setPreemption();

        // An empty body must serialise as a JSON object "{}", not an array "[]".
        $this->assertSame('{}', (string) $history[0]['request']->getBody());
    }

    public function testPreemptionStatusUsesGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'inactive', 'inFlight' => 0])),
        ], $history);

        $result = $client->preemptionStatus();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());
        $this->assertSame('inactive', $result['state']);
    }

    public function testClearPreemptionUsesDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'inactive'])),
        ], $history);

        $result = $client->clearPreemption();

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());
        $this->assertSame('inactive', $result['state']);
    }

    // -----------------------------------------------------------------
    // Chaos experiment
    // -----------------------------------------------------------------

    public function testStartChaosExperimentSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['status' => 'started', 'name' => 'gradual-degradation'])),
        ], $history);

        $result = $client->startChaosExperiment([
            'name' => 'gradual-degradation',
            'stages' => [
                [
                    'durationMillis' => 10000,
                    'profiles' => ['api.example.com' => ['errorStatus' => 500, 'errorProbability' => 0.1]],
                ],
                [
                    'durationMillis' => 10000,
                    'profiles' => ['api.example.com' => ['errorStatus' => 500, 'errorProbability' => 0.5]],
                ],
            ],
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/chaosExperiment', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('gradual-degradation', $body['name']);
        $this->assertCount(2, $body['stages']);
        $this->assertSame(10000, $body['stages'][0]['durationMillis']);
        $this->assertSame(0.1, $body['stages'][0]['profiles']['api.example.com']['errorProbability']);

        $this->assertSame('started', $result['status']);
    }

    public function testStartChaosExperimentThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'empty stages'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->startChaosExperiment(['name' => 'bad', 'stages' => []]);
    }

    // -----------------------------------------------------------------
    // Load scenario — thresholds, pacing, feeder, captures, weight, shape
    // -----------------------------------------------------------------

    public function testLoadScenarioSerialisesThresholdsAndAbortControls(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('with-thresholds')
                ->thresholds(
                    LoadThreshold::of('LATENCY_P95', 'LESS_THAN', 250),
                    LoadThreshold::of('ERROR_RATE', 'LESS_THAN_OR_EQUAL', 0.01),
                )
                ->abortOnFail()
                ->abortGraceMillis(5000)
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);

        $this->assertCount(2, $body['thresholds']);
        $this->assertSame('LATENCY_P95', $body['thresholds'][0]['metric']);
        $this->assertSame('LESS_THAN', $body['thresholds'][0]['comparator']);
        $this->assertEquals(250, $body['thresholds'][0]['threshold']);
        $this->assertSame('ERROR_RATE', $body['thresholds'][1]['metric']);
        $this->assertSame('LESS_THAN_OR_EQUAL', $body['thresholds'][1]['comparator']);
        $this->assertEquals(0.01, $body['thresholds'][1]['threshold']);

        $this->assertTrue($body['abortOnFail']);
        $this->assertEquals(5000, $body['abortGraceMillis']);
    }

    public function testLoadScenarioSerialisesPacingAndFeeder(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('paced')
                ->pacing(LoadPacing::constantPacing(1000))
                ->feeder(LoadFeeder::rows([
                    ['user' => 'alice', 'id' => '1'],
                    ['user' => 'bob', 'id' => '2'],
                ])->strategy('RANDOM'))
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);

        $this->assertSame('CONSTANT_PACING', $body['pacing']['mode']);
        $this->assertEquals(1000, $body['pacing']['value']);

        $this->assertCount(2, $body['feeder']['rows']);
        $this->assertSame('alice', $body['feeder']['rows'][0]['user']);
        $this->assertSame('RANDOM', $body['feeder']['strategy']);
        $this->assertArrayNotHasKey('data', $body['feeder']);
        $this->assertArrayNotHasKey('format', $body['feeder']);
    }

    public function testLoadFeederRawCsvShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('csv-feeder')
                ->feeder(LoadFeeder::raw("user,id\nalice,1\nbob,2", 'csv'))
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame("user,id\nalice,1\nbob,2", $body['feeder']['data']);
        $this->assertSame('CSV', $body['feeder']['format']);
        $this->assertArrayNotHasKey('rows', $body['feeder']);
        $this->assertArrayNotHasKey('strategy', $body['feeder']);
    }

    public function testLoadScenarioWeightedStepsWithCaptures(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('weighted')
                ->stepSelection('weighted')
                ->profile(LoadProfile::constant(2, 1000))
                ->addStep(
                    HttpRequest::request()->method('POST')->path('/login'),
                    null,
                    'login',
                    [],
                    [
                        LoadCapture::of('token', 'BODY_JSONPATH', '$.token'),
                        LoadCapture::of('etag', 'HEADER', 'ETag')->defaultValue('none'),
                    ],
                    7.0,
                )
                ->addStep(
                    HttpRequest::request()->method('GET')->path('/me'),
                    null,
                    'me',
                    [],
                    [],
                    3.0,
                )
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);

        $this->assertSame('WEIGHTED', $body['stepSelection']);

        $login = $body['steps'][0];
        $this->assertEquals(7.0, $login['weight']);
        $this->assertCount(2, $login['captures']);
        $this->assertSame('token', $login['captures'][0]['name']);
        $this->assertSame('BODY_JSONPATH', $login['captures'][0]['source']);
        $this->assertSame('$.token', $login['captures'][0]['expression']);
        $this->assertArrayNotHasKey('defaultValue', $login['captures'][0]);
        $this->assertSame('etag', $login['captures'][1]['name']);
        $this->assertSame('HEADER', $login['captures'][1]['source']);
        $this->assertSame('none', $login['captures'][1]['defaultValue']);

        $me = $body['steps'][1];
        $this->assertEquals(3.0, $me['weight']);
        $this->assertArrayNotHasKey('captures', $me);
    }

    public function testLoadProfileFromShapeSpike(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('spike')
                ->profile(LoadProfile::fromShape(
                    LoadShape::spike('VU', 1, 50, 5000, 30000, 5000)
                        ->recoveryHoldMillis(10000)
                        ->curve('LINEAR')
                ))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $shape = $body['profile']['shape'];
        $this->assertSame('SPIKE', $shape['type']);
        $this->assertSame('VU', $shape['metric']);
        $this->assertSame('LINEAR', $shape['curve']);
        $this->assertEquals(1, $shape['baseline']);
        $this->assertEquals(50, $shape['peak']);
        $this->assertEquals(5000, $shape['rampUpMillis']);
        $this->assertEquals(30000, $shape['holdMillis']);
        $this->assertEquals(5000, $shape['rampDownMillis']);
        $this->assertEquals(10000, $shape['recoveryHoldMillis']);
        // a shape-only profile carries no explicit stages
        $this->assertArrayNotHasKey('stages', $body['profile']);
        $this->assertArrayNotHasKey('start', $shape);
        $this->assertArrayNotHasKey('target', $shape);
    }

    public function testLoadProfileShapeStairsAndRampHold(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('stairs')
                ->profile(LoadProfile::fromShape(LoadShape::stairs('RATE', 10, 10, 5, 20000)))
                ->addStep(HttpRequest::request()->path('/x'))
        );
        $stairs = json_decode((string) $history[0]['request']->getBody(), true)['profile']['shape'];
        $this->assertSame('STAIRS', $stairs['type']);
        $this->assertSame('RATE', $stairs['metric']);
        $this->assertEquals(10, $stairs['start']);
        $this->assertEquals(10, $stairs['step']);
        $this->assertEquals(5, $stairs['steps']);
        $this->assertEquals(20000, $stairs['stepDurationMillis']);
        $this->assertArrayNotHasKey('peak', $stairs);

        $client->loadScenario(
            LoadScenario::scenario('ramp-hold')
                ->profile(LoadProfile::fromShape(LoadShape::rampHold('VU', 25, 10000, 60000)))
                ->addStep(HttpRequest::request()->path('/x'))
        );
        $rampHold = json_decode((string) $history[1]['request']->getBody(), true)['profile']['shape'];
        $this->assertSame('RAMP_HOLD', $rampHold['type']);
        $this->assertSame('VU', $rampHold['metric']);
        $this->assertEquals(25, $rampHold['target']);
        $this->assertEquals(10000, $rampHold['rampMillis']);
        $this->assertEquals(60000, $rampHold['holdMillis']);
    }

    public function testLoadProfileStagesStillEmittedWhenNoShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('stages')
                ->profile(LoadProfile::constant(3, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertArrayHasKey('stages', $body['profile']);
        $this->assertArrayNotHasKey('shape', $body['profile']);
        $this->assertSame(3, $body['profile']['stages'][0]['vus']);
    }

    // -----------------------------------------------------------------
    // Load scenario — report and generation endpoints
    // -----------------------------------------------------------------

    public function testGetLoadScenarioReportJsonForm(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'scenario' => 'checkout-load',
                'verdict' => 'PASS',
                'p999Millis' => 412.5,
            ])),
        ], $history);

        $report = $client->getLoadScenarioReport('checkout-load');

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/checkout-load/report', $request->getUri()->getPath());
        $this->assertSame('', $request->getUri()->getQuery());

        $this->assertIsArray($report);
        $this->assertSame('checkout-load', $report['scenario']);
        $this->assertSame('PASS', $report['verdict']);
        $this->assertEquals(412.5, $report['p999Millis']);
    }

    public function testGetLoadScenarioReportJunitFormReturnsRawXml(): void
    {
        $history = [];
        $xml = '<testsuite name="checkout-load"><testcase name="run completed"/></testsuite>';
        $client = $this->createClientWithMock([
            new Response(200, ['Content-Type' => 'application/xml'], $xml),
        ], $history);

        $report = $client->getLoadScenarioReport('checkout-load', 'junit');

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/checkout-load/report', $request->getUri()->getPath());
        $this->assertSame('format=junit', $request->getUri()->getQuery());

        $this->assertIsString($report);
        $this->assertSame($xml, $report);
    }

    public function testGetLoadScenarioReportThrowsOn404(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], json_encode(['error' => 'no run'])),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->getLoadScenarioReport('never-ran');
    }

    public function testGenerateLoadScenarioFromOpenAPISendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'status' => 'loaded',
                'name' => 'petstore-load',
                'state' => 'LOADED',
                'scenario' => ['name' => 'petstore-load'],
            ])),
        ], $history);

        $result = $client->generateLoadScenarioFromOpenAPI(
            'petstore-load',
            'https://example.com/petstore.yaml',
            ['host' => 'petstore.svc', 'port' => 8080, 'scheme' => 'http'],
            LoadProfile::constant(1, 5000),
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/generateFromOpenAPI', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('petstore-load', $body['name']);
        $this->assertSame('https://example.com/petstore.yaml', $body['specUrlOrPayload']);
        $this->assertSame('petstore.svc', $body['target']['host']);
        $this->assertSame(8080, $body['target']['port']);
        $this->assertSame('http', $body['target']['scheme']);
        $this->assertSame(1, $body['profile']['stages'][0]['vus']);

        $this->assertSame('loaded', $result['status']);
        $this->assertSame('LOADED', $result['state']);
    }

    public function testGenerateLoadScenarioFromOpenAPIOmitsOptionalFields(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->generateLoadScenarioFromOpenAPI('minimal', '{"openapi":"3.0.0"}');

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('minimal', $body['name']);
        $this->assertSame('{"openapi":"3.0.0"}', $body['specUrlOrPayload']);
        $this->assertArrayNotHasKey('target', $body);
        $this->assertArrayNotHasKey('profile', $body);
    }

    public function testGenerateLoadScenarioFromOpenAPIThrowsOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'unparseable spec'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->generateLoadScenarioFromOpenAPI('bad', 'not a spec');
    }

    public function testGenerateLoadScenarioFromRecordingSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'status' => 'loaded',
                'name' => 'replay-prod-traffic',
                'state' => 'LOADED',
            ])),
        ], $history);

        $result = $client->generateLoadScenarioFromRecording(
            'replay-prod-traffic',
            'templatized',
            HttpRequest::request()->method('GET'),
            ['host' => 'staging.svc', 'port' => 8080, 'scheme' => 'http'],
            100,
            LoadProfile::constant(1, 5000),
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/generateFromRecording', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('replay-prod-traffic', $body['name']);
        $this->assertSame('TEMPLATIZED', $body['mode']);
        $this->assertSame('GET', $body['requestFilter']['method']);
        $this->assertSame('staging.svc', $body['target']['host']);
        $this->assertSame(100, $body['maxSteps']);
        $this->assertSame(1, $body['profile']['stages'][0]['vus']);

        $this->assertSame('loaded', $result['status']);
    }

    public function testGenerateLoadScenarioFromRecordingMinimalPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->generateLoadScenarioFromRecording('replay');

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame(['name' => 'replay'], $body);
    }

    public function testGenerateLoadScenarioFromRecordingThrowsOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'no recorded requests'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->generateLoadScenarioFromRecording('empty');
    }
}
