using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Forward action that falls back to a canned response when the upstream forward fails or returns a
/// configured status code (serialised under the expectation's <c>"httpForwardWithFallback"</c>
/// property). Mirrors the server's <c>httpForwardWithFallback</c> schema.
/// </summary>
public sealed class HttpForwardWithFallback
{
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>The upstream forward attempted first.</summary>
    [JsonPropertyName("httpForward")]
    public HttpForward? HttpForward { get; set; }

    /// <summary>The response returned when the forward triggers the fallback.</summary>
    [JsonPropertyName("fallbackResponse")]
    public HttpResponse? FallbackResponse { get; set; }

    /// <summary>Upstream status codes that trigger the fallback response.</summary>
    [JsonPropertyName("fallbackOnStatusCodes")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<int>? FallbackOnStatusCodes { get; set; }

    /// <summary>When true a forward timeout triggers the fallback response.</summary>
    [JsonPropertyName("fallbackOnTimeout")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? FallbackOnTimeout { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }
}
