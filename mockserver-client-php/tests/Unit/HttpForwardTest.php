<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Delay;
use MockServer\HttpForward;
use PHPUnit\Framework\TestCase;

class HttpForwardTest extends TestCase
{
    public function testEmptyForward(): void
    {
        $forward = HttpForward::forward();
        $this->assertSame([], $forward->toArray());
    }

    public function testFullForward(): void
    {
        $forward = HttpForward::forward()
            ->host('backend.example.com')
            ->port(8080)
            ->scheme('HTTPS');

        $expected = [
            'host' => 'backend.example.com',
            'port' => 8080,
            'scheme' => 'HTTPS',
        ];

        $this->assertSame($expected, $forward->toArray());
    }

    public function testSchemeUppercased(): void
    {
        $forward = HttpForward::forward()->scheme('http');
        $this->assertSame('HTTP', $forward->toArray()['scheme']);
    }

    public function testGetters(): void
    {
        $forward = HttpForward::forward()
            ->host('localhost')
            ->port(9090)
            ->scheme('HTTP');

        $this->assertSame('localhost', $forward->getHost());
        $this->assertSame(9090, $forward->getPort());
        $this->assertSame('HTTP', $forward->getScheme());
    }

    public function testJsonSerialize(): void
    {
        $forward = HttpForward::forward()
            ->host('api.example.com')
            ->port(443)
            ->scheme('HTTPS');

        $json = json_encode($forward, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame('api.example.com', $decoded['host']);
        $this->assertSame(443, $decoded['port']);
        $this->assertSame('HTTPS', $decoded['scheme']);
    }

    public function testPrimaryIsSerialised(): void
    {
        $forward = HttpForward::forward()->host('example.com')->primary(true);

        $this->assertArrayHasKey('primary', $forward->toArray());
        $this->assertTrue($forward->toArray()['primary']);
    }

    public function testPrimaryFalseIsSerialisedNotOmitted(): void
    {
        $arr = HttpForward::forward()->host('example.com')->primary(false)->toArray();

        $this->assertArrayHasKey('primary', $arr);
        $this->assertFalse($arr['primary']);
    }

    public function testPrimaryOmittedWhenNotSet(): void
    {
        $this->assertArrayNotHasKey('primary', HttpForward::forward()->host('example.com')->toArray());
    }

    public function testDelayIsSerialised(): void
    {
        $arr = HttpForward::forward()->host('example.com')->delay(Delay::seconds(2))->toArray();

        $this->assertSame(['timeUnit' => 'SECONDS', 'value' => 2], $arr['delay']);
    }

    public function testZeroDelayIsSerialisedNotOmitted(): void
    {
        $arr = HttpForward::forward()->host('example.com')->delay(Delay::milliseconds(0))->toArray();

        $this->assertArrayHasKey('delay', $arr);
        $this->assertSame(['timeUnit' => 'MILLISECONDS', 'value' => 0], $arr['delay']);
    }

    public function testDelayOmittedWhenNotSet(): void
    {
        $this->assertArrayNotHasKey('delay', HttpForward::forward()->host('example.com')->toArray());
    }
}
