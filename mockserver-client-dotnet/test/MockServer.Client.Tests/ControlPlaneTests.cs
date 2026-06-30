using System.Net;
using System.Text;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the typed control-plane helpers (clock, metrics, configuration, pact,
/// file store, import, operating mode, WSDL). They use a fake HttpMessageHandler to assert
/// the request the client sends (method, path, query, body) and the behaviour it derives
/// from the response, without needing a live MockServer.
/// </summary>
public class ControlPlaneTests
{
    /// <summary>
    /// Captures requests and returns a pre-configured response. Supports byte-body responses
    /// (for the file retrieve path) by encoding <see cref="ResponseBody"/> as UTF-8.
    /// </summary>
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public string? LastRequestBody { get; private set; }
        public HttpStatusCode ResponseStatusCode { get; set; } = HttpStatusCode.OK;
        public string ResponseBody { get; set; } = "";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            LastRequestBody = request.Content != null
                ? await request.Content.ReadAsStringAsync(cancellationToken)
                : null;

            return new HttpResponseMessage(ResponseStatusCode)
            {
                Content = new StringContent(ResponseBody, Encoding.UTF8, "application/json")
            };
        }
    }

    private static (MockServerClient Client, FakeHandler Handler) CreateClient()
    {
        var handler = new FakeHandler();
        var httpClient = new HttpClient(handler);
        var client = new MockServerClient("http://localhost:1080", httpClient);
        return (client, handler);
    }

    // ---------------- Clock ----------------

    [Fact]
    public void FreezeClock_WithInstant_SendsFreezeAction()
    {
        var (client, handler) = CreateClient();
        client.FreezeClock("2024-01-01T00:00:00Z");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/clock");
        handler.LastRequestBody.Should().Contain("\"action\":\"freeze\"");
        handler.LastRequestBody.Should().Contain("\"instant\":\"2024-01-01T00:00:00Z\"");
    }

    [Fact]
    public void FreezeClock_WithoutInstant_OmitsInstant()
    {
        var (client, handler) = CreateClient();
        client.FreezeClock();

        handler.LastRequestBody.Should().Contain("\"action\":\"freeze\"");
        handler.LastRequestBody.Should().NotContain("instant");
    }

    [Fact]
    public void AdvanceClock_SendsAdvanceActionWithDuration()
    {
        var (client, handler) = CreateClient();
        client.AdvanceClock(5000);

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/clock");
        handler.LastRequestBody.Should().Contain("\"action\":\"advance\"");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":5000");
    }

    [Fact]
    public void ResetClock_SendsResetAction()
    {
        var (client, handler) = CreateClient();
        client.ResetClock();

        handler.LastRequestBody.Should().Contain("\"action\":\"reset\"");
    }

    [Fact]
    public void ClockStatus_GetsAndReturnsBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"currentEpochMillis\":123,\"frozen\":true}";

        var status = client.ClockStatus();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/clock");
        status.Should().Contain("\"frozen\":true");
    }

    // ---------------- Metrics ----------------

    [Fact]
    public void RetrieveMetrics_PutsToRetrieveMetrics()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"EXPECTATION_MATCHED\":7}";

        var body = client.RetrieveMetrics();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/retrieve?type=METRICS");
        body.Should().Contain("EXPECTATION_MATCHED");
    }

    [Fact]
    public void ScrapeMetrics_GetsPrometheusText()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "# HELP mockserver_requests Total\nmockserver_requests 3";

        var text = client.ScrapeMetrics();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/metrics");
        text.Should().Contain("mockserver_requests");
    }

    // ---------------- Configuration ----------------

    [Fact]
    public void RetrieveConfiguration_GetsConfiguration()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"logLevel\":\"INFO\"}";

        var body = client.RetrieveConfiguration();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/configuration");
        body.Should().Contain("logLevel");
    }

    [Fact]
    public void UpdateConfiguration_PutsBodyAndReturnsUpdated()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"logLevel\":\"DEBUG\"}";

        var body = client.UpdateConfiguration("{\"logLevel\":\"DEBUG\"}");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/configuration");
        handler.LastRequestBody.Should().Contain("DEBUG");
        body.Should().Contain("DEBUG");
    }

    [Fact]
    public void UpdateConfiguration_ThrowsOnBadRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "Invalid configuration JSON";

        var act = () => client.UpdateConfiguration("{bad}");
        act.Should().Throw<MockServerClientException>().WithMessage("*Invalid configuration*");
    }

    // ---------------- Pact ----------------

    [Fact]
    public void PactImport_PutsToPactImportAndReturnsExpectations()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        handler.ResponseBody = "[{\"httpRequest\":{\"path\":\"/a\"}}]";

        var result = client.PactImport("{\"consumer\":{}}");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/pact/import");
        result.Should().HaveCount(1);
    }

    [Fact]
    public void PactImport_ThrowsOnBlankJson()
    {
        var (client, _) = CreateClient();
        var act = () => client.PactImport("  ");
        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void PactExport_PutsWithConsumerAndProviderQuery()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"interactions\":[]}";

        var body = client.PactExport("consumerA", "providerB");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/pact?consumer=consumerA&provider=providerB");
        body.Should().Contain("interactions");
    }

    [Fact]
    public void PactExport_NoArgs_HasNoQuery()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{}";

        client.PactExport();

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/pact");
    }

    [Fact]
    public void PactVerify_Returns202AsPass()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Accepted;
        handler.ResponseBody = "{\"valid\":true}";

        var pass = client.PactVerify("{\"interactions\":[]}");

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/pact/verify");
        pass.Should().BeTrue();
    }

    [Fact]
    public void PactVerify_Returns406AsFail()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotAcceptable;
        handler.ResponseBody = "{\"valid\":false}";

        var pass = client.PactVerify("{\"interactions\":[]}");

        pass.Should().BeFalse();
    }

    // ---------------- File store ----------------

    [Fact]
    public void StoreFile_PutsNameAndContent()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        handler.ResponseBody = "{\"name\":\"a.txt\",\"size\":5}";

        client.StoreFile("a.txt", "hello");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/files/store");
        handler.LastRequestBody.Should().Contain("\"name\":\"a.txt\"");
        handler.LastRequestBody.Should().Contain("\"content\":\"hello\"");
    }

    [Fact]
    public void RetrieveFile_ReturnsRawBytes()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "file-contents";

        var bytes = client.RetrieveFile("a.txt");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/files/retrieve");
        handler.LastRequestBody.Should().Contain("\"name\":\"a.txt\"");
        Encoding.UTF8.GetString(bytes).Should().Be("file-contents");
    }

    [Fact]
    public void RetrieveFile_ThrowsOn404()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;
        handler.ResponseBody = "file not found: a.txt";

        var act = () => client.RetrieveFile("a.txt");
        act.Should().Throw<MockServerClientException>().WithMessage("*not found*");
    }

    [Fact]
    public void ListFiles_ReturnsNameArray()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "[\"a.txt\",\"b.txt\"]";

        var files = client.ListFiles();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/files/list");
        files.Should().BeEquivalentTo(new[] { "a.txt", "b.txt" });
    }

    [Fact]
    public void DeleteFile_PutsName()
    {
        var (client, handler) = CreateClient();

        client.DeleteFile("a.txt");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/files/delete");
        handler.LastRequestBody.Should().Contain("\"name\":\"a.txt\"");
    }

    [Fact]
    public void DeleteFile_ThrowsOn404()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;
        handler.ResponseBody = "file not found: a.txt";

        var act = () => client.DeleteFile("a.txt");
        act.Should().Throw<MockServerClientException>().WithMessage("*not found*");
    }

    // ---------------- Import ----------------

    [Fact]
    public void ImportHar_PutsWithFormatHar()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        handler.ResponseBody = "[{\"httpRequest\":{\"path\":\"/x\"}}]";

        var result = client.ImportHar("{\"log\":{\"entries\":[]}}");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/import?format=har");
        result.Should().HaveCount(1);
    }

    [Fact]
    public void ImportPostmanCollection_PutsWithFormatPostman()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        handler.ResponseBody = "[]";

        client.ImportPostmanCollection("{\"info\":{},\"item\":[]}");

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/import?format=postman");
    }

    [Fact]
    public void ImportHar_ThrowsOnBlank()
    {
        var (client, _) = CreateClient();
        var act = () => client.ImportHar("");
        act.Should().Throw<ArgumentException>();
    }

    // ---------------- Operating mode ----------------

    [Fact]
    public void SetMode_PutsModeQuery()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"mode\":\"SPY\",\"proxyUnmatchedRequests\":true}";

        client.SetMode(MockMode.Spy);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/mode?mode=SPY");
    }

    [Fact]
    public void RetrieveMode_GetsAndParsesMode()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"mode\":\"CAPTURE\",\"proxyUnmatchedRequests\":true}";

        var mode = client.RetrieveMode();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/mode");
        mode.Should().Be(MockMode.Capture);
    }

    // ---------------- WSDL ----------------

    [Fact]
    public void WsdlExpectation_PutsRawXmlBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        handler.ResponseBody = "[{\"httpRequest\":{\"path\":\"/ws\"}}]";

        var result = client.WsdlExpectation("<definitions/>");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/wsdl");
        handler.LastRequestBody.Should().Be("<definitions/>");
        handler.LastRequest!.Content!.Headers.ContentType!.MediaType.Should().Be("text/xml");
        result.Should().HaveCount(1);
    }

    [Fact]
    public void WsdlExpectation_ThrowsOnBlank()
    {
        var (client, _) = CreateClient();
        var act = () => client.WsdlExpectation("   ");
        act.Should().Throw<ArgumentException>();
    }
}
