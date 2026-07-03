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

    /// <summary>
    /// <c>token_bucket</c>: token refill rate per second. Typed as <see cref="decimal"/> (not
    /// <see cref="double"/>) so a whole-number rate written on the wire as <c>5.0</c> preserves its
    /// trailing zero on round-trip (a <c>double</c> would re-serialise it as <c>5</c>).
    /// </summary>
    [JsonPropertyName("refillPerSecond")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public decimal? RefillPerSecond { get; set; }

    [JsonPropertyName("errorStatus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? ErrorStatus { get; set; }

    [JsonPropertyName("retryAfter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? RetryAfter { get; set; }
}
