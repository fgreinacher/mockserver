using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

public class HttpRequestBuilderTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void Request_WithMethodAndPath_SerializesCorrectly()
    {
        var request = HttpRequest.Request()
            .WithMethod("GET")
            .WithPath("/hello")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("method").GetString().Should().Be("GET");
        doc.RootElement.GetProperty("path").GetString().Should().Be("/hello");
    }

    [Fact]
    public void Request_WithQueryStringParameters_SerializesCorrectly()
    {
        var request = HttpRequest.Request()
            .WithMethod("GET")
            .WithPath("/search")
            .WithQueryStringParameter("q", "mockserver")
            .WithQueryStringParameter("page", "1", "2")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var qsp = doc.RootElement.GetProperty("queryStringParameters");
        qsp.GetProperty("q")[0].GetString().Should().Be("mockserver");
        qsp.GetProperty("page")[0].GetString().Should().Be("1");
        qsp.GetProperty("page")[1].GetString().Should().Be("2");
    }

    [Fact]
    public void Request_WithHeaders_SerializesCorrectly()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithHeader("Content-Type", "application/json")
            .WithHeader("Accept", "text/html", "application/json")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var headers = doc.RootElement.GetProperty("headers");
        headers.GetProperty("Content-Type")[0].GetString().Should().Be("application/json");
        headers.GetProperty("Accept")[0].GetString().Should().Be("text/html");
        headers.GetProperty("Accept")[1].GetString().Should().Be("application/json");
    }

    [Fact]
    public void Request_WithStringBody_SerializesAsString()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithBody("hello world")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("body").GetString().Should().Be("hello world");
    }

    [Fact]
    public void Request_WithJsonBody_SerializesAsTypedObject()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithJsonBody("{\"key\":\"value\"}")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var body = doc.RootElement.GetProperty("body");
        body.GetProperty("type").GetString().Should().Be("JSON");
        body.GetProperty("json").GetString().Should().Be("{\"key\":\"value\"}");
    }

    [Fact]
    public void Request_NullFields_AreOmitted()
    {
        var request = HttpRequest.Request()
            .WithPath("/hello")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("method", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("headers", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("queryStringParameters", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("body", out _).Should().BeFalse();
    }

    [Fact]
    public void Request_ImplicitConversion_Works()
    {
        HttpRequest request = HttpRequest.Request().WithPath("/hello");
        request.Path.Should().Be("/hello");
    }

    [Fact]
    public void Request_WithJwt_SerializesToExactWireShape()
    {
        var request = HttpRequest.Request()
            .WithPath("/secure")
            .WithJwt(new Jwt
            {
                Claims = new Dictionary<string, string>
                {
                    ["sub"] = "user-123",
                    ["role"] = "!admin",
                    ["email"] = "^.+@example.com$"
                },
                Issuer = "https://issuer.example.com",
                Audience = "my-api",
                Algorithm = "RS256"
            })
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var jwt = doc.RootElement.GetProperty("jwt");
        var claims = jwt.GetProperty("claims");
        claims.GetProperty("sub").GetString().Should().Be("user-123");
        claims.GetProperty("role").GetString().Should().Be("!admin");
        claims.GetProperty("email").GetString().Should().Be("^.+@example.com$");
        jwt.GetProperty("issuer").GetString().Should().Be("https://issuer.example.com");
        jwt.GetProperty("audience").GetString().Should().Be("my-api");
        jwt.GetProperty("algorithm").GetString().Should().Be("RS256");
        // unset optional properties are omitted
        jwt.TryGetProperty("header", out _).Should().BeFalse();
        jwt.TryGetProperty("scheme", out _).Should().BeFalse();

        // exact serialised shape of the jwt object. System.Text.Json's default
        // JavaScriptEncoder emits '+' as the + escape; the server decodes it back to '+'.
        jwt.GetRawText().Should().Be(
            "{\"claims\":{\"sub\":\"user-123\",\"role\":\"!admin\",\"email\":\"^.\\u002B@example.com$\"}," +
            "\"issuer\":\"https://issuer.example.com\",\"audience\":\"my-api\",\"algorithm\":\"RS256\"}");
    }

    [Fact]
    public void Request_WithAllOfBody_SerializesToExactWireShape()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithAllOfBody(
                BodyMatcher.OfJsonPath("$.name"),
                BodyMatcher.OfRegex(".*active.*"))
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var body = doc.RootElement.GetProperty("body");
        body.GetProperty("type").GetString().Should().Be("ALL_OF");
        var bodyAllOf = body.GetProperty("bodyAllOf");
        bodyAllOf.GetArrayLength().Should().Be(2);
        bodyAllOf[0].GetProperty("type").GetString().Should().Be("JSON_PATH");
        bodyAllOf[0].GetProperty("jsonPath").GetString().Should().Be("$.name");
        bodyAllOf[1].GetProperty("type").GetString().Should().Be("REGEX");
        bodyAllOf[1].GetProperty("regex").GetString().Should().Be(".*active.*");

        // exact serialised shape of the body object
        body.GetRawText().Should().Be(
            "{\"type\":\"ALL_OF\",\"bodyAllOf\":[" +
            "{\"type\":\"JSON_PATH\",\"jsonPath\":\"$.name\"}," +
            "{\"type\":\"REGEX\",\"regex\":\".*active.*\"}]}");
    }
}
