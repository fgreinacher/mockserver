using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Forward action that additionally validates the request and/or response against an OpenAPI spec
/// (serialised under the expectation's <c>"httpForwardValidateAction"</c> property). Mirrors the
/// server's <c>httpForwardValidateAction</c> schema.
/// </summary>
public sealed class HttpForwardValidateAction
{
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>The OpenAPI spec URL or inline payload to validate against.</summary>
    [JsonPropertyName("specUrlOrPayload")]
    public string? SpecUrlOrPayload { get; set; }

    [JsonPropertyName("host")]
    public string? Host { get; set; }

    [JsonPropertyName("port")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Port { get; set; }

    /// <summary>Upstream scheme: <c>HTTP</c> or <c>HTTPS</c>.</summary>
    [JsonPropertyName("scheme")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Scheme { get; set; }

    [JsonPropertyName("validateRequest")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? ValidateRequest { get; set; }

    [JsonPropertyName("validateResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? ValidateResponse { get; set; }

    /// <summary>Validation mode: <c>STRICT</c> or <c>LOG_ONLY</c>.</summary>
    [JsonPropertyName("validationMode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ValidationMode { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }
}
