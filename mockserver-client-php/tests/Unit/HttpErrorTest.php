<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\HttpError;
use PHPUnit\Framework\TestCase;

class HttpErrorTest extends TestCase
{
    public function testEmptyError(): void
    {
        $error = HttpError::error();
        $this->assertSame([], $error->toArray());
    }

    public function testDropConnection(): void
    {
        $error = HttpError::error()->dropConnection(true);

        $this->assertSame(['dropConnection' => true], $error->toArray());
    }

    public function testResponseBytes(): void
    {
        $bytes = base64_encode('garbage');
        $error = HttpError::error()
            ->dropConnection(true)
            ->responseBytes($bytes);

        $expected = [
            'dropConnection' => true,
            'responseBytes' => $bytes,
        ];

        $this->assertSame($expected, $error->toArray());
    }

    public function testJsonSerialize(): void
    {
        $error = HttpError::error()->dropConnection(false);

        $json = json_encode($error, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertFalse($decoded['dropConnection']);
    }

    public function testPrimaryIsSerialised(): void
    {
        $arr = HttpError::error()->dropConnection(true)->primary(true)->toArray();

        $this->assertArrayHasKey('primary', $arr);
        $this->assertTrue($arr['primary']);
    }

    public function testPrimaryFalseIsSerialisedNotOmitted(): void
    {
        $arr = HttpError::error()->dropConnection(true)->primary(false)->toArray();

        $this->assertArrayHasKey('primary', $arr);
        $this->assertFalse($arr['primary']);
    }

    public function testStreamErrorIsSerialised(): void
    {
        $arr = HttpError::error()->streamError(2)->toArray();

        $this->assertArrayHasKey('streamError', $arr);
        $this->assertSame(2, $arr['streamError']);
    }

    public function testStreamErrorZeroIsSerialisedNotOmitted(): void
    {
        // 0 is a valid protocol error code (NO_ERROR); it must not be dropped
        // by a falsy check.
        $arr = HttpError::error()->streamError(0)->toArray();

        $this->assertArrayHasKey('streamError', $arr);
        $this->assertSame(0, $arr['streamError']);
    }

    public function testMatchesTheSharedStreamResetFixture(): void
    {
        // test-fixtures/expectations/action_error_stream_reset.json
        $arr = HttpError::error()->streamError(2)->dropConnection(true)->primary(false)->toArray();

        $this->assertSame(
            ['dropConnection' => true, 'streamError' => 2, 'primary' => false],
            $arr,
        );
    }

    public function testFieldsOmittedWhenNotSet(): void
    {
        $arr = HttpError::error()->dropConnection(true)->toArray();

        $this->assertArrayNotHasKey('primary', $arr);
        $this->assertArrayNotHasKey('streamError', $arr);
    }
}
