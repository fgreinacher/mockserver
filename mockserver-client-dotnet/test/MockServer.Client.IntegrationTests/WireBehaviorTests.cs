using System.Net;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.IntegrationTests;

/// <summary>
/// Integration tests that prove the SERVER actually honours the actions and matchers the .NET
/// client sends over the wire — not merely that the client can create the expectation.
///
/// <para>Two coverage gaps motivated these tests:</para>
/// <list type="bullet">
///   <item><description>NottableString negation (a header matcher value of <c>!foo</c>): the client
///   must send the negation marker and the server must reject a request whose header equals
///   <c>foo</c> while matching one whose header does not — and the escaped literal <c>!foo</c>
///   (via <see cref="MatcherValue.Literal"/>) must match a header whose value really is
///   <c>!foo</c>.</description></item>
///   <item><description>Response actions (forward / error): the client registers a FORWARD and an
///   ERROR action and a real request proves the server actually forwards (the response body comes
///   from the forwarded-to expectation) and actually errors (the connection is dropped) —
///   not just that the expectation was created.</description></item>
/// </list>
///
/// <para>Reaches a running MockServer via the same harness the other integration tests use: the
/// <c>MOCKSERVER_URL</c> environment variable (for example <c>http://localhost:1080</c>). When it
/// is unset the tests skip, unless <c>MOCKSERVER_REQUIRE_SERVER=true</c> (set in CI) forces a loud
/// failure so a missing server can never be a silent green.</para>
/// </summary>
[Collection("Integration")]
public class WireBehaviorTests : IDisposable
{
    private readonly MockServerClient? _client;
    private readonly string? _baseUrl;
    private readonly string? _selfForwardHost;
    private readonly int _selfForwardPort;

    public WireBehaviorTests()
    {
        var url = Environment.GetEnvironmentVariable("MOCKSERVER_URL");
        if (string.IsNullOrEmpty(url))
        {
            _client = null;
            return;
        }

        _baseUrl = url.TrimEnd('/');
        var uri = new Uri(url);
        _client = new MockServerClient(uri.Host, uri.Port, secure: uri.Scheme == "https");
        // The server can always reach itself on its own loopback and listening port. This holds both
        // for a host-run jar (localhost:<port>) and for a containerised server (localhost:1080 inside
        // its own container), so a self-forward needs no knowledge of external networking.
        _selfForwardHost = "localhost";
        _selfForwardPort = uri.Port;
    }

    public void Dispose()
    {
        _client?.Dispose();
    }

    private void SkipIfNoServer()
    {
        if (_client == null
            && Environment.GetEnvironmentVariable("MOCKSERVER_REQUIRE_SERVER") == "true")
        {
            throw new InvalidOperationException(
                "MOCKSERVER_REQUIRE_SERVER=true but MOCKSERVER_URL is not set — refusing to skip.");
        }

        Skip.If(_client == null, "MOCKSERVER_URL environment variable not set; skipping integration test.");
    }

    private static HttpResponseMessage Get(string url, string? headerName = null, string? headerValue = null)
    {
        using var httpClient = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (headerName != null && headerValue != null)
        {
            request.Headers.TryAddWithoutValidation(headerName, headerValue);
        }
        return httpClient.Send(request);
    }

    // ── #44 NottableString negation over the wire ──────────────────────────────────────────────

    [SkippableFact]
    public void HeaderNegationMatcher_IsSentAndEnforced_OverWire()
    {
        SkipIfNoServer();
        _client!.Reset();

        // "X-Tag is NOT foo" — MatcherValue.NotLiteral serialises to the plain marker string "!foo",
        // which the server reads back as a negation.
        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/neg-header")
                .WithHeaderMatcher("X-Tag", MatcherValue.NotLiteral("foo"))
        ).Respond(
            HttpResponse.Response().WithStatusCode(200).WithBody("MATCHED-NOT-FOO")
        );

        // A non-foo header satisfies "not foo" → the expectation matches.
        var matched = Get($"{_baseUrl}/neg-header", "X-Tag", "bar");
        matched.StatusCode.Should().Be(HttpStatusCode.OK);
        matched.Content.ReadAsStringAsync().Result.Should().Be("MATCHED-NOT-FOO");

