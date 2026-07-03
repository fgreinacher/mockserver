using System.Text.Json;
using System.Text.Json.Nodes;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Verifies the C# <see cref="Expectation"/> model round-trips every MockServer expectation feature
/// without silent drops: newly-added request/response fields (cookies, pathParameters, trailers, ...),
/// expectation-level actions and metadata (chaos, rateLimit, forward-with-fallback, before/after
/// actions, capture, steps, namespace, grpcBidiResponse), and a kitchen-sink expectation exercising
/// all of them at once.
/// </summary>
public class ExpectationCompletenessTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    private static void AssertRoundTrips<T>(string json)
    {
        var model = JsonSerializer.Deserialize<T>(json, JsonOptions);
        model.Should().NotBeNull();
        var reserialized = JsonSerializer.Serialize(model, JsonOptions);
        JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reserialized))
            .Should().BeTrue($"{typeof(T).Name} should round-trip losslessly; got: {reserialized}");
    }

    [Fact]
    public void Request_CookiesAndPathParameters_RoundTrip()
    {
        var json = "{\"method\":\"GET\",\"path\":\"/pets/{id}\"," +
                   "\"pathParameters\":{\"id\":[\"123\"]}," +
                   "\"queryStringParameters\":{\"q\":[\"x\"]}," +
                   "\"cookies\":{\"session\":\"abc\",\"theme\":\"dark\"}," +
                   "\"headers\":{\"H\":[\"v\"]}}";
        AssertRoundTrips<HttpRequest>(json);
    }

    [Fact]
    public void Request_ProtocolAndCertAndFlags_RoundTrip()
    {
        var json = "{\"path\":\"/x\",\"not\":true,\"respondBeforeBody\":true,\"protocol\":\"HTTP_2\"," +
                   "\"originalBody\":\"raw\",\"localAddress\":\"127.0.0.1:1\",\"remoteAddress\":\"10.0.0.1:2\"," +
                   "\"clientCertificate\":{\"subject\":\"CN=a\",\"issuer\":\"CN=b\",\"fingerprintSha256\":\"deadbeef\"}," +
                   "\"clientCertificateChain\":[{\"subjectDistinguishedName\":\"CN=a\",\"serialNumber\":\"7\"}]}";
        AssertRoundTrips<HttpRequest>(json);
    }

    [Fact]
    public void Response_CookiesTrailersAndExtras_RoundTrip()
    {
        var json = "{\"statusCode\":201,\"statusCodeRange\":\"2xx\",\"reasonPhrase\":\"Created\"," +
                   "\"headers\":{\"Content-Type\":[\"application/json\"]},\"trailers\":{\"X-Trailer\":[\"t\"]}," +
                   "\"cookies\":{\"sid\":\"9\"},\"generateFromSchema\":\"{\\\"type\\\":\\\"object\\\"}\"," +
                   "\"primary\":true,\"recoverAfter\":{\"failTimes\":3,\"idempotencyHeader\":\"Idempotency-Key\"}}";
        AssertRoundTrips<HttpResponse>(json);
    }

    [Fact]
    public void ChaosProfile_RoundTrips()
    {
        var json = "{\"errorStatus\":503,\"retryAfter\":\"5\",\"errorProbability\":0.25," +
                   "\"dropConnectionProbability\":0.1,\"latency\":{\"timeUnit\":\"MILLISECONDS\",\"value\":100}," +
                   "\"seed\":42,\"failRequestCount\":2,\"outageAfterMillis\":1000,\"malformedBody\":true," +
                   "\"quotaName\":\"q\",\"quotaLimit\":10,\"quotaWindowMillis\":1000,\"graphqlErrors\":true," +
                   "\"graphqlErrorMessage\":\"boom\",\"truncateBodyAtFraction\":0.5}";
        AssertRoundTrips<HttpChaosProfile>(json);
    }

    [Fact]
    public void RateLimit_RoundTrips()
    {
        var json = "{\"name\":\"api\",\"algorithm\":\"token_bucket\",\"burst\":20," +
                   "\"refillPerSecond\":5.5,\"errorStatus\":429,\"retryAfter\":\"1\"}";
        AssertRoundTrips<RateLimit>(json);
    }

    [Fact]
    public void ForwardWithFallback_RoundTrips()
    {
        var json = "{\"httpForward\":{\"host\":\"backend\",\"port\":443,\"scheme\":\"HTTPS\"}," +
                   "\"fallbackResponse\":{\"statusCode\":503,\"body\":\"down\"}," +
                   "\"fallbackOnStatusCodes\":[500,502,503],\"fallbackOnTimeout\":true,\"primary\":true}";
        AssertRoundTrips<HttpForwardWithFallback>(json);
    }

    [Fact]
    public void ForwardValidateAction_RoundTrips()
    {
        var json = "{\"specUrlOrPayload\":\"https://example/openapi.json\",\"host\":\"backend\",\"port\":80," +
                   "\"scheme\":\"HTTP\",\"validateRequest\":true,\"validateResponse\":true,\"validationMode\":\"STRICT\"}";
        AssertRoundTrips<HttpForwardValidateAction>(json);
    }

    [Fact]
    public void GrpcBidiResponse_RoundTrips()
    {
        var json = "{\"statusName\":\"OK\",\"headers\":{\"h\":[\"v\"]}," +
                   "\"messages\":[{\"json\":\"{}\",\"templateType\":\"VELOCITY\",\"delay\":{\"timeUnit\":\"SECONDS\",\"value\":1}}]," +
                   "\"rules\":[{\"matchJson\":\"{\\\"op\\\":\\\"a\\\"}\",\"responses\":[{\"json\":\"{\\\"r\\\":1}\"}]}]," +
                   "\"closeConnection\":true,\"primary\":false}";
        AssertRoundTrips<GrpcBidiResponse>(json);
    }

    [Fact]
    public void BeforeAfterActions_And_Capture_RoundTrip()
    {
        var json = "{\"httpRequest\":{\"path\":\"/ping\"},\"httpResponse\":{\"statusCode\":200}," +
                   "\"beforeActions\":[{\"httpRequest\":{\"method\":\"POST\",\"path\":\"/audit\"},\"blocking\":true,\"failurePolicy\":\"BEST_EFFORT\"}]," +
                   "\"afterActions\":[{\"httpClassCallback\":{\"callbackClass\":\"com.x.Cb\"},\"delay\":{\"timeUnit\":\"MILLISECONDS\",\"value\":10}}]," +
                   "\"capture\":[{\"source\":\"jsonPath\",\"expression\":\"$.id\",\"into\":\"petId\"}]}";
        AssertRoundTrips<Expectation>(json);
    }

    [Fact]
    public void Steps_RoundTrip()
    {
        var json = "{\"steps\":[" +
                   "{\"httpResponse\":{\"statusCode\":200},\"responder\":true,\"delay\":{\"timeUnit\":\"MILLISECONDS\",\"value\":0}}," +
                   "{\"httpForward\":{\"host\":\"b\",\"port\":80},\"blocking\":false,\"failurePolicy\":\"FAIL_FAST\"}]}";
        AssertRoundTrips<Expectation>(json);
    }

    [Fact]
    public void TypedRequestBodyMatcher_OnExpectation_RoundTrips()
    {
        // Body is object? so it deserialises to a faithful JsonElement; a fully-typed matcher shape
        // must survive the expectation round-trip untouched.
        var json = "{\"httpRequest\":{\"path\":\"/x\",\"body\":{\"type\":\"JSON\",\"json\":\"{\\\"a\\\":1}\",\"matchType\":\"STRICT\"}}," +
                   "\"httpResponse\":{\"statusCode\":200,\"body\":{\"type\":\"BINARY\",\"base64Bytes\":\"AAE=\"}}}";
        AssertRoundTrips<Expectation>(json);
    }

    [Fact]
    public void KitchenSink_Expectation_RoundTrips()
    {
        var json = @"{
          ""id"": ""ks-1"",
          ""priority"": 7,
          ""percentage"": 50,
          ""chaos"": { ""errorStatus"": 500, ""errorProbability"": 0.3, ""latency"": { ""timeUnit"": ""MILLISECONDS"", ""value"": 25 } },
          ""rateLimit"": { ""name"": ""r"", ""algorithm"": ""fixed_window"", ""limit"": 100, ""windowMillis"": 1000 },
          ""httpRequest"": {
            ""method"": ""POST"",
            ""path"": ""/pets/{petId}"",
            ""pathParameters"": { ""petId"": [""42""] },
            ""queryStringParameters"": { ""verbose"": [""true""] },
            ""headers"": { ""Accept"": [""application/json""] },
            ""cookies"": { ""session"": ""s1"" },
            ""body"": { ""type"": ""JSON"", ""json"": ""{\""name\"":\""fido\""}"", ""matchType"": ""ONLY_MATCHING_FIELDS"" },
            ""protocol"": ""HTTP_1_1"",
            ""secure"": true,
            ""jwt"": { ""issuer"": ""acme"", ""claims"": { ""role"": ""admin"" } }
          },
          ""httpResponse"": {
            ""statusCode"": 200,
            ""headers"": { ""Content-Type"": [""application/json""] },
            ""cookies"": { ""sid"": ""9"" },
            ""trailers"": { ""X-T"": [""1""] },
            ""body"": ""ok"",
            ""primary"": true,
            ""recoverAfter"": { ""failTimes"": 2 }
          },
          ""beforeActions"": [ { ""httpRequest"": { ""path"": ""/before"" }, ""blocking"": true } ],
          ""afterActions"": [ { ""httpObjectCallback"": { ""clientId"": ""c1"" } } ],
          ""capture"": [ { ""source"": ""header"", ""expression"": ""X-Id"", ""into"": ""id"" } ],
          ""namespace"": ""tenant-a"",
          ""scenarioName"": ""checkout"",
          ""scenarioState"": ""START"",
          ""newScenarioState"": ""DONE"",
          ""crossProtocolScenarios"": [ { ""trigger"": ""HTTP_REQUEST"", ""scenarioName"": ""checkout"", ""targetState"": ""DONE"" } ],
          ""times"": { ""remainingTimes"": 3, ""unlimited"": false },
          ""timeToLive"": { ""unlimited"": true },
          ""timestamp"": ""2026-01-01T00:00:00Z""
        }";

        AssertRoundTrips<Expectation>(json);

        // Spot-check a few typed fields survived deserialisation.
        var exp = JsonSerializer.Deserialize<Expectation>(json, JsonOptions)!;
        exp.Percentage.Should().Be(50);
        exp.Namespace.Should().Be("tenant-a");
        exp.Chaos!.ErrorStatus.Should().Be(500);
        exp.RateLimit!.Limit.Should().Be(100);
        exp.HttpRequest!.PathParameters!["petId"][0].Should().Be("42");
        exp.HttpRequest.Cookies!["session"].Should().Be("s1");
        exp.HttpResponse!.Cookies!["sid"].Should().Be("9");
        exp.BeforeActions.Should().HaveCount(1);
        exp.AfterActions.Should().HaveCount(1);
        exp.Capture![0].Into.Should().Be("id");
    }
}
