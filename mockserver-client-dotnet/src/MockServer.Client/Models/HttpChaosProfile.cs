using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Declarative HTTP chaos / fault-injection profile applied to an expectation
/// (serialised under the expectation's <c>"chaos"</c> property). Every property is optional; unset
/// properties are omitted. Mirrors the server's <c>httpChaosProfile</c> schema.
/// </summary>
public sealed class HttpChaosProfile
{
    [JsonPropertyName("errorStatus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? ErrorStatus { get; set; }

    [JsonPropertyName("retryAfter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? RetryAfter { get; set; }

    [JsonPropertyName("errorProbability")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? ErrorProbability { get; set; }

    [JsonPropertyName("dropConnectionProbability")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? DropConnectionProbability { get; set; }

    [JsonPropertyName("latency")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Latency { get; set; }

    [JsonPropertyName("seed")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? Seed { get; set; }

    [JsonPropertyName("succeedFirst")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? SucceedFirst { get; set; }

    [JsonPropertyName("failRequestCount")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? FailRequestCount { get; set; }

    [JsonPropertyName("outageAfterMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? OutageAfterMillis { get; set; }

    [JsonPropertyName("outageDurationMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? OutageDurationMillis { get; set; }

    [JsonPropertyName("truncateBodyAtFraction")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? TruncateBodyAtFraction { get; set; }

    [JsonPropertyName("malformedBody")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? MalformedBody { get; set; }

    [JsonPropertyName("slowResponseChunkSize")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? SlowResponseChunkSize { get; set; }

    [JsonPropertyName("slowResponseChunkDelay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? SlowResponseChunkDelay { get; set; }

    [JsonPropertyName("quotaName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? QuotaName { get; set; }

    [JsonPropertyName("quotaLimit")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? QuotaLimit { get; set; }

    [JsonPropertyName("quotaWindowMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? QuotaWindowMillis { get; set; }

    [JsonPropertyName("quotaErrorStatus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? QuotaErrorStatus { get; set; }

    [JsonPropertyName("degradationRampMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? DegradationRampMillis { get; set; }

    [JsonPropertyName("graphqlErrors")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? GraphqlErrors { get; set; }

    [JsonPropertyName("graphqlErrorMessage")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? GraphqlErrorMessage { get; set; }

    [JsonPropertyName("graphqlErrorCode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? GraphqlErrorCode { get; set; }

    [JsonPropertyName("graphqlNullifyData")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? GraphqlNullifyData { get; set; }
}
