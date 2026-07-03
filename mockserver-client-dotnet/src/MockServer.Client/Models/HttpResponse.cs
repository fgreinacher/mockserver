using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents an HTTP response action for MockServer.
/// </summary>
public sealed class HttpResponse
{
    [JsonPropertyName("statusCode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? StatusCode { get; set; }

    [JsonPropertyName("reasonPhrase")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ReasonPhrase { get; set; }

    /// <summary>A status-code range string (e.g. <c>"2xx"</c>) used when matching a response.</summary>
    [JsonPropertyName("statusCodeRange")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? StatusCodeRange { get; set; }

    [JsonPropertyName("headers")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? Headers { get; set; }

    /// <summary>HTTP trailing headers (trailers) to return.</summary>
    [JsonPropertyName("trailers")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? Trailers { get; set; }

    /// <summary>Response cookies (single value per name). Serialised as the <c>keyToValue</c> map form.</summary>
    [JsonPropertyName("cookies")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, string>? Cookies { get; set; }

    /// <summary>
    /// The response body. Accepts a raw string, <see cref="TypedBody"/>, <see cref="FileBody"/>, or the
    /// fully-typed <see cref="Models.Body"/>; an unrecognised shape round-trips as a raw <c>JsonElement</c>.
    /// </summary>
    [JsonPropertyName("body")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public object? Body { get; set; }

    /// <summary>When set, the body is generated from this JSON schema string.</summary>
    [JsonPropertyName("generateFromSchema")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? GenerateFromSchema { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("connectionOptions")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public ConnectionOptions? ConnectionOptions { get; set; }

    /// <summary>Response "recover after" policy (fail first N times, then recover to normal response).</summary>
    [JsonPropertyName("recoverAfter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public RecoverAfter? RecoverAfter { get; set; }

    /// <summary>Marks this as the primary response within a multi-response set.</summary>
    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Creates a new HttpResponse builder.
    /// </summary>
    public static HttpResponseBuilder Response() => new();
}
