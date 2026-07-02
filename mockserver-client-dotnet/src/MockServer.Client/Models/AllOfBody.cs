using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// An <c>ALL_OF</c> body matcher — every element of <see cref="BodyAllOf"/> must match the
/// request body. Serialises as <c>{"type":"ALL_OF","bodyAllOf":[ ... ]}</c>.
/// </summary>
public sealed class AllOfBody
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "ALL_OF";

    /// <summary>
    /// The sub-matchers that must all match. Each element is a typed body matcher
    /// (e.g. <see cref="BodyMatcher"/>, <see cref="TypedBody"/>) serialised as the client
    /// serialises any other body.
    /// </summary>
    [JsonPropertyName("bodyAllOf")]
    public List<object> BodyAllOf { get; set; } = new();
}

/// <summary>
/// A typed body matcher for the non-JSON matcher variants used inside an
/// <see cref="AllOfBody"/> (e.g. <c>JSON_PATH</c>, <c>REGEX</c>). Mirrors <see cref="TypedBody"/>;
/// unset fields are omitted from the emitted JSON so each variant serialises to exactly its
/// own shape (e.g. <c>{"type":"JSON_PATH","jsonPath":"$.name"}</c>).
/// </summary>
public sealed class BodyMatcher
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "STRING";

    [JsonPropertyName("json")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Json { get; set; }

    [JsonPropertyName("jsonPath")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? JsonPath { get; set; }

    [JsonPropertyName("regex")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Regex { get; set; }

    /// <summary>Creates a <c>JSON_PATH</c> body matcher.</summary>
    public static BodyMatcher OfJsonPath(string jsonPath) => new() { Type = "JSON_PATH", JsonPath = jsonPath };

    /// <summary>Creates a <c>REGEX</c> body matcher.</summary>
    public static BodyMatcher OfRegex(string regex) => new() { Type = "REGEX", Regex = regex };

    /// <summary>Creates a <c>JSON</c> body matcher.</summary>
    public static BodyMatcher OfJson(string json) => new() { Type = "JSON", Json = json };
}
