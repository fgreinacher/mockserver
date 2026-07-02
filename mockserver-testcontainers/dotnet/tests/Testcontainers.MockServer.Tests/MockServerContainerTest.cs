namespace Testcontainers.MockServer.Tests;

using System.Net;
using System.Net.Http;
using System.Text;
using DotNet.Testcontainers.Builders;
using FluentAssertions;
using Xunit;

/// <summary>
/// Integration tests for <see cref="MockServerContainer" /> that require a running Docker daemon.
/// Tests are skipped when Docker is not available, matching the repo's assumeTrue(isDockerAvailable())
/// convention.
/// </summary>
[Trait("Category", "Integration")]
public sealed class MockServerContainerTest : IAsyncLifetime
{
    private readonly MockServerContainer _container = new MockServerBuilder().Build();
    private bool _dockerAvailable;

    public async Task InitializeAsync()
    {
        _dockerAvailable = await IsDockerAvailableAsync();
        if (_dockerAvailable)
        {
            await _container.StartAsync();
        }
    }

    public async Task DisposeAsync()
    {
        if (_dockerAvailable)
        {
            await _container.DisposeAsync();
        }
    }

    [SkippableFact]
    public async Task ContainerStartsAndStatusEndpointResponds()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        using var httpClient = new HttpClient();

        // PUT /mockserver/status should return 200 with ports info
        var statusRequest = new HttpRequestMessage(HttpMethod.Put, $"{_container.GetUrl()}/mockserver/status");
        var statusResponse = await httpClient.SendAsync(statusRequest);
        statusResponse.StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [SkippableFact]
    public async Task ContainerAcceptsMockExpectationAndServesResponse()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        using var httpClient = new HttpClient();

        // Create an expectation
        var expectationJson = """
            {
                "httpRequest": {
                    "method": "GET",
                    "path": "/hello"
                },
                "httpResponse": {
                    "statusCode": 200,
                    "headers": {
                        "Content-Type": ["text/plain"]
                    },
                    "body": "world"
                }
            }
            """;

        var expectationRequest = new HttpRequestMessage(HttpMethod.Put, $"{_container.GetUrl()}/mockserver/expectation")
        {
            Content = new StringContent(expectationJson, Encoding.UTF8, "application/json")
        };

        var expectationResponse = await httpClient.SendAsync(expectationRequest);
        expectationResponse.StatusCode.Should().Be(HttpStatusCode.Created);

        // Issue a matching request
        var helloResponse = await httpClient.GetAsync($"{_container.GetUrl()}/hello");
        helloResponse.StatusCode.Should().Be(HttpStatusCode.OK);

        var body = await helloResponse.Content.ReadAsStringAsync();
        body.Should().Be("world");
    }

    [SkippableFact]
    public async Task GetUrlReturnsHttpEndpoint()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        _container.GetUrl().Should().StartWith("http://");
        _container.GetUrl().Should().Contain(":");
    }

    [SkippableFact]
    public async Task GetSecureUrlReturnsHttpsEndpoint()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        _container.GetSecureUrl().Should().StartWith("https://");
    }

    [SkippableFact]
    public async Task GetConnectionStringReturnsHostPort()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        var connectionString = _container.GetConnectionString();
        connectionString.Should().Contain(":");
        // Should be parseable as host:port
        var parts = connectionString.Split(':');
        parts.Should().HaveCount(2);
        int.TryParse(parts[1], out var port).Should().BeTrue();
        port.Should().BeGreaterThan(0);
    }

    [SkippableFact]
    public async Task GetClientReturnsWiredClient()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        // GetClient() returns a MockServerClient wired to the mapped host/port; a
        // control-plane reset proves it reaches the container.
        var client = _container.GetClient();
        client.Should().NotBeNull();
        await client.ResetAsync();

        // The client is cached across calls.
        _container.GetClient().Should().BeSameAs(client);
    }

    [SkippableFact]
    public async Task ResetClearsExpectations()
    {
        Skip.IfNot(_dockerAvailable, "Docker is not available");

        using var httpClient = new HttpClient();

        // Create an expectation
        var expectationJson = """
            {
                "httpRequest": { "method": "GET", "path": "/temp" },
                "httpResponse": { "statusCode": 200, "body": "temporary" }
            }
            """;

        var createRequest = new HttpRequestMessage(HttpMethod.Put, $"{_container.GetUrl()}/mockserver/expectation")
        {
            Content = new StringContent(expectationJson, Encoding.UTF8, "application/json")
        };
        await httpClient.SendAsync(createRequest);

        // Reset
        var resetRequest = new HttpRequestMessage(HttpMethod.Put, $"{_container.GetUrl()}/mockserver/reset");
        var resetResponse = await httpClient.SendAsync(resetRequest);
        resetResponse.StatusCode.Should().Be(HttpStatusCode.OK);

        // The expectation should no longer match (404 = no matching expectation)
        var response = await httpClient.GetAsync($"{_container.GetUrl()}/temp");
        response.StatusCode.Should().NotBe(HttpStatusCode.OK);
    }

    private static async Task<bool> IsDockerAvailableAsync()
    {
        try
        {
            // Attempt to create and start a minimal container to verify Docker is reachable.
            var container = new ContainerBuilder("alpine:3.19")
                .WithEntrypoint("true")
                .Build();

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await container.StartAsync(cts.Token);
            await container.DisposeAsync();
            return true;
        }
        catch
        {
            return false;
        }
    }
}
