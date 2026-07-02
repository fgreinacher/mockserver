using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a JWT request matcher. Serialised under the request's <c>"jwt"</c> property
/// alongside method/path/headers.
/// </summary>
/// <remarks>
/// <see cref="Claims"/> maps a claim name to an exact-or-regex string value. A <c>"!"</c>
/// prefix on a value negates the match. The remaining properties are optional constraints on
/// the token; unset properties are omitted from the emitted JSON.
/// </remarks>
public sealed class Jwt
{
    /// <summary>
    /// Claim name to exact-or-regex value. A leading <c>"!"</c> negates the match.
    /// </summary>
    [JsonPropertyName("claims")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, string>? Claims { get; set; }

    [JsonPropertyName("issuer")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Issuer { get; set; }

    [JsonPropertyName("audience")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Audience { get; set; }

    [JsonPropertyName("algorithm")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Algorithm { get; set; }

    [JsonPropertyName("header")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Header { get; set; }

    [JsonPropertyName("scheme")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Scheme { get; set; }
}