        // The excluded value "foo" must NOT match → no expectation → 404. This is the discrimination
        // that proves the "!" negation actually crossed the wire and the server enforced it; a client
        // that dropped the "!" would send a plain equality on "foo", making this request match (200)
        // and the bar request 404 — the exact inversion the positive control demonstrates.
        var excluded = Get($"{_baseUrl}/neg-header", "X-Tag", "foo");
        excluded.StatusCode.Should().Be(HttpStatusCode.NotFound);

        _client.Reset();
    }

    [SkippableFact]
    public void LiteralBangHeaderMatcher_IsSentAndEnforced_OverWire()
    {
        SkipIfNoServer();
        _client!.Reset();

        // Escaped literal: "X-Tag is exactly the string !foo" — MatcherValue.Literal escapes to the
        // object form {"not":false,"value":"!foo"} so the leading "!" is data, not a negation marker.
        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/lit-header")
                .WithHeaderMatcher("X-Tag", MatcherValue.Literal("!foo"))
        ).Respond(
            HttpResponse.Response().WithStatusCode(200).WithBody("MATCHED-LITERAL-BANG")
        );

        // Only a header whose value really is "!foo" matches.
        var literal = Get($"{_baseUrl}/lit-header", "X-Tag", "!foo");
        literal.StatusCode.Should().Be(HttpStatusCode.OK);
        literal.Content.ReadAsStringAsync().Result.Should().Be("MATCHED-LITERAL-BANG");

        // "foo" must NOT match a literal-"!foo" matcher (if the escape had been dropped and read as a
        // negation, "foo" would be excluded and any other value would match — a different behaviour).
        var plainFoo = Get($"{_baseUrl}/lit-header", "X-Tag", "foo");
        plainFoo.StatusCode.Should().Be(HttpStatusCode.NotFound);

        // An unrelated value must NOT match either (it would, had the escape collapsed to "not foo").
        var other = Get($"{_baseUrl}/lit-header", "X-Tag", "bar");
        other.StatusCode.Should().Be(HttpStatusCode.NotFound);

        _client.Reset();
    }

    // ── #46 response actions actually performed over the wire ──────────────────────────────────

    [SkippableFact]
    public void ForwardAction_IsActuallyForwarded_OverWire()
    {
        SkipIfNoServer();
        _client!.Reset();

        // Forward the request to the server itself (once). The forwarded request re-enters on the same
        // path; because the forward expectation is consumed (Times.Once) and lower-priority than the
        // response below is not needed — after consumption only the response expectation remains — the
        // second pass is served by the plain response, whose distinctive body proves the round trip.
        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/self-forward"),
            Times.Once(),
            timeToLive: null,
            priority: 10
        ).Forward(
            HttpForward.Forward().WithHost(_selfForwardHost!).WithPort(_selfForwardPort).WithScheme("HTTP")
        );

        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/self-forward")
        ).Respond(
            HttpResponse.Response().WithStatusCode(200).WithBody("FORWARDED-OK")
        );

        var response = Get($"{_baseUrl}/self-forward");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        response.Content.ReadAsStringAsync().Result.Should().Be("FORWARDED-OK");

        // The body alone is not proof: the response expectation matches /self-forward directly, so a
        // server that ignored the forward could still return "FORWARDED-OK". The decisive signal is
        // that the path was RECEIVED TWICE — once from the test client and once when the forward
        // re-entered the server — which only an actually-performed forward produces. A server that
        // dropped the forward would log exactly one receipt and this verification would fail.
        _client.Verify(
            HttpRequest.Request().WithMethod("GET").WithPath("/self-forward"),
            VerificationTimes.AtLeastTimes(2)
        );

        _client.Reset();
    }

    [SkippableFact]
    public void ErrorAction_ActuallyDropsConnection_OverWire()
    {
        SkipIfNoServer();
        _client!.Reset();

        _client.When(
            HttpRequest.Request().WithMethod("GET").WithPath("/drop-conn")
        ).Error(
            HttpError.Error().WithDropConnection(true)
        );

        // The server drops the connection instead of responding, so the client sees a transport
        // failure rather than any HTTP status. If the action had NOT been performed the request would
        // return 404 (no response expectation) and this assertion would fail — the positive control.
        var act = () => Get($"{_baseUrl}/drop-conn");
        act.Should().Throw<HttpRequestException>();

        _client.Reset();
    }
}
