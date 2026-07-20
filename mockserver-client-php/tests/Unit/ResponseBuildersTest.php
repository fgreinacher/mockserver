<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\BinaryResponse;
use MockServer\Delay;
use MockServer\DnsRecord;
use MockServer\DnsResponse;
use MockServer\GrpcStreamMessage;
use MockServer\GrpcStreamResponse;
use MockServer\HttpSseResponse;
use MockServer\GraphQLSubscriptionFilter;
use MockServer\HttpWebSocketResponse;
use MockServer\OpenAPIExpectation;
use MockServer\SseEvent;
use MockServer\WebSocketMessage;
use PHPUnit\Framework\TestCase;

/**
 * Pure builder tests asserting the wire JSON keys produced by toArray()
 * for each advanced response builder, independent of any HTTP transport.
 */
class ResponseBuildersTest extends TestCase
{
    public function testSseEventToArray(): void
    {
        $event = SseEvent::event()
            ->withEvent('message')
            ->withData('payload')
            ->withId('42')
            ->withRetry(3000)
            ->withDelay(Delay::milliseconds(100));

        $this->assertSame([
            'event' => 'message',
            'data' => 'payload',
            'id' => '42',
            'retry' => 3000,
            'delay' => ['timeUnit' => 'MILLISECONDS', 'value' => 100],
        ], $event->toArray());
    }

    public function testHttpSseResponseToArray(): void
    {
        $sse = HttpSseResponse::response()
            ->statusCode(200)
            ->header('Content-Type', 'text/event-stream')
            ->event(SseEvent::event()->withData('a'))
            ->closeConnection(true)
            ->primary(false);

        $arr = $sse->toArray();
        $this->assertSame(200, $arr['statusCode']);
        $this->assertSame(['text/event-stream'], $arr['headers']['Content-Type']);
        $this->assertSame([['data' => 'a']], $arr['events']);
        $this->assertTrue($arr['closeConnection']);
        $this->assertFalse($arr['primary']);
    }

    public function testWebSocketMessageTextAndBinary(): void
    {
        $this->assertSame(['text' => 'hello'], WebSocketMessage::text('hello')->toArray());

        $binary = WebSocketMessage::binary("\x00\xFF")->toArray();
        $this->assertSame(base64_encode("\x00\xFF"), $binary['binary']);
        $this->assertArrayNotHasKey('text', $binary);

        $this->assertSame(['binary' => 'YWJj'], WebSocketMessage::binaryBase64('YWJj')->toArray());
    }

    public function testHttpWebSocketResponseToArray(): void
    {
        $ws = HttpWebSocketResponse::response()
            ->subprotocol('graphql-ws')
            ->message(WebSocketMessage::text('ping'))
            ->closeConnection(false);

        $arr = $ws->toArray();
        $this->assertSame('graphql-ws', $arr['subprotocol']);
        $this->assertSame([['text' => 'ping']], $arr['messages']);
        $this->assertFalse($arr['closeConnection']);
    }

    public function testGrpcStreamMessageJsonArrayAndString(): void
    {
        $this->assertSame(['json' => ['a' => 1]], GrpcStreamMessage::message(['a' => 1])->toArray());
        $this->assertSame(['json' => '{"a":1}'], GrpcStreamMessage::message('{"a":1}')->toArray());
    }

    public function testGrpcStreamResponseToArray(): void
    {
        $grpc = GrpcStreamResponse::response()
            ->statusName('OK')
            ->statusMessage('done')
            ->header('grpc-status', '0')
            ->message(GrpcStreamMessage::message(['n' => 1]))
            ->primary(true);

        $arr = $grpc->toArray();
        $this->assertSame('OK', $arr['statusName']);
        $this->assertSame('done', $arr['statusMessage']);
        $this->assertSame(['0'], $arr['headers']['grpc-status']);
        $this->assertSame([['json' => ['n' => 1]]], $arr['messages']);
        $this->assertTrue($arr['primary']);
    }

    public function testBinaryResponseFromBytesAndBase64(): void
    {
        $this->assertSame(
            ['binaryData' => base64_encode("\x01\x02")],
            BinaryResponse::response()->fromBytes("\x01\x02")->toArray()
        );
        $this->assertSame(
            ['binaryData' => 'YWJj'],
            BinaryResponse::response()->binaryData('YWJj')->toArray()
        );
    }

