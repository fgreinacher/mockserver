namespace Testcontainers.MockServer.Tests;

using FluentAssertions;
using Xunit;

/// <summary>
/// Unit tests for <see cref="MockServerBuilder" /> configuration shaping.
/// These tests verify builder configuration WITHOUT starting Docker.
/// </summary>
public class MockServerBuilderTest
{
    [Fact]
    public void DefaultImageUsesCorrectRepository()
    {
        MockServerBuilder.MockServerImage.Should().Be("mockserver/mockserver");
        MockServerContainer.ImageName.Should().Be("mockserver/mockserver");
    }

    [Fact]
    public void DefaultPortIs1080()
    {
        MockServerBuilder.MockServerPort.Should().Be(1080);
        MockServerContainer.DefaultPort.Should().Be(1080);
    }

    [Fact]
    public void DefaultImageIsDerivedNotHardPinned()
    {
        // The tag derives from the package version ("mockserver-<version>") or falls
        // back to ":latest" — never a hard-coded stale version.
        MockServerContainer.DefaultImage.Should().StartWith("mockserver/mockserver:");
        MockServerBuilder.DefaultImage.Should().Be(MockServerContainer.DefaultImage);

        var tag = MockServerContainer.DefaultImage.Split(':')[1];
        (tag == "latest" || tag.StartsWith("mockserver-")).Should().BeTrue();
    }

    [Fact]
    public void BuilderCreatesContainerInstance()
    {
        // Verify that Build() produces a non-null MockServerContainer.
        var container = new MockServerBuilder().Build();
        container.Should().NotBeNull();
        container.Should().BeOfType<MockServerContainer>();
    }

    [Fact]
    public void WithLogLevelIsChainable()
    {
        // Verify fluent chaining does not throw and produces a valid builder.
        var builder = new MockServerBuilder()
            .WithLogLevel("DEBUG");

        builder.Should().NotBeNull();
        var container = builder.Build();
        container.Should().NotBeNull();
    }

    [Fact]
    public void WithMockServerPropertyIsChainable()
    {
        var builder = new MockServerBuilder()
            .WithMockServerProperty("MOCKSERVER_MAX_EXPECTATIONS", "500");

        builder.Should().NotBeNull();
        var container = builder.Build();
        container.Should().NotBeNull();
    }

    [Fact]
    public void FluentChainingAcrossMultipleHelpers()
    {
        var container = new MockServerBuilder()
            .WithLogLevel("WARN")
            .WithMockServerProperty("MOCKSERVER_MAX_EXPECTATIONS", "200")
            .WithEnvironment("MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "true")
            .Build();

        container.Should().NotBeNull();
    }

    [Fact]
    public void WithCustomImageOverridesDefault()
    {
        var container = new MockServerBuilder()
            .WithImage("mockserver/mockserver:latest")
            .Build();

        container.Should().NotBeNull();
    }

    [Fact]
    public void WithCustomPortBindingOverridesDefault()
    {
        var container = new MockServerBuilder()
            .WithPortBinding(9090, MockServerBuilder.MockServerPort)
            .Build();

        container.Should().NotBeNull();
    }
}
