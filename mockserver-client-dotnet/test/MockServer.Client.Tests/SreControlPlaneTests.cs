using System.Net;
using System.Net.Http;
using System.Text;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the SRE control-plane methods (load scenarios, service chaos, SLO verdicts,
/// preemption and chaos experiments). Uses a fake HttpMessageHandler so no real server is needed,
/// asserting the HTTP method, path and serialized body of each request.
/// </summary>
public class SreControlPlaneTests
{
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

    // -------------------------------------------------------------------
    // Load-scenario registry
    // -------------------------------------------------------------------

    [Fact]
    public void LoadScenario_RegistersWithPutAndCamelCaseBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"checkout-load\",\"state\":\"LOADED\"}";

        var scenario = new LoadScenario
        {
            Name = "checkout-load",
            TemplateType = LoadTemplateType.VELOCITY,
            MaxRequests = 5000,
            StartDelayMillis = 2000,
            Labels = new Dictionary<string, string> { ["team"] = "checkout" },
            Profile = new LoadProfile
            {
                Stages = new List<LoadStage>
                {
                    LoadStage.RampVus(0, 10, 30000, RampCurve.LINEAR),
                    LoadStage.ConstantVus(10, 60000),
                    LoadStage.Pause(5000)
                }
            },
            Steps = new List<LoadStep>
            {
                new()
                {
                    Name = "get-item",
                    Request = HttpRequest.Request().WithMethod("GET").WithPath("/api/item/1").Build(),
                    ThinkTime = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 20 }
                }
            }
        };

        var registered = client.LoadScenario(scenario);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
        handler.LastRequestBody.Should().Contain("\"name\":\"checkout-load\"");
        handler.LastRequestBody.Should().Contain("\"templateType\":\"VELOCITY\"");
        handler.LastRequestBody.Should().Contain("\"maxRequests\":5000");
        handler.LastRequestBody.Should().Contain("\"startDelayMillis\":2000");
        handler.LastRequestBody.Should().Contain("\"profile\"");
        handler.LastRequestBody.Should().Contain("\"stages\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"VU\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"PAUSE\"");
        handler.LastRequestBody.Should().Contain("\"curve\":\"LINEAR\"");
        // A meaningful zero (startVus=0) must still be serialised.
        handler.LastRequestBody.Should().Contain("\"startVus\":0");
        handler.LastRequestBody.Should().Contain("\"endVus\":10");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":30000");
        handler.LastRequestBody.Should().Contain("\"vus\":10");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":5000");
        handler.LastRequestBody.Should().Contain("\"steps\"");
        handler.LastRequestBody.Should().Contain("\"thinkTime\"");

        // Registration parses {name,state} and does NOT start the scenario.
        registered.Name.Should().Be("checkout-load");
        registered.State.Should().Be(LoadScenarioState.LOADED);
    }

    [Fact]
    public void LoadScenario_DoesNotThrow403_RegistrationAlwaysAllowed()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"x\",\"state\":\"LOADED\"}";

        var scenario = new LoadScenario { Name = "x", Profile = new LoadProfile(), Steps = new List<LoadStep>() };

        var registered = client.LoadScenario(scenario);

        registered.State.Should().Be(LoadScenarioState.LOADED);
    }

    [Fact]
    public void LoadScenario_ThrowsOnInvalid400()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"missing profile\"}";

        var act = () => client.LoadScenario(new LoadScenario { Name = "x" });
        act.Should().Throw<MockServerClientException>().WithMessage("*Invalid load scenario*");
    }

    [Fact]
    public void LoadScenarios_ListsAllWithGet()
    {
        var (client, handler) = CreateClient();
        // The server emits the live status fields FLAT on each entry (siblings of
        // name/state/definition), present only once the scenario has run.
        handler.ResponseBody =
            "{\"scenarios\":[" +
            "{\"name\":\"a\",\"state\":\"RUNNING\",\"definition\":{\"name\":\"a\"}," +
            "\"currentVus\":3,\"stageIndex\":1,\"stageType\":\"RATE\",\"currentTarget\":50,\"requestsSent\":42,\"p95Millis\":12.5}," +
            "{\"name\":\"b\",\"state\":\"LOADED\",\"definition\":{\"name\":\"b\"}}" +
            "]}";

        var list = client.LoadScenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
        list.Scenarios.Should().HaveCount(2);
        list.Scenarios[0].Name.Should().Be("a");
        list.Scenarios[0].State.Should().Be(LoadScenarioState.RUNNING);
        list.Scenarios[0].Definition!.Name.Should().Be("a");
        // Flat live fields are populated directly on the entry (inherited LoadScenarioStatus).
        list.Scenarios[0].RequestsSent.Should().Be(42);
        list.Scenarios[0].P95Millis.Should().Be(12.5);
        list.Scenarios[0].CurrentVus.Should().Be(3);
        list.Scenarios[0].StageIndex.Should().Be(1);
        list.Scenarios[0].StageType.Should().Be("RATE");
        list.Scenarios[0].CurrentTarget.Should().Be(50);
        // A never-run LOADED scenario carries no live fields.
        list.Scenarios[1].State.Should().Be(LoadScenarioState.LOADED);
        list.Scenarios[1].RequestsSent.Should().BeNull();
        list.Scenarios[1].CurrentVus.Should().BeNull();
    }

    [Fact]
    public void GetLoadScenario_FetchesOneByName()
    {
        var (client, handler) = CreateClient();
        // A COMPLETED scenario: live fields are FLAT siblings of name/state/definition.
        handler.ResponseBody = "{\"name\":\"checkout load\",\"state\":\"COMPLETED\",\"startDelayMillis\":2000,\"definition\":{\"name\":\"checkout load\"},\"requestsSent\":100,\"succeeded\":98,\"failed\":2,\"p95Millis\":9}";

        var entry = client.GetLoadScenario("checkout load");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        // Name is URL-escaped in the path.
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/checkout%20load");
        entry.Name.Should().Be("checkout load");
        entry.State.Should().Be(LoadScenarioState.COMPLETED);
        entry.StartDelayMillis.Should().Be(2000);
        // Flat live fields populate directly on the entry.
        entry.RequestsSent.Should().Be(100);
        entry.Succeeded.Should().Be(98);
        entry.Failed.Should().Be(2);
        entry.P95Millis.Should().Be(9);
    }

    [Fact]
    public void GetLoadScenario_Throws404WhenAbsent()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;
        handler.ResponseBody = "{\"error\":\"not found\"}";

        var act = () => client.GetLoadScenario("missing");
        act.Should().Throw<MockServerClientException>().WithMessage("*missing*404*");
    }

    [Fact]
    public void DeleteLoadScenario_RemovesOneByName()
    {
        var (client, handler) = CreateClient();

        client.DeleteLoadScenario("a");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/a");
    }

    [Fact]
    public void ClearLoadScenarios_DeletesAll()
    {
        var (client, handler) = CreateClient();

        client.ClearLoadScenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
    }

    [Fact]
    public void StartLoadScenarios_SendsNamesArray()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody =
            "{\"started\":[{\"name\":\"a\",\"state\":\"PENDING\"},{\"name\":\"b\",\"state\":\"RUNNING\"}],\"status\":\"ok\"}";

        var result = client.StartLoadScenarios("a", "b");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/start");
        handler.LastRequestBody.Should().Contain("\"names\":[\"a\",\"b\"]");
        result.Started.Should().HaveCount(2);
        result.Started[0].Name.Should().Be("a");
        result.Started[0].State.Should().Be(LoadScenarioState.PENDING);
        result.Started[1].State.Should().Be(LoadScenarioState.RUNNING);
        result.Status.Should().Be("ok");
    }

    [Fact]
    public void StartLoadScenarios_Throws403WhenDisabled()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Forbidden;
        handler.ResponseBody = "{\"error\":\"load generation not enabled\"}";

        var act = () => client.StartLoadScenarios("a");
        act.Should().Throw<MockServerClientException>().WithMessage("*loadGenerationEnabled*");
    }

    [Fact]
    public void StartLoadScenarios_Throws404OnUnknownName()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;
        handler.ResponseBody = "{\"error\":\"unknown scenario\"}";

        var act = () => client.StartLoadScenarios("nope");
        act.Should().Throw<MockServerClientException>().WithMessage("*404*");
    }

    [Fact]
    public void StartLoadScenarios_ThrowsWhenNoNames()
    {
        var (client, _) = CreateClient();

        var act = () => client.StartLoadScenarios();
        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void StopLoadScenarios_SendsNamesArray()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"stopped\":[{\"name\":\"a\",\"state\":\"STOPPED\"}],\"status\":\"ok\"}";

        var result = client.StopLoadScenarios("a");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/stop");
        handler.LastRequestBody.Should().Contain("\"names\":[\"a\"]");
        result.Stopped.Should().HaveCount(1);
        result.Stopped[0].Name.Should().Be("a");
        result.Stopped[0].State.Should().Be(LoadScenarioState.STOPPED);
        result.Status.Should().Be("ok");
    }

    [Fact]
    public void StopLoadScenarios_SendsEmptyBodyToStopAll()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"stopped\":[],\"status\":\"ok\"}";

        client.StopLoadScenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/stop");
        handler.LastRequestBody.Should().BeEmpty();
    }

    [Fact]
    public void RunLoadScenario_RegistersThenStarts()
    {
        var (client, handler) = CreateClient();
        // The convenience helper issues two PUTs; the fake handler records only the last
        // (the /start call), which is what we assert on. Both share the same response body.
        handler.ResponseBody = "{\"name\":\"checkout-load\",\"state\":\"LOADED\",\"started\":[{\"name\":\"checkout-load\",\"state\":\"PENDING\"}],\"status\":\"ok\"}";

        var scenario = new LoadScenario { Name = "checkout-load", Profile = new LoadProfile(), Steps = new List<LoadStep>() };

        var result = client.RunLoadScenario(scenario);

        // Final request is the start call.
        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/start");
        handler.LastRequestBody.Should().Contain("\"names\":[\"checkout-load\"]");
        result.Started.Should().ContainSingle(r => r.Name == "checkout-load");
    }

    [Fact]
    public void LoadScenario_SerialisesParitySchemaFields()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"parity\",\"state\":\"LOADED\"}";

        var scenario = new LoadScenario
        {
            Name = "parity",
            StepSelection = LoadStepSelection.WEIGHTED,
            AbortOnFail = true,
            AbortGraceMillis = 3000,
            Thresholds = new List<LoadThreshold>
            {
                new() { Metric = LoadThresholdMetric.LATENCY_P999, Comparator = LoadThresholdComparator.LESS_THAN, Threshold = 250 },
                new() { Metric = LoadThresholdMetric.ERROR_RATE, Comparator = LoadThresholdComparator.LESS_THAN_OR_EQUAL, Threshold = 0.01 }
            },
            Pacing = new LoadPacing { Mode = LoadPacingMode.CONSTANT_THROUGHPUT, Value = 5 },
            Feeder = new LoadFeeder
            {
                Rows = new List<Dictionary<string, string>>
                {
                    new() { ["user"] = "alice" },
                    new() { ["user"] = "bob" }
                },
                Strategy = LoadFeederStrategy.RANDOM
            },
            Profile = new LoadProfile
            {
                Shape = new LoadShape
                {
                    Type = LoadShapeType.SPIKE,
                    Metric = LoadShapeMetric.RATE,
                    Baseline = 0,
                    Peak = 100,
                    RampUpMillis = 5000,
                    HoldMillis = 10000,
                    RampDownMillis = 5000
                }
            },
            Steps = new List<LoadStep>
            {
                new()
                {
                    Name = "login",
                    Weight = 7,
                    Request = HttpRequest.Request().WithMethod("POST").WithPath("/login").Build(),
                    Captures = new List<LoadCapture>
                    {
                        new() { Name = "token", Source = LoadCaptureSource.BODY_JSONPATH, Expression = "$.token", DefaultValue = "none" }
                    }
                }
            }
        };

        client.LoadScenario(scenario);

        var body = handler.LastRequestBody!;
        body.Should().Contain("\"stepSelection\":\"WEIGHTED\"");
        body.Should().Contain("\"abortOnFail\":true");
        body.Should().Contain("\"abortGraceMillis\":3000");
        body.Should().Contain("\"thresholds\"");
        body.Should().Contain("\"metric\":\"LATENCY_P999\"");
        body.Should().Contain("\"comparator\":\"LESS_THAN\"");
        body.Should().Contain("\"threshold\":250");
        body.Should().Contain("\"metric\":\"ERROR_RATE\"");
        body.Should().Contain("\"pacing\"");
        body.Should().Contain("\"mode\":\"CONSTANT_THROUGHPUT\"");
        body.Should().Contain("\"value\":5");
        body.Should().Contain("\"feeder\"");
        body.Should().Contain("\"rows\"");
        body.Should().Contain("\"user\":\"alice\"");
        body.Should().Contain("\"strategy\":\"RANDOM\"");
        body.Should().Contain("\"shape\"");
        body.Should().Contain("\"type\":\"SPIKE\"");
        body.Should().Contain("\"peak\":100");
        // A meaningful zero (baseline=0) must still be serialised.
        body.Should().Contain("\"baseline\":0");
        body.Should().Contain("\"weight\":7");
        body.Should().Contain("\"captures\"");
        body.Should().Contain("\"source\":\"BODY_JSONPATH\"");
        body.Should().Contain("\"expression\":\"$.token\"");
        body.Should().Contain("\"defaultValue\":\"none\"");
    }

    [Fact]
    public void LoadScenario_OmitsParityFieldsWhenUnset()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"basic\",\"state\":\"LOADED\"}";

        var scenario = new LoadScenario
        {
            Name = "basic",
            Profile = new LoadProfile { Stages = new List<LoadStage> { LoadStage.ConstantVus(1, 1000) } },
            Steps = new List<LoadStep> { new() { Request = HttpRequest.Request().WithMethod("GET").WithPath("/x").Build() } }
        };

        client.LoadScenario(scenario);

        var body = handler.LastRequestBody!;
        // Existing scenarios serialise unchanged — none of the new optional fields appear.
        body.Should().NotContain("thresholds");
        body.Should().NotContain("abortOnFail");
        body.Should().NotContain("abortGraceMillis");
        body.Should().NotContain("pacing");
        body.Should().NotContain("feeder");
        body.Should().NotContain("stepSelection");
        body.Should().NotContain("shape");
        body.Should().NotContain("captures");
        body.Should().NotContain("weight");
    }

    [Fact]
    public void GetLoadScenario_ParsesNewStatusFields()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody =
            "{\"name\":\"checkout\",\"state\":\"COMPLETED\",\"p999Millis\":312.5," +
            "\"droppedIterations\":7,\"verdict\":\"FAIL\",\"abortedByThreshold\":true," +
            "\"thresholdResults\":[{\"metric\":\"LATENCY_P999\",\"comparator\":\"LESS_THAN\"," +
            "\"threshold\":250,\"observed\":312.5,\"satisfied\":false}]}";

        var entry = client.GetLoadScenario("checkout");

        entry.State.Should().Be(LoadScenarioState.COMPLETED);
        entry.P999Millis.Should().Be(312.5);
        entry.DroppedIterations.Should().Be(7);
        entry.Verdict.Should().Be(LoadVerdict.FAIL);
        entry.AbortedByThreshold.Should().BeTrue();
        entry.ThresholdResults.Should().ContainSingle();
        entry.ThresholdResults![0].Metric.Should().Be("LATENCY_P999");
        entry.ThresholdResults[0].Observed.Should().Be(312.5);
        entry.ThresholdResults[0].Satisfied.Should().BeFalse();
    }

    [Fact]
    public void GetLoadScenarioReport_FetchesJsonReportWithGet()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"scenario\":\"checkout\",\"verdict\":\"PASS\"}";

        var report = client.GetLoadScenarioReport("checkout load");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/checkout%20load/report");
        report.Should().Contain("\"verdict\":\"PASS\"");
    }

    [Fact]
    public void GetLoadScenarioReport_AppendsJunitFormatQuery()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "<testsuite/>";

        var report = client.GetLoadScenarioReport("checkout", "junit");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/checkout/report?format=junit");
        report.Should().Be("<testsuite/>");
    }

    [Fact]
    public void GetLoadScenarioReport_Throws404WhenNeverRan()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;

        var act = () => client.GetLoadScenarioReport("missing");

        act.Should().Throw<MockServerClientException>().WithMessage("*404*");
    }

    [Fact]
    public void GenerateLoadScenarioFromOpenAPI_PutsNameSpecTargetProfile()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody =
            "{\"status\":\"loaded\",\"name\":\"petstore-load\",\"state\":\"LOADED\"," +
            "\"scenario\":{\"name\":\"petstore-load\"}}";

        var result = client.GenerateLoadScenarioFromOpenAPI(
            "petstore-load",
            "https://example.com/petstore.yaml",
            new LoadGenerateTarget { Host = "petstore.svc", Port = 8080, Scheme = LoadTargetScheme.http },
            new LoadProfile { Stages = new List<LoadStage> { LoadStage.ConstantVus(2, 5000) } });

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/generateFromOpenAPI");
        var body = handler.LastRequestBody!;
        body.Should().Contain("\"name\":\"petstore-load\"");
        body.Should().Contain("\"specUrlOrPayload\":\"https://example.com/petstore.yaml\"");
        body.Should().Contain("\"target\"");
        body.Should().Contain("\"host\":\"petstore.svc\"");
        body.Should().Contain("\"port\":8080");
        body.Should().Contain("\"scheme\":\"http\"");
        body.Should().Contain("\"profile\"");

        result.Status.Should().Be("loaded");
        result.Name.Should().Be("petstore-load");
        result.State.Should().Be(LoadScenarioState.LOADED);
        result.Scenario!.Name.Should().Be("petstore-load");
    }

    [Fact]
    public void GenerateLoadScenarioFromOpenAPI_OmitsOptionalsWhenNull()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"status\":\"loaded\",\"name\":\"x\",\"state\":\"LOADED\"}";

        client.GenerateLoadScenarioFromOpenAPI("x", "inline-spec");

        var body = handler.LastRequestBody!;
        body.Should().Contain("\"specUrlOrPayload\":\"inline-spec\"");
        body.Should().NotContain("target");
        body.Should().NotContain("profile");
    }

    [Fact]
    public void GenerateLoadScenarioFromOpenAPI_Throws400OnInvalid()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;

        var act = () => client.GenerateLoadScenarioFromOpenAPI("x", "bad-spec");

        act.Should().Throw<MockServerClientException>();
    }

    [Fact]
    public void GenerateLoadScenarioFromRecording_PutsModeFilterMaxStepsTarget()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody =
            "{\"status\":\"loaded\",\"name\":\"replay\",\"state\":\"LOADED\"," +
            "\"scenario\":{\"name\":\"replay\"}}";

        var result = client.GenerateLoadScenarioFromRecording(
            "replay",
            LoadRecordingMode.TEMPLATIZED,
            HttpRequest.Request().WithMethod("GET").WithPath("/api/.*").Build(),
            new LoadGenerateTarget { Host = "staging.svc", Port = 8080, Scheme = LoadTargetScheme.http },
            maxSteps: 25);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/generateFromRecording");
        var body = handler.LastRequestBody!;
        body.Should().Contain("\"name\":\"replay\"");
        body.Should().Contain("\"mode\":\"TEMPLATIZED\"");
        body.Should().Contain("\"requestFilter\"");
        body.Should().Contain("\"maxSteps\":25");
        body.Should().Contain("\"target\"");
        body.Should().Contain("\"host\":\"staging.svc\"");

        result.Status.Should().Be("loaded");
        result.Name.Should().Be("replay");
        result.State.Should().Be(LoadScenarioState.LOADED);
    }

    [Fact]
    public void GenerateLoadScenarioFromRecording_OmitsOptionalsWhenNull()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"status\":\"loaded\",\"name\":\"r\",\"state\":\"LOADED\"}";

        client.GenerateLoadScenarioFromRecording("r");

        var body = handler.LastRequestBody!;
        body.Should().Contain("\"name\":\"r\"");
        body.Should().NotContain("mode");
        body.Should().NotContain("requestFilter");
        body.Should().NotContain("maxSteps");
        body.Should().NotContain("target");
        body.Should().NotContain("profile");
    }

    [Fact]
    public void GenerateLoadScenarioFromRecording_Throws400OnInvalid()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;

        var act = () => client.GenerateLoadScenarioFromRecording("r");

        act.Should().Throw<MockServerClientException>();
    }

    // -------------------------------------------------------------------
    // Service chaos
    // -------------------------------------------------------------------

    [Fact]
    public void SetServiceChaos_SendsPutWithHostChaosAndTtl()
    {
        var (client, handler) = CreateClient();

        var profile = new ServiceChaosProfile
        {
            ErrorStatus = 503,
            ErrorProbability = 0.3,
            Latency = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 200 }
        };

        client.SetServiceChaos("payments.internal:8443", profile, 60000);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"host\":\"payments.internal:8443\"");
        handler.LastRequestBody.Should().Contain("\"chaos\"");
        handler.LastRequestBody.Should().Contain("\"errorStatus\":503");
        handler.LastRequestBody.Should().Contain("\"errorProbability\":0.3");
        handler.LastRequestBody.Should().Contain("\"latency\"");
        handler.LastRequestBody.Should().Contain("\"ttlMillis\":60000");
    }

    [Fact]
    public void SetServiceChaos_OmitsTtlWhenNull()
    {
        var (client, handler) = CreateClient();

        client.SetServiceChaos("api.svc", new ServiceChaosProfile { ErrorProbability = 0.5 });

        handler.LastRequestBody.Should().NotContain("ttlMillis");
    }

    [Fact]
    public void RemoveServiceChaos_SendsRemoveFlag()
    {
        var (client, handler) = CreateClient();

        client.RemoveServiceChaos("api.svc");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"host\":\"api.svc\"");
        handler.LastRequestBody.Should().Contain("\"remove\":true");
    }

    [Fact]
    public void ClearServiceChaos_SendsClearFlag()
    {
        var (client, handler) = CreateClient();

        client.ClearServiceChaos();

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"clear\":true");
    }

    // -------------------------------------------------------------------
    // SLO verdicts
    // -------------------------------------------------------------------

    [Fact]
    public void VerifySlo_SendsPutAndParsesPassVerdict()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.OK;
        handler.ResponseBody = "{\"name\":\"checkout-slo\",\"result\":\"PASS\",\"sampleCount\":50}";

        var criteria = new SloCriteria
        {
            Name = "checkout-slo",
            Window = SloWindow.Lookback(60000),
            MinimumSampleCount = 20,
            UpstreamHosts = new List<string> { "payments.svc" },
            Objectives = new List<SloObjective>
            {
                new() { Sli = Sli.LATENCY_P95, Comparator = SloComparator.LESS_THAN, Threshold = 250.0, Scope = SloScope.FORWARD }
            }
        };

        var verdict = client.VerifySlo(criteria);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/verifySLO");
        handler.LastRequestBody.Should().Contain("\"name\":\"checkout-slo\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"LOOKBACK\"");
        handler.LastRequestBody.Should().Contain("\"lookbackMillis\":60000");
        handler.LastRequestBody.Should().Contain("\"sli\":\"LATENCY_P95\"");
        handler.LastRequestBody.Should().Contain("\"comparator\":\"LESS_THAN\"");
        handler.LastRequestBody.Should().Contain("\"threshold\":250");
        handler.LastRequestBody.Should().Contain("\"upstreamHosts\"");

        verdict.Result.Should().Be(SloResult.PASS);
        verdict.Name.Should().Be("checkout-slo");
        verdict.SampleCount.Should().Be(50);
    }

    [Fact]
    public void VerifySlo_ReturnsVerdictOnFail406()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotAcceptable; // 406 = FAIL
        handler.ResponseBody = "{\"name\":\"checkout-slo\",\"result\":\"FAIL\"}";

        var verdict = client.VerifySlo(new SloCriteria
        {
            Objectives = new List<SloObjective> { new() { Sli = Sli.ERROR_RATE, Comparator = SloComparator.LESS_THAN, Threshold = 0.01 } }
        });

        verdict.Result.Should().Be(SloResult.FAIL);
    }

    [Fact]
    public void VerifySlo_ThrowsOnDisabled403()
    {
        // 403 is "SLO tracking is off"; it used to share 400 with malformed criteria, so a typo in
        // the criteria was reported to callers as a disabled feature and vice versa.
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Forbidden;
        handler.ResponseBody = "{\"error\":\"SLO tracking not enabled\"}";

        var act = () => client.VerifySlo(new SloCriteria { Objectives = new List<SloObjective>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*sloTrackingEnabled*");
    }

    [Fact]
    public void VerifySlo_ThrowsOnMalformedCriteria400()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"objectives must be an array\"}";

        var act = () => client.VerifySlo(new SloCriteria { Objectives = new List<SloObjective>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*malformed criteria*");
    }

    // -------------------------------------------------------------------
    // Preemption
    // -------------------------------------------------------------------

    [Fact]
    public void SetPreemption_SendsPutWithBodyAndParsesStatus()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"draining\",\"inFlight\":3,\"drainRemainingMillis\":9000,\"mode\":\"both\"}";

        var status = client.SetPreemption(new PreemptionRequest
        {
            Mode = PreemptionMode.both,
            DrainMillis = 10000,
            TtlMillis = 60000,
            LastStreamId = 3
        });

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        handler.LastRequestBody.Should().Contain("\"mode\":\"both\"");
        handler.LastRequestBody.Should().Contain("\"drainMillis\":10000");
        handler.LastRequestBody.Should().Contain("\"ttlMillis\":60000");
        handler.LastRequestBody.Should().Contain("\"lastStreamId\":3");

        status.State.Should().Be("draining");
        status.InFlight.Should().Be(3);
        status.DrainRemainingMillis.Should().Be(9000);
        status.Mode.Should().Be("both");
    }

    [Fact]
    public void SetPreemption_SendsEmptyBodyWhenNullRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"draining\"}";

        client.SetPreemption();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        handler.LastRequestBody.Should().BeEmpty();
    }

    [Fact]
    public void PreemptionStatus_SendsGetAndParsesStatus()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"inactive\",\"inFlight\":0}";

        var status = client.PreemptionStatus();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        status.State.Should().Be("inactive");
        status.InFlight.Should().Be(0);
    }

    [Fact]
    public void ClearPreemption_SendsDelete()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"inactive\"}";

        client.ClearPreemption();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
    }

    // -------------------------------------------------------------------
    // Chaos experiment
    // -------------------------------------------------------------------

    [Fact]
    public void StartChaosExperiment_SendsPutWithStages()
    {
        var (client, handler) = CreateClient();

        var experiment = new ChaosExperiment
        {
            Name = "gradual-degradation",
            Loop = false,
            Stages = new List<ChaosExperimentStage>
            {
                new()
                {
                    DurationMillis = 10000,
                    Profiles = new Dictionary<string, ServiceChaosProfile>
                    {
                        ["api.example.com"] = new() { ErrorStatus = 500, ErrorProbability = 0.1 }
                    }
                }
            }
        };

        client.StartChaosExperiment(experiment);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/chaosExperiment");
        handler.LastRequestBody.Should().Contain("\"name\":\"gradual-degradation\"");
        handler.LastRequestBody.Should().Contain("\"stages\"");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":10000");
        handler.LastRequestBody.Should().Contain("\"profiles\"");
        handler.LastRequestBody.Should().Contain("\"api.example.com\"");
        handler.LastRequestBody.Should().Contain("\"errorStatus\":500");
        handler.LastRequestBody.Should().Contain("\"errorProbability\":0.1");
    }

    [Fact]
    public void StartChaosExperiment_ThrowsOnBadRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"too many stages\"}";

        var act = () => client.StartChaosExperiment(new ChaosExperiment { Stages = new List<ChaosExperimentStage>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*Invalid chaos experiment*");
    }
}