    public function testDnsRecordFactories(): void
    {
        $this->assertSame(
            ['name' => 'host', 'type' => 'A', 'value' => '1.2.3.4'],
            DnsRecord::aRecord('host', '1.2.3.4')->toArray()
        );
        $this->assertSame(
            ['name' => 'host', 'type' => 'AAAA', 'value' => '::1'],
            DnsRecord::aaaaRecord('host', '::1')->toArray()
        );
        $this->assertSame(
            ['name' => 'alias', 'type' => 'CNAME', 'value' => 'target'],
            DnsRecord::cnameRecord('alias', 'target')->toArray()
        );
        $this->assertSame(
            ['name' => 'd', 'type' => 'MX', 'value' => 'mail', 'priority' => 5],
            DnsRecord::mxRecord('d', 5, 'mail')->toArray()
        );
        $this->assertSame(
            [
                'name' => '_sip._tcp',
                'type' => 'SRV',
                'value' => 'sipserver',
                'priority' => 1,
                'weight' => 2,
                'port' => 5060,
            ],
            DnsRecord::srvRecord('_sip._tcp', 1, 2, 5060, 'sipserver')->toArray()
        );
        $this->assertSame(
            ['name' => 'd', 'type' => 'TXT', 'value' => 'v=spf1'],
            DnsRecord::txtRecord('d', 'v=spf1')->toArray()
        );
    }

    public function testDnsResponseToArray(): void
    {
        $dns = DnsResponse::response()
            ->responseCode('NXDOMAIN')
            ->answer(DnsRecord::aRecord('a', '1.1.1.1'))
            ->authority(DnsRecord::txtRecord('b', 'note'))
            ->additional(DnsRecord::aaaaRecord('c', '::1'));

        $arr = $dns->toArray();
        $this->assertSame('NXDOMAIN', $arr['responseCode']);
        $this->assertCount(1, $arr['answerRecords']);
        $this->assertCount(1, $arr['authorityRecords']);
        $this->assertCount(1, $arr['additionalRecords']);
        $this->assertSame('1.1.1.1', $arr['answerRecords'][0]['value']);
    }

    public function testOpenApiExpectationToArray(): void
    {
        $this->assertSame(
            ['specUrlOrPayload' => 'spec.yaml'],
            OpenAPIExpectation::openAPI('spec.yaml')->toArray()
        );

        $this->assertSame(
            [
                'specUrlOrPayload' => 'spec.yaml',
                'operationsAndResponses' => ['op' => '200'],
            ],
            OpenAPIExpectation::openAPI('spec.yaml', ['op' => '200'])->toArray()
        );
    }

    public function testHttpWebSocketResponseGraphqlSubscriptionFilter(): void
    {
        $ws = HttpWebSocketResponse::response()
            ->subprotocol('graphql-transport-ws')
            ->graphqlSubscriptionFilter(
                GraphQLSubscriptionFilter::query('subscription OnMessage { messageAdded { id body } }')
                    ->operationName('OnMessage')
                    ->selectionSetMatchType(GraphQLSubscriptionFilter::AST_SUBSET)
                    ->fields(['messageAdded']),
            );

        $arr = $ws->toArray();
        $this->assertArrayHasKey('graphqlSubscriptionFilter', $arr);
        $this->assertSame(
            [
                'query' => 'subscription OnMessage { messageAdded { id body } }',
                'operationName' => 'OnMessage',
                'selectionSetMatchType' => 'AST_SUBSET',
                'fields' => ['messageAdded'],
            ],
            $arr['graphqlSubscriptionFilter'],
        );
    }

    public function testGraphqlSubscriptionFilterOmittedWhenNotSet(): void
    {
        $arr = HttpWebSocketResponse::response()->subprotocol('chat')->toArray();

        $this->assertArrayNotHasKey('graphqlSubscriptionFilter', $arr);
    }

    public function testGraphqlSubscriptionFilterEmitsQueryEvenWhenNothingElseSet(): void
    {
        // query is the server's only required property, so it must be emitted
        // unconditionally rather than through an "if set" guard.
        $arr = GraphQLSubscriptionFilter::query('subscription { t }')->toArray();

        $this->assertSame(['query' => 'subscription { t }'], $arr);
    }

    public function testGraphqlSubscriptionFilterMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_websocket_graphql_filter.json
        $fixture = json_decode(
            (string) file_get_contents(
                __DIR__ . '/../../../test-fixtures/expectations/action_websocket_graphql_filter.json',
            ),
            true,
        );

        $built = GraphQLSubscriptionFilter::query($fixture['httpWebSocketResponse']['graphqlSubscriptionFilter']['query'])
            ->operationName('OnMessage')
            ->selectionSetMatchType(GraphQLSubscriptionFilter::AST_SUBSET)
            ->fields(['messageAdded'])
            ->toArray();

        $this->assertSame($fixture['httpWebSocketResponse']['graphqlSubscriptionFilter'], $built);
    }
}
