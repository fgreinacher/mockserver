<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\ConnectionOptions;
use MockServer\Delay;
use MockServer\HttpResponse;
use PHPUnit\Framework\TestCase;

class HttpResponseTest extends TestCase
{
    public function testEmptyResponse(): void
    {
        $response = HttpResponse::response();
        $this->assertSame([], $response->toArray());
    }

    public function testStatusCode(): void
    {
        $response = HttpResponse::response()->statusCode(201);

        $this->assertSame(['statusCode' => 201], $response->toArray());
    }

    public function testReasonPhrase(): void
    {
        $response = HttpResponse::response()
            ->statusCode(404)
            ->reasonPhrase('Not Found');

        $this->assertSame([
            'statusCode' => 404,
            'reasonPhrase' => 'Not Found',
        ], $response->toArray());
    }

    public function testHeaders(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->header('Content-Type', 'application/json')
            ->header('X-Custom', 'a', 'b');

        $expected = [
            'statusCode' => 200,
            'headers' => [
                'Content-Type' => ['application/json'],
                'X-Custom' => ['a', 'b'],
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testCookies(): void
    {
        $response = HttpResponse::response()
            ->cookie('session', 'xyz');

        $expected = [
            'cookies' => [
                'session' => ['xyz'],
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testStringBody(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->body('hello world');

        $expected = [
            'statusCode' => 200,
            'body' => 'hello world',
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testJsonBody(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->jsonBody(['message' => 'ok']);

        $array = $response->toArray();

        $this->assertSame(200, $array['statusCode']);
        $this->assertSame('JSON', $array['body']['type']);
        $this->assertSame('{"message":"ok"}', $array['body']['json']);
    }

    public function testDelay(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->delay(Delay::milliseconds(500));

        $expected = [
            'statusCode' => 200,
            'delay' => [
                'timeUnit' => 'MILLISECONDS',
                'value' => 500,
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testConnectionOptions(): void
    {
        $opts = (new ConnectionOptions())
            ->closeSocket(true)
            ->suppressContentLengthHeader(true);

        $response = HttpResponse::response()
            ->statusCode(200)
            ->connectionOptions($opts);

        $array = $response->toArray();

        $this->assertSame(200, $array['statusCode']);
        $this->assertTrue($array['connectionOptions']['closeSocket']);
        $this->assertTrue($array['connectionOptions']['suppressContentLengthHeader']);
    }

    public function testFileBody(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->fileBody('/path/to/file.html');

        $expected = [
            'statusCode' => 200,
            'body' => [
                'type' => 'FILE',
                'filePath' => '/path/to/file.html',
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testFileBodyWithContentType(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->fileBody('/path/to/data.json', 'application/json');

        $array = $response->toArray();

        $this->assertSame('FILE', $array['body']['type']);
        $this->assertSame('/path/to/data.json', $array['body']['filePath']);
        $this->assertSame('application/json', $array['body']['contentType']);
        $this->assertArrayNotHasKey('templateType', $array['body']);
    }

    public function testFileBodyWithTemplateType(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->fileBody('/templates/response.html', 'text/html', 'VELOCITY');

        $expected = [
            'statusCode' => 200,
            'body' => [
                'type' => 'FILE',
                'filePath' => '/templates/response.html',
                'contentType' => 'text/html',
                'templateType' => 'VELOCITY',
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testFileBodyWithMustacheTemplateType(): void
    {
        $response = HttpResponse::response()
            ->fileBody('/templates/response.mustache', null, 'MUSTACHE');

        $expected = [
            'body' => [
                'type' => 'FILE',
                'filePath' => '/templates/response.mustache',
                'templateType' => 'MUSTACHE',
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testJsonSerialize(): void
    {
        $response = HttpResponse::response()
            ->statusCode(204);

        $json = json_encode($response, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame(204, $decoded['statusCode']);
    }

    public function testGetters(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->body('test');

        $this->assertSame(200, $response->getStatusCode());
        $this->assertSame('test', $response->getBody());
        $this->assertSame([], $response->getHeaders());
        $this->assertNull($response->getDelay());
    }

    public function testPrimaryIsSerialised(): void
    {
        $response = HttpResponse::response()->statusCode(200)->primary(true);

        $this->assertArrayHasKey('primary', $response->toArray());
        $this->assertTrue($response->toArray()['primary']);
    }

    public function testPrimaryFalseIsSerialisedNotOmitted(): void
    {
        // false is meaningful: it marks a secondary action on a multi-action
        // expectation. Treating it like "unset" is how the selector goes missing.
        $response = HttpResponse::response()->statusCode(200)->primary(false);

        $arr = $response->toArray();
        $this->assertArrayHasKey('primary', $arr);
        $this->assertFalse($arr['primary']);
    }

    public function testPrimaryOmittedWhenNotSet(): void
    {
        $this->assertArrayNotHasKey('primary', HttpResponse::response()->statusCode(200)->toArray());
    }
}
