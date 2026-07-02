namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpRequest"/>.
/// </summary>
public sealed class HttpRequestBuilder
{
    private readonly HttpRequest _request = new();

    public HttpRequestBuilder WithMethod(string method)
    {
        _request.Method = method;
        return this;
    }

    public HttpRequestBuilder WithPath(string path)
    {
        _request.Path = path;
        return this;
    }

    public HttpRequestBuilder WithQueryStringParameter(string name, params string[] values)
    {
        _request.QueryStringParameters ??= new Dictionary<string, List<string>>();
        _request.QueryStringParameters[name] = new List<string>(values);
        return this;
    }

    public HttpRequestBuilder WithHeader(string name, params string[] values)
    {
        _request.Headers ??= new Dictionary<string, List<string>>();
        _request.Headers[name] = new List<string>(values);
        return this;
    }

    /// <summary>
    /// Sets a plain string body matcher.
    /// </summary>
    public HttpRequestBuilder WithBody(string body)
    {
        _request.Body = body;
        return this;
    }

    /// <summary>
    /// Sets a typed JSON body matcher.
    /// </summary>
    public HttpRequestBuilder WithJsonBody(string json)
    {
        _request.Body = new TypedBody { Type = "JSON", Json = json };
        return this;
    }

    /// <summary>
    /// Sets a FILE body matcher with an optional template type.
    /// </summary>
    public HttpRequestBuilder WithFileBody(string filePath, string? contentType = null, FileTemplateType? templateType = null)
    {
        _request.Body = new FileBody { FilePath = filePath, ContentType = contentType, TemplateType = templateType };
        return this;
    }

    /// <summary>
    /// Sets an <c>ALL_OF</c> body matcher — the request body must match every supplied
    /// sub-matcher. Emits <c>{"type":"ALL_OF","bodyAllOf":[ ... ]}</c>.
    /// </summary>
    public HttpRequestBuilder WithAllOfBody(params BodyMatcher[] bodies)
    {
        _request.Body = new AllOfBody { BodyAllOf = bodies.Cast<object>().ToList() };
        return this;
    }

    /// <summary>
    /// Sets a JWT request matcher, serialised under the request's <c>"jwt"</c> property.
    /// </summary>
    public HttpRequestBuilder WithJwt(Jwt jwt)
    {
        _request.Jwt = jwt;
        return this;
    }

    public HttpRequestBuilder WithSecure(bool secure)
    {
        _request.Secure = secure;
        return this;
    }

    public HttpRequestBuilder WithKeepAlive(bool keepAlive)
    {
        _request.KeepAlive = keepAlive;
        return this;
    }

    public HttpRequest Build() => _request;

    /// <summary>
    /// Implicit conversion to HttpRequest for ergonomic use.
    /// </summary>
    public static implicit operator HttpRequest(HttpRequestBuilder builder) => builder.Build();
}
