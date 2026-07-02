<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\HttpRequest;
use PHPUnit\Framework\TestCase;

class HttpRequestTest extends TestCase
{
    public function testEmptyRequest(): void
    {
        $request = HttpRequest::request();
        $this->assertSame([], $request->toArray());
    }

    public function testMethodAndPath(): void
    {
        $request = HttpRequest::request()
            ->method('GET')
            ->path('/hello');

        $this->assertSame([
            'method' => 'GET',
            'path' => '/hello',
        ], $request->toArray());
    }

    public function testQueryStringParameters(): void
    {
        $request = HttpRequest::request()
            ->path('/search')
            ->queryStringParameter('q', 'mockserver')
            ->queryStringParameter('page', '1', '2');

        $expected = [
            'path' => '/search',
            'queryStringParameters' => [
                'q' => ['mockserver'],
                'page' => ['1', '2'],
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testHeaders(): void
    {
        $request = HttpRequest::request()
            ->header('Accept', 'application/json')
            ->header('X-Custom', 'value1', 'value2');

        $expected = [
            'headers' => [
                'Accept' => ['application/json'],
                'X-Custom' => ['value1', 'value2'],
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testCookies(): void
    {
        $request = HttpRequest::request()
            ->cookie('session', 'abc123');

        $expected = [
            'cookies' => [
                'session' => ['abc123'],
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testStringBody(): void
    {
        $request = HttpRequest::request()
            ->method('POST')
            ->body('plain text body');

        $expected = [
            'method' => 'POST',
            'body' => 'plain text body',
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testJsonBody(): void
    {
        $request = HttpRequest::request()
            ->method('POST')
            ->jsonBody(['key' => 'value']);

        $array = $request->toArray();

        $this->assertSame('POST', $array['method']);
        $this->assertSame('JSON', $array['body']['type']);
        $this->assertSame('{"key":"value"}', $array['body']['json']);
    }

    public function testJsonBodyFromString(): void
    {
        $request = HttpRequest::request()
            ->jsonBody('{"already":"json"}');

        $array = $request->toArray();

        $this->assertSame('JSON', $array['body']['type']);
        $this->assertSame('{"already":"json"}', $array['body']['json']);
    }

    public function testFileBody(): void
    {
        $request = HttpRequest::request()
            ->method('POST')
            ->fileBody('/path/to/request.json');

        $expected = [
            'method' => 'POST',
            'body' => [
                'type' => 'FILE',
                'filePath' => '/path/to/request.json',
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testFileBodyWithContentType(): void
    {
        $request = HttpRequest::request()
            ->fileBody('/data/payload.xml', 'application/xml');

        $array = $request->toArray();

        $this->assertSame('FILE', $array['body']['type']);
        $this->assertSame('/data/payload.xml', $array['body']['filePath']);
        $this->assertSame('application/xml', $array['body']['contentType']);
        $this->assertArrayNotHasKey('templateType', $array['body']);
    }

    public function testFileBodyWithTemplateType(): void
    {
        $request = HttpRequest::request()
            ->fileBody('/templates/request.vm', 'application/json', 'VELOCITY');

        $expected = [
            'body' => [
                'type' => 'FILE',
                'filePath' => '/templates/request.vm',
                'contentType' => 'application/json',
                'templateType' => 'VELOCITY',
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testKeepAliveAndSecure(): void
    {
        $request = HttpRequest::request()
            ->keepAlive(true)
            ->secure(false);

        $expected = [
            'keepAlive' => true,
            'secure' => false,
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testJwtMatcher(): void
    {
        $request = HttpRequest::request()
            ->method('GET')
            ->path('/secure')
            ->jwt(
                \MockServer\Jwt::jwt()
                    ->claim('sub', 'user-123')
                    ->claim('role', '!admin')
                    ->claim('email', '^.+@example.com$')
                    ->issuer('https://issuer.example.com')
                    ->audience('my-api')
                    ->algorithm('RS256')
            );

        $expected = [
            'method' => 'GET',
            'path' => '/secure',
            'jwt' => [
                'claims' => [
                    'sub' => 'user-123',
                    'role' => '!admin',
                    'email' => '^.+@example.com$',
                ],
                'issuer' => 'https://issuer.example.com',
                'audience' => 'my-api',
                'algorithm' => 'RS256',
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testJwtMatcherWithHeaderAndScheme(): void
    {
        $request = HttpRequest::request()
            ->jwt(
                \MockServer\Jwt::jwt()
                    ->claims(['sub' => 'user-123', 'role' => '!admin', 'email' => '^.+@example.com$'])
                    ->issuer('https://issuer.example.com')
                    ->audience('my-api')
                    ->algorithm('RS256')
                    ->header('authorization')
                    ->scheme('Bearer')
            );

        $json = json_encode($request, JSON_THROW_ON_ERROR);

        $this->assertSame(
            '{"jwt":{"claims":{"sub":"user-123","role":"!admin","email":"^.+@example.com$"},'
            . '"issuer":"https:\/\/issuer.example.com","audience":"my-api","algorithm":"RS256",'
            . '"header":"authorization","scheme":"Bearer"}}',
            $json
        );
    }

    public function testJwtMatcherFromArray(): void
    {
        $request = HttpRequest::request()
            ->jwt(['claims' => ['sub' => 'user-123']]);

        $this->assertSame(['jwt' => ['claims' => ['sub' => 'user-123']]], $request->toArray());
    }

    public function testAllOfBody(): void
    {
        $request = HttpRequest::request()
            ->method('POST')
            ->allOfBody([
                HttpRequest::jsonPathBody('$.name'),
                HttpRequest::regexBody('.*active.*'),
            ]);

        $expected = [
            'method' => 'POST',
            'body' => [
                'type' => 'ALL_OF',
                'bodyAllOf' => [
                    ['type' => 'JSON_PATH', 'jsonPath' => '$.name'],
                    ['type' => 'REGEX', 'regex' => '.*active.*'],
                ],
            ],
        ];

        $this->assertSame($expected, $request->toArray());
    }

    public function testAllOfBodyJsonSerialisation(): void
    {
        $request = HttpRequest::request()
            ->allOfBody([
                HttpRequest::jsonPathBody('$.name'),
                HttpRequest::regexBody('.*active.*'),
            ]);

        $json = json_encode($request->toArray()['body'], JSON_THROW_ON_ERROR);

        $this->assertSame(
            '{"type":"ALL_OF","bodyAllOf":[{"type":"JSON_PATH","jsonPath":"$.name"},'
            . '{"type":"REGEX","regex":".*active.*"}]}',
            $json
        );
    }

    public function testJsonSerialize(): void
    {
        $request = HttpRequest::request()
            ->method('DELETE')
            ->path('/resource/123');

        $json = json_encode($request, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame('DELETE', $decoded['method']);
        $this->assertSame('/resource/123', $decoded['path']);
    }

    public function testFluentChaining(): void
    {
        $request = HttpRequest::request()
            ->method('PUT')
            ->path('/api/items')
            ->header('Content-Type', 'application/json')
            ->queryStringParameter('version', '2')
            ->body('{"name":"item"}');

        $array = $request->toArray();

        $this->assertSame('PUT', $array['method']);
        $this->assertSame('/api/items', $array['path']);
        $this->assertSame(['application/json'], $array['headers']['Content-Type']);
        $this->assertSame(['2'], $array['queryStringParameters']['version']);
        $this->assertSame('{"name":"item"}', $array['body']);
    }

    public function testGetters(): void
    {
        $request = HttpRequest::request()
            ->method('PATCH')
            ->path('/test');

        $this->assertSame('PATCH', $request->getMethod());
        $this->assertSame('/test', $request->getPath());
        $this->assertSame([], $request->getHeaders());
        $this->assertSame([], $request->getQueryStringParameters());
        $this->assertSame([], $request->getCookies());
        $this->assertNull($request->getBody());
    }
}
