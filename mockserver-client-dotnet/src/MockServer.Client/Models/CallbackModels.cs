using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A class callback action. The expectation references a server-side class that
/// implements one of MockServer's callback interfaces
/// (<c>ExpectationResponseCallback</c> for response callbacks,
/// <c>ExpectationForwardCallback</c>/<c>ExpectationForwardAndResponseCallback</c>
/// for forward callbacks). This is a pure-JSON, REST-only action — no WebSocket is
/// involved. The class only needs to exist on the MockServer classpath at request
/// time; the control plane accepts and stores the expectation regardless.
/// </summary>
public sealed class HttpClassCallback
{
    /// <summary>
    /// Fully-qualified name of the server-side callback class
    /// (e.g. <c>com.example.MyCallback</c>).
    /// </summary>
    [JsonPropertyName("callbackClass")]
    public string? CallbackClass { get; set; }

    /// <summary>
    /// Optional delay applied before the callback's result is returned.
    /// </summary>
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>
    /// When true, marks this as a primary action (affects ordering/selection on the server).
    /// </summary>
    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Convenience factory for a class callback referencing <paramref name="callbackClass"/>.
    /// </summary>
    public static HttpClassCallback Of(string callbackClass) => new() { CallbackClass = callbackClass };
}

/// <summary>
/// An object (closure) callback action. The expectation references a callback
/// WebSocket client by its server-assigned <see cref="ClientId"/>; on a match the
/// server sends the request over that WebSocket and the client's registered closure
/// produces the response (or forward request). Requires an open callback WebSocket —
/// use <see cref="MockServerClient.MockWithCallback(HttpRequest, System.Func{HttpRequest, HttpResponse}, Times, TimeToLive)"/>
/// rather than constructing this directly.
/// </summary>
public sealed class HttpObjectCallback
{
    /// <summary>
    /// The callback WebSocket client id the server should dispatch matched requests to.
    /// </summary>
    [JsonPropertyName("clientId")]
    public string? ClientId { get; set; }

    /// <summary>
    /// When true, the server expects a response (rather than a forwarded request) back over the
    /// callback WebSocket. Serialised as the JSON boolean <c>responseCallback</c> — the server's
    /// <c>org.mockserver.model.HttpObjectCallback.responseCallback</c> is a <c>Boolean</c>
    /// (schema <c>httpObjectCallback.json</c> declares <c>"type": "boolean"</c>). Usually left unset.
    /// </summary>
    [JsonPropertyName("responseCallback")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? ResponseCallback { get; set; }

    /// <summary>
    /// Optional delay applied before the callback's result is returned.
    /// </summary>
    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>
    /// When true, marks this as a primary action (affects ordering/selection on the server).
    /// </summary>
    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }
}
