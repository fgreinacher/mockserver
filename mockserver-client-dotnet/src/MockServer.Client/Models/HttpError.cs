using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents an HTTP error action for MockServer (drops/corrupts connections).
/// </summary>
public sealed class HttpError
{
    [JsonPropertyName("dropConnection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? DropConnection { get; set; }

    [JsonPropertyName("responseBytes")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ResponseBytes { get; set; }

    /// <summary>
    /// Optional delay applied before the error behaviour is triggered. Mirrors the server's
    /// <c>httpError.json</c> <c>delay</c> property.
    /// </summary>
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>
    /// Creates a new HttpError builder.
    /// </summary>
    public static HttpErrorBuilder Error() => new();
}
