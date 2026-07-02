using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents an HTTP request matcher for MockServer.
/// </summary>
public sealed class HttpRequest
{
    [JsonPropertyName("method")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Method { get; set; }

    [JsonPropertyName("path")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Path { get; set; }

    [JsonPropertyName("queryStringParameters")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? QueryStringParameters { get; set; }

    [JsonPropertyName("headers")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? Headers { get; set; }

    [JsonPropertyName("body")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public object? Body { get; set; }

    /// <summary>
    /// JWT matcher applied to a bearer token on the request (typically the <c>Authorization</c>
    /// header). Serialised under the <c>"jwt"</c> property alongside method/path/headers.
    /// </summary>
    [JsonPropertyName("jwt")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Jwt? Jwt { get; set; }

    [JsonPropertyName("secure")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Secure { get; set; }

    /// <summary>
    /// Overrides the host/port/scheme used to connect when this request is forwarded
    /// (e.g. as the request override of an override-forwarded-request action), independent
    /// of the <see cref="Path"/> and <c>Host</c> header sent on the wire.
    /// </summary>
    [JsonPropertyName("socketAddress")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public SocketAddress? SocketAddress { get; set; }

    [JsonPropertyName("keepAlive")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? KeepAlive { get; set; }

    /// <summary>
    /// Creates a new HttpRequest builder.
    /// </summary>
    public static HttpRequestBuilder Request() => new();
}
