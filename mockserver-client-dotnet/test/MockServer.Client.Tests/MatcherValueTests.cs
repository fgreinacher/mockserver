using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the negation escape hatch: a matcher value whose own first character is '!' or '?'
/// is the negation / optional marker on the wire, so a LITERAL such value must be sent in the object
/// form (<c>{"not":false,"value":"!foo"}</c>) rather than a bare string, and both wire forms must
/// decode back. Mirrors the Go client's matcher_value_test.go.
/// </summary>
public class MatcherValueTests
{
    // Options that register HttpRequestConverter, matching the client. MatcherValue itself uses an
    // attribute converter, so its serialization is options-independent, but the HttpRequest-level tests
    // below need the converter to merge the matcher maps onto the wire.
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new HttpRequestConverter() }
    };

    // A value that round-trips through the plain form must STAY a plain string, so existing
    // expectations are byte-identical on the wire.
    [Theory]
    [InlineData("foo", false, false, "\"foo\"")]                 // Literal("foo")
    [InlineData("foo", true, false, "\"!foo\"")]                 // NotLiteral("foo")
    [InlineData("foo", false, true, "\"?foo\"")]                 // OptionalLiteral("foo")
    [InlineData("", false, false, "\"\"")]                       // Literal("")
    [InlineData("foo!bar", false, false, "\"foo!bar\"")]         // marker not at position 0
    [InlineData("!foo", true, false, "\"!!foo\"")]               // NotLiteral("!foo") re-parses to itself
    public void UnambiguousStaysPlainString(string value, bool not, bool optional, string want)
    {
        var matcher = new MatcherValue(value, not, optional);
        JsonSerializer.Serialize(matcher).Should().Be(want);
    }

    // A value the plain form would misread must use the object form, and must carry "not" explicitly
    // even when false so the intent is unmistakable.
    [Theory]
    [InlineData("!foo", false, false, "{\"not\":false,\"value\":\"!foo\"}")]
    [InlineData("?foo", false, false, "{\"not\":false,\"value\":\"?foo\"}")]
    [InlineData("?!foo", false, false, "{\"not\":false,\"value\":\"?!foo\"}")]
    [InlineData("!?foo", false, false, "{\"not\":false,\"value\":\"!?foo\"}")]
    [InlineData("!foo", false, true, "{\"not\":false,\"optional\":true,\"value\":\"!foo\"}")]
    public void AmbiguousUsesObjectFormWithExplicitNot(string value, bool not, bool optional, string want)
    {
        var matcher = new MatcherValue(value, not, optional);
        JsonSerializer.Serialize(matcher).Should().Be(want);
    }

    // NotLiteral("!foo") serialises to "!!foo", which re-parses to exactly itself, so it is NOT
    // ambiguous and stays the shorter plain string. A naive startsWith('!') implementation would escape
    // this unnecessarily and diverge from the Java/Go clients.
    [Fact]
    public void NotLiteralOfMarkerValueStaysShortPlainString()
    {
        MatcherValue.NotLiteral("!foo").IsAmbiguous().Should().BeFalse();
        JsonSerializer.Serialize(MatcherValue.NotLiteral("!foo")).Should().Be("\"!!foo\"");
    }

    // A blank value must NOT use the object form: the server's object-form reader ignores a blank
    // "value", so escaping one would silently drop the matcher.
    [Theory]
    [InlineData("", false)]
    [InlineData("   ", false)]
    [InlineData("", true)]
    public void BlankNeverUsesObjectForm(string value, bool not)
    {
        var json = JsonSerializer.Serialize(new MatcherValue(value, not));
        json[0].Should().Be('"', "a blank value has no marker to misread and the object form drops it");
    }

    [Theory]
    [InlineData("\"foo\"")]
    [InlineData("\"!foo\"")]
    [InlineData("{\"not\":false,\"value\":\"!foo\"}")]
    [InlineData("{\"not\":true,\"value\":\"!foo\"}")]
    public void RoundTripsBothWireForms(string wire)
    {
        var first = JsonSerializer.Deserialize<MatcherValue>(wire);
        var remarshalled = JsonSerializer.Serialize(first);
        var second = JsonSerializer.Deserialize<MatcherValue>(remarshalled);
        second.Should().Be(first);
    }

    [Fact]
    public void ObjectFormDecodesToLiteralValue()
    {
        var matcher = JsonSerializer.Deserialize<MatcherValue>("{\"not\":false,\"value\":\"!foo\"}");
        matcher.Should().Be(MatcherValue.Literal("!foo"));
        matcher.Not.Should().BeFalse();
        matcher.Value.Should().Be("!foo");
    }

    [Fact]
    public void PlainNegatedStringDecodesToNotLiteral()
    {
        var matcher = JsonSerializer.Deserialize<MatcherValue>("\"!foo\"");
        matcher.Should().Be(MatcherValue.NotLiteral("foo"));
        matcher.Not.Should().BeTrue();
        matcher.Value.Should().Be("foo");
    }

    // The matcher maps must REPLACE their plain counterparts on the wire, using the escaped object form.
    [Fact]
    public void MatcherMapsReplacePlainMapsOnTheWire()
    {
        var request = HttpRequest.Request()
            .WithPath("/x")
            .WithHeaderMatcher("X-Tag", MatcherValue.Literal("!foo"))
            .WithQueryStringParameterMatcher("q", MatcherValue.Literal("!foo"))
            .WithCookieMatcher("ck", MatcherValue.Literal("!foo"))
            .WithPathParameterMatcher("id", MatcherValue.Literal("!foo"))
            .Build();

        var doc = JsonDocument.Parse(JsonSerializer.Serialize(request, JsonOptions)).RootElement;

        // header/query/path parameters carry a list; the escaped value is the object form, not "!foo".
        doc.GetProperty("headers").GetProperty("X-Tag")[0].ValueKind.Should().Be(JsonValueKind.Object);
        doc.GetProperty("headers").GetProperty("X-Tag")[0].GetProperty("value").GetString().Should().Be("!foo");
        doc.GetProperty("queryStringParameters").GetProperty("q")[0].GetProperty("value").GetString().Should().Be("!foo");
        doc.GetProperty("pathParameters").GetProperty("id")[0].GetProperty("value").GetString().Should().Be("!foo");
        // cookies carry a single value.
        doc.GetProperty("cookies").GetProperty("ck").GetProperty("value").GetString().Should().Be("!foo");
    }

    // Plain header entries set through WithHeader before the matcher API is used must be carried over,
    // not dropped, when the matcher map replaces the plain field on the wire. This exercises the
    // migration block the four With*Matcher methods share.
    [Fact]
    public void HeaderMatcherCarriesOverExistingPlainEntries()
    {
        var request = HttpRequest.Request()
            .WithPath("/x")
            .WithHeader("X-Existing", "plain")
            .WithHeaderMatcher("X-New", MatcherValue.Literal("!lit"))
            .Build();

        var headers = JsonDocument.Parse(JsonSerializer.Serialize(request, JsonOptions)).RootElement
            .GetProperty("headers");
        headers.GetProperty("X-Existing")[0].GetString().Should().Be("plain", "the pre-existing plain header must survive the migration");
        headers.GetProperty("X-New")[0].GetProperty("value").GetString().Should().Be("!lit");
    }

    // When a matcher map is set directly alongside a plain map (e.g. on an expectation read back from a
    // retrieve), the matcher map REPLACES the plain field on the wire — the documented behaviour of all
    // four matcher maps.
    [Fact]
    public void PathParameterMatchersReplacePlainPathParametersOnTheWire()
    {
        var request = HttpRequest.Request().WithPath("/x/{id}").Build();
        request.PathParameters = new Dictionary<string, List<JsonElement>>
        {
            ["id"] = new List<JsonElement> { JsonDocument.Parse("\"plain\"").RootElement }
        };
        request.PathParameterMatchers = new Dictionary<string, List<MatcherValue>>
        {
            ["id"] = new List<MatcherValue> { MatcherValue.Literal("!lit") }
        };

        var pp = JsonDocument.Parse(JsonSerializer.Serialize(request, JsonOptions)).RootElement
            .GetProperty("pathParameters");
        pp.GetProperty("id")[0].GetProperty("value").GetString().Should().Be("!lit");
    }

    // A genuinely-negated matcher still serialises to the marker string, so it is byte-identical to the
    // plain API and keeps its current meaning.
    [Fact]
    public void NegatedMatcherStillEmitsMarkerString()
    {
        var request = HttpRequest.Request()
            .WithPath("/x")
            .WithHeaderMatcher("X-Tag", MatcherValue.NotLiteral("foo"))
            .Build();

        var doc = JsonDocument.Parse(JsonSerializer.Serialize(request, JsonOptions)).RootElement;
        var value = doc.GetProperty("headers").GetProperty("X-Tag")[0];
        value.ValueKind.Should().Be(JsonValueKind.String);
        value.GetString().Should().Be("!foo");
    }

    // Plain values set via the matcher API serialise exactly as the plain map would.
    [Fact]
    public void PlainMatcherValuesSerialiseAsPlainStrings()
    {
        var request = HttpRequest.Request()
            .WithPath("/x")
            .WithHeaderMatcher("X-Tag", MatcherValue.Literal("foo"))
            .Build();

        var doc = JsonDocument.Parse(JsonSerializer.Serialize(request, JsonOptions)).RootElement;
        doc.GetProperty("headers").GetProperty("X-Tag")[0].GetString().Should().Be("foo");
    }

    // A request using only the plain maps must serialise identically whether or not the converter is
    // registered — the escape is additive and invisible to existing callers.
    [Fact]
    public void PlainOnlyRequestIsUnchangedByTheConverter()
    {
        var request = HttpRequest.Request().WithPath("/x").WithHeader("X-Tag", "foo").Build();

        var withConverter = JsonSerializer.Serialize(request, JsonOptions);
        var withoutConverter = JsonSerializer.Serialize(request, new JsonSerializerOptions
        {
            DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase
        });

        withConverter.Should().Be(withoutConverter);
    }

    // The escape must survive a RETRIEVE, not just a create: an expectation carrying the object form
    // must deserialise and re-serialise back to the object form.
    [Fact]
    public void LiteralMatcherSurvivesRoundTripThroughExpectation()
    {
        const string wire =
            "{\"httpRequest\":{\"path\":\"/x\",\"headers\":{\"X-Tag\":[{\"not\":false,\"value\":\"!foo\"}]}}," +
            "\"httpResponse\":{\"statusCode\":200}}";

        var expectation = JsonSerializer.Deserialize<Expectation>(wire, JsonOptions);
        expectation.Should().NotBeNull();

        // The ambiguous value routes to the matcher map, leaving the plain header map null.
        expectation!.HttpRequest!.HeaderMatchers.Should().ContainKey("X-Tag");
        expectation.HttpRequest.HeaderMatchers!["X-Tag"][0].Should().Be(MatcherValue.Literal("!foo"));
        expectation.HttpRequest.Headers.Should().BeNull();

        var reserialised = JsonDocument.Parse(JsonSerializer.Serialize(expectation, JsonOptions)).RootElement;
        reserialised.GetProperty("httpRequest").GetProperty("headers").GetProperty("X-Tag")[0]
            .GetProperty("value").GetString().Should().Be("!foo");
    }

    // A retrieved expectation whose header values all survive the plain form is mirrored back into the
    // plain map, so ordinary traffic decodes exactly as it did before.
    [Fact]
    public void PlainHeaderValuesMirrorBackIntoThePlainMap()
    {
        const string wire =
            "{\"httpRequest\":{\"path\":\"/x\",\"headers\":{\"X-Tag\":[\"foo\",\"!bar\"]}}," +
            "\"httpResponse\":{\"statusCode\":200}}";

        var expectation = JsonSerializer.Deserialize<Expectation>(wire, JsonOptions);

        expectation!.HttpRequest!.HeaderMatchers.Should().BeNull();
        expectation.HttpRequest.Headers.Should().ContainKey("X-Tag");
        expectation.HttpRequest.Headers!["X-Tag"].Should().BeEquivalentTo(new[] { "foo", "!bar" });
    }
}
