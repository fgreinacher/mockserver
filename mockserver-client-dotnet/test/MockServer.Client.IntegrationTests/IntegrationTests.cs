using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.IntegrationTests;

/// <summary>
/// Integration tests that require a running MockServer instance.
/// Set the MOCKSERVER_URL environment variable (e.g., "http://localhost:1080") to enable these tests.
/// When MOCKSERVER_URL is not set, all tests in this class are skipped.
/// </summary>
[Collection("Integration")]
public class IntegrationTests : IDisposable
{
    private readonly MockServerClient? _client;

    public IntegrationTests()
    {
        var url = Environment.GetEnvironmentVariable("MOCKSERVER_URL");
        if (string.IsNullOrEmpty(url))
        {
            _client = null;
            return;
        }

        var uri = new Uri(url);
        _client = new MockServerClient(uri.Host, uri.Port, secure: uri.Scheme == "https");
    }

    public void Dispose()
    {
        _client?.Dispose();
    }

    private void SkipIfNoServer()
    {
        // A skip reports green, which is indistinguishable from a pass. CI sets
        // MOCKSERVER_REQUIRE_SERVER=true so a missing server fails the build
        // loudly instead of quietly testing nothing.
        if (_client == null
            && Environment.GetEnvironmentVariable("MOCKSERVER_REQUIRE_SERVER") == "true")
        {
            throw new InvalidOperationException(
                "MOCKSERVER_REQUIRE_SERVER=true but MOCKSERVER_URL is not set — refusing to skip.");
        }

        Skip.If(_client == null, "MOCKSERVER_URL environment variable not set; skipping integration test.");
    }

    [SkippableFact]
    public void Status_ReturnsOk()
    {
        SkipIfNoServer();

        var status = _client!.Status();
        status.PortList.Should().NotBeEmpty();
    }

    [SkippableFact]
    public void When_Respond_ThenVerify_Works()
    {
        SkipIfNoServer();

        // Reset to start clean
        _client!.Reset();

        // Create expectation
        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/integration-test")
        ).Respond(
            HttpResponse.Response().WithStatusCode(200).WithBody("integration-test-response")
        );

        // Verify active expectations
        var active = _client.RetrieveActiveExpectations();
        active.Should().NotBeEmpty();

        // Make a real request to the MockServer
        using var httpClient = new HttpClient();
        var url = Environment.GetEnvironmentVariable("MOCKSERVER_URL")!;
        var response = httpClient.GetAsync($"{url.TrimEnd('/')}/integration-test").Result;
        response.StatusCode.Should().Be(System.Net.HttpStatusCode.OK);

        // Verify the request was received
        _client.Verify(
            HttpRequest.Request().WithMethod("GET").WithPath("/integration-test"),
            VerificationTimes.AtLeastTimes(1)
        );

        // Clean up
        _client.Reset();
    }

    [SkippableFact]
    public void Clear_RemovesExpectations()
    {
        SkipIfNoServer();

        _client!.Reset();

        _client.When(
            HttpRequest.Request().WithPath("/to-clear")
        ).Respond(
            HttpResponse.Response().WithStatusCode(200)
        );

        _client.Clear(HttpRequest.Request().WithPath("/to-clear"));

        var active = _client.RetrieveActiveExpectations();
        active.Should().BeEmpty();
    }

    [SkippableFact]
    public void Verify_Fails_WhenRequestNotReceived()
    {
        SkipIfNoServer();

        _client!.Reset();

        var act = () => _client.Verify(
            HttpRequest.Request().WithPath("/never-called"),
            VerificationTimes.AtLeastTimes(1)
        );

        act.Should().Throw<VerificationException>();
    }

    [SkippableFact]
    public void Forward_CreatesExpectation()
    {
        SkipIfNoServer();

        _client!.Reset();

        _client.When(
            HttpRequest.Request().WithPath("/forward-test")
        ).Forward(
            HttpForward.Forward().WithHost("example.com").WithPort(80)
        );

        var active = _client.RetrieveActiveExpectations();
        active.Should().NotBeEmpty();

        _client.Reset();
    }

    [SkippableFact]
    public void VerifySequence_Works()
    {
        SkipIfNoServer();

        _client!.Reset();

        // Create expectations
        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/seq-first")
        ).Respond(HttpResponse.Response().WithStatusCode(200));

        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/seq-second")
        ).Respond(HttpResponse.Response().WithStatusCode(200));

        // Make requests in order
        using var httpClient = new HttpClient();
        var url = Environment.GetEnvironmentVariable("MOCKSERVER_URL")!.TrimEnd('/');
        httpClient.GetAsync($"{url}/seq-first").Wait();
        httpClient.GetAsync($"{url}/seq-second").Wait();

        // Verify sequence
        _client.VerifySequence(
            HttpRequest.Request().WithPath("/seq-first"),
            HttpRequest.Request().WithPath("/seq-second")
        );

        _client.Reset();
    }
}
