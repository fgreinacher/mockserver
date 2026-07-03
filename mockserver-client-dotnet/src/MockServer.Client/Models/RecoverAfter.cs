using System.Text.Json;
using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Response "recover after" policy: return <see cref="FailResponse"/> for the first
/// <see cref="FailTimes"/> matches, then recover to the expectation's normal response. Serialised
/// under an <see cref="HttpResponse"/>'s <c>"recoverAfter"</c> property. Mirrors the server's
/// <c>recoverAfter</c> schema.
/// </summary>
public sealed class RecoverAfter
{
    [JsonPropertyName("failTimes")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? FailTimes { get; set; }

    /// <summary>
    /// The failure response returned before recovery. Kept as a raw <see cref="JsonElement"/> because
    /// the server models it as an open object.
    /// </summary>
    [JsonPropertyName("failResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public JsonElement? FailResponse { get; set; }

    [JsonPropertyName("idempotencyHeader")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? IdempotencyHeader { get; set; }
}
