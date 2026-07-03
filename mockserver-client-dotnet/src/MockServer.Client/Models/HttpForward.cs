using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents an HTTP forward action for MockServer.
/// </summary>
public sealed class HttpForward
{
    [JsonPropertyName("host")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Host { get; set; }

    [JsonPropertyName("port")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Port { get; set; }

    [JsonPropertyName("scheme")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Scheme { get; set; }

    /// <summary>
    /// Optional delay applied before the request is forwarded. Mirrors the server's
    /// <c>httpForward.json</c> <c>delay</c> property.
    /// </summary>
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>
    /// Creates a new HttpForward builder.
    /// </summary>
    public static HttpForwardBuilder Forward() => new();
}
