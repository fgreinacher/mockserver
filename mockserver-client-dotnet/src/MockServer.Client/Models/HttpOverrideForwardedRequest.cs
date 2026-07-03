using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents an override-forwarded-request action for MockServer. The matched request is
/// forwarded after applying <see cref="HttpRequest"/> as a request override (host/port/scheme,
/// headers, body), and the response returned to the caller is produced by
/// <see cref="ResponseTemplate"/> rather than the upstream response.
/// Mirrors <c>org.mockserver.model.HttpOverrideForwardedRequest</c>; the request-override
/// field is serialised as <c>httpRequest</c> on the wire.
/// </summary>
public sealed class HttpOverrideForwardedRequest
{
    /// <summary>
    /// The request override applied before forwarding. Serialised as <c>httpRequest</c>
    /// (matching the server-side <c>@JsonAlias("httpRequest")</c> on <c>requestOverride</c>).
    /// </summary>
    [JsonPropertyName("httpRequest")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpRequest? HttpRequest { get; set; }

    /// <summary>
    /// A static response returned to the caller instead of forwarding upstream. Serialised as
    /// <c>httpResponse</c> — the second <c>oneOf</c> variant of the server's
    /// <c>httpOverrideForwardedRequest.json</c> schema (<c>httpRequest</c> + <c>httpResponse</c>).
    /// </summary>
    [JsonPropertyName("httpResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpResponse? HttpResponse { get; set; }

    [JsonPropertyName("responseTemplate")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpTemplate? ResponseTemplate { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }
}
