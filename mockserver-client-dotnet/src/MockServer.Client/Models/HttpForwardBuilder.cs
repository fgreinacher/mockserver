namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpForward"/>.
/// </summary>
public sealed class HttpForwardBuilder
{
    private readonly HttpForward _forward = new();

    public HttpForwardBuilder WithHost(string host)
    {
        _forward.Host = host;
        return this;
    }

    public HttpForwardBuilder WithPort(int port)
    {
        _forward.Port = port;
        return this;
    }

    public HttpForwardBuilder WithScheme(string scheme)
    {
        _forward.Scheme = scheme;
        return this;
    }

    public HttpForwardBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _forward.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    /// <summary>Marks this as the primary action when the expectation configures more than one.</summary>
    public HttpForwardBuilder WithPrimary(bool primary = true)
    {
        _forward.Primary = primary;
        return this;
    }

    public HttpForward Build() => _forward;

    /// <summary>
    /// Implicit conversion to HttpForward for ergonomic use.
    /// </summary>
    public static implicit operator HttpForward(HttpForwardBuilder builder) => builder.Build();
}
