using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Declarative, protocol-agnostic rate limit / quota applied to an expectation (serialised under the
/// expectation's <c>"rateLimit"</c> property). Mirrors the server's <c>rateLimit</c> schema.
/// </summary>
public sealed class RateLimit
{
    /// <summary>
    /// Shared counter key; expectations with the same name share one rate-limit counter. When omitted
    /// the expectation id is used as the key.
    /// </summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>Rate-limiting algorithm: <c>fixed_window</c> (default) or <c>token_bucket</c>.</summary>
    [JsonPropertyName("algorithm")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Algorithm { get; set; }

    [JsonPropertyName("limit")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Limit { get; set; }

    [JsonPropertyName("windowMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? WindowMillis { get; set; }

    [JsonPropertyName("burst")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Burst { get; set; }

    [JsonPropertyName("refillPerSecond")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? RefillPerSecond { get; set; }

    [JsonPropertyName("errorStatus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? ErrorStatus { get; set; }

    [JsonPropertyName("retryAfter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? RetryAfter { get; set; }
}
