namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpError"/>.
/// </summary>
public sealed class HttpErrorBuilder
{
    private readonly HttpError _error = new();

    public HttpErrorBuilder WithDropConnection(bool drop)
    {
        _error.DropConnection = drop;
        return this;
    }

    public HttpErrorBuilder WithResponseBytes(string base64Bytes)
    {
        _error.ResponseBytes = base64Bytes;
        return this;
    }

    public HttpErrorBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _error.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public HttpError Build() => _error;

    /// <summary>
    /// Implicit conversion to HttpError for ergonomic use.
    /// </summary>
    public static implicit operator HttpError(HttpErrorBuilder builder) => builder.Build();
}
