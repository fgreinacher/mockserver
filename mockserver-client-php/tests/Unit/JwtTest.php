<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Jwt;
use PHPUnit\Framework\TestCase;

class JwtTest extends TestCase
{
    public function testEmptyJwt(): void
    {
        $this->assertSame([], Jwt::jwt()->toArray());
    }

    public function testClaimsIncludingRegexAndNegation(): void
    {
        $jwt = Jwt::jwt()
            ->claim('sub', 'user-123')
            ->claim('role', '!admin')
            ->claim('email', '^.+@example.com$')
            ->issuer('https://issuer.example.com')
            ->audience('my-api')
            ->algorithm('RS256');

        $expected = [
            'claims' => [
                'sub' => 'user-123',
                'role' => '!admin',
                'email' => '^.+@example.com$',
            ],
            'issuer' => 'https://issuer.example.com',
            'audience' => 'my-api',
            'algorithm' => 'RS256',
        ];

        $this->assertSame($expected, $jwt->toArray());
    }

    public function testOptionalKeysOmittedWhenUnset(): void
    {
        $array = Jwt::jwt()->claim('sub', 'user-123')->toArray();

        $this->assertSame(['claims' => ['sub' => 'user-123']], $array);
        $this->assertArrayNotHasKey('issuer', $array);
        $this->assertArrayNotHasKey('audience', $array);
        $this->assertArrayNotHasKey('algorithm', $array);
        $this->assertArrayNotHasKey('header', $array);
        $this->assertArrayNotHasKey('scheme', $array);
    }

    public function testHeaderAndScheme(): void
    {
        $jwt = Jwt::jwt()
            ->claims(['sub' => 'user-123'])
            ->header('authorization')
            ->scheme('Bearer');

        $this->assertSame([
            'claims' => ['sub' => 'user-123'],
            'header' => 'authorization',
            'scheme' => 'Bearer',
        ], $jwt->toArray());
    }

    public function testJsonSerialize(): void
    {
        $jwt = Jwt::jwt()->claim('sub', 'user-123')->algorithm('RS256');

        $json = json_encode($jwt, JSON_THROW_ON_ERROR);

        $this->assertSame('{"claims":{"sub":"user-123"},"algorithm":"RS256"}', $json);
    }
}
