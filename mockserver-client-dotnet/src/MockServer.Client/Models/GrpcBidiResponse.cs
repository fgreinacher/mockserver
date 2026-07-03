using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A single message within a <see cref="GrpcBidiResponse"/> (or a rule response). The
/// <see cref="Json"/> field carries the JSON representation of the protobuf message.
/// </summary>
public sealed class GrpcBidiMessage
{
    [JsonPropertyName("json")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Json { get; set; }

    /// <summary>Optional template engine for the message: <c>VELOCITY</c>, <c>JAVASCRIPT</c> or <c>MUSTACHE</c>.</summary>
    [JsonPropertyName("templateType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? TemplateType { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }
}

/// <summary>
/// A match rule within a <see cref="GrpcBidiResponse"/>: when an incoming client message matches
/// <see cref="MatchJson"/>, the configured <see cref="Responses"/> are streamed back.
/// </summary>
public sealed class GrpcBidiRule
{
    [JsonPropertyName("matchJson")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? MatchJson { get; set; }

    [JsonPropertyName("responses")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<GrpcBidiMessage>? Responses { get; set; }
}

/// <summary>
/// A bidirectional-streaming gRPC response action (serialised under the expectation's
/// <c>"grpcBidiResponse"</c> property). Mirrors the server's <c>grpcBidiResponse</c> schema.
/// </summary>
public sealed class GrpcBidiResponse
{
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("headers")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? Headers { get; set; }

    [JsonPropertyName("statusName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? StatusName { get; set; }

    [JsonPropertyName("statusMessage")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? StatusMessage { get; set; }

    /// <summary>Messages streamed back unconditionally (independent of <see cref="Rules"/>).</summary>
    [JsonPropertyName("messages")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<GrpcBidiMessage>? Messages { get; set; }

    /// <summary>Per-incoming-message match rules producing streamed responses.</summary>
    [JsonPropertyName("rules")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<GrpcBidiRule>? Rules { get; set; }

    [JsonPropertyName("closeConnection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CloseConnection { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }
}
