using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A single message within a <see cref="GrpcStreamResponse"/>. The
/// <see cref="Json"/> field carries the JSON representation of the protobuf
/// message to stream back to the client.
/// </summary>
public sealed class GrpcStreamMessage
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

    /// <summary>
    /// Creates a gRPC stream message from a JSON-encoded protobuf message.
    /// </summary>
    public static GrpcStreamMessage OfJson(string json) => new() { Json = json };
}

/// <summary>
/// Represents a server-streaming gRPC response action for MockServer. Each
/// configured message is streamed to the client, followed by the gRPC status.
/// </summary>
public sealed class GrpcStreamResponse
{
    [JsonPropertyName("statusName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? StatusName { get; set; }

    [JsonPropertyName("statusMessage")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? StatusMessage { get; set; }

    [JsonPropertyName("headers")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? Headers { get; set; }

    [JsonPropertyName("messages")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<GrpcStreamMessage>? Messages { get; set; }

    [JsonPropertyName("closeConnection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CloseConnection { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Creates a new gRPC stream response builder.
    /// </summary>
    public static GrpcStreamResponseBuilder Response() => new();
}
