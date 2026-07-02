<?php

declare(strict_types=1);

namespace MockServer\Testcontainers\Tests\Unit;

use MockServer\Testcontainers\MockServerContainer;
use PHPUnit\Framework\TestCase;
use ReflectionClass;

/**
 * Unit tests for MockServerContainer configuration. These verify container
 * configuration WITHOUT starting Docker — image derivation, environment
 * variables, exposed ports, and fluent helpers.
 */
class MockServerContainerTest extends TestCase
{
    /** @return array<string, mixed> */
    private function readProtected(object $object, string $property): mixed
    {
        $ref = new ReflectionClass($object);
        // Walk up to the declaring class (property lives on GenericContainer).
        while (!$ref->hasProperty($property) && $ref->getParentClass() !== false) {
            $ref = $ref->getParentClass();
        }
        // No setAccessible() call needed: protected/private members are
        // reflection-accessible since PHP 8.1 (the module's minimum), and
        // setAccessible() is deprecated as a no-op from PHP 8.5.
        return $ref->getProperty($property)->getValue($object);
    }

    public function testImageTagDerivesFromReleaseVersion(): void
    {
        self::assertSame('mockserver-7.3.0', MockServerContainer::imageTagForVersion('7.3.0'));
        self::assertSame('mockserver-7.3.0', MockServerContainer::imageTagForVersion('v7.3.0'));
    }

    public function testImageTagFallsBackToLatest(): void
    {
        self::assertSame('latest', MockServerContainer::imageTagForVersion(null));
        self::assertSame('latest', MockServerContainer::imageTagForVersion('dev-master'));
        self::assertSame('latest', MockServerContainer::imageTagForVersion('7.3.0-SNAPSHOT'));
    }

    public function testDefaultImageIsMockServerImage(): void
    {
        self::assertStringStartsWith('mockserver/mockserver:', MockServerContainer::defaultImage());
    }

    public function testDefaultPortConstant(): void
    {
        self::assertSame(1080, MockServerContainer::DEFAULT_PORT);
    }

    public function testDefaultExposesPort1080AndSetsServerPortEnv(): void
    {
        $container = new MockServerContainer();
        self::assertContains('1080/tcp', $this->readProtected($container, 'exposedPorts'));
        self::assertSame('1080', $this->readProtected($container, 'env')['SERVER_PORT']);
    }

    public function testCustomImageIsUsed(): void
    {
        $container = new MockServerContainer('mockserver/mockserver:latest');
        self::assertSame('mockserver/mockserver:latest', $this->readProtected($container, 'image'));
    }

    public function testWithServerPortReplacesExposedPort(): void
    {
        $container = (new MockServerContainer())->withServerPort(9090);
        $ports = $this->readProtected($container, 'exposedPorts');
        self::assertContains('9090/tcp', $ports);
        self::assertNotContains('1080/tcp', $ports);
        self::assertSame('9090', $this->readProtected($container, 'env')['SERVER_PORT']);
    }

    public function testWithLogLevelSetsEnv(): void
    {
        $container = (new MockServerContainer())->withLogLevel('DEBUG');
        self::assertSame('DEBUG', $this->readProtected($container, 'env')['MOCKSERVER_LOG_LEVEL']);
    }

    public function testWithMockServerPropertySetsEnv(): void
    {
        $container = (new MockServerContainer())->withMockServerProperty('MOCKSERVER_MAX_EXPECTATIONS', '500');
        self::assertSame('500', $this->readProtected($container, 'env')['MOCKSERVER_MAX_EXPECTATIONS']);
    }

    public function testWithInitializationJsonSetsEnv(): void
    {
        $container = (new MockServerContainer())->withInitializationJson(__FILE__);
        self::assertSame(
            '/config/initializerJson.json',
            $this->readProtected($container, 'env')['MOCKSERVER_INITIALIZATION_JSON_PATH']
        );
    }

    public function testFluentChaining(): void
    {
        $container = (new MockServerContainer())
            ->withLogLevel('WARN')
            ->withServerPort(8080)
            ->withMockServerProperty('MOCKSERVER_MAX_EXPECTATIONS', '100');
        $env = $this->readProtected($container, 'env');
        self::assertSame('WARN', $env['MOCKSERVER_LOG_LEVEL']);
        self::assertSame('8080', $env['SERVER_PORT']);
        self::assertSame('100', $env['MOCKSERVER_MAX_EXPECTATIONS']);
        self::assertContains('8080/tcp', $this->readProtected($container, 'exposedPorts'));
    }
}
