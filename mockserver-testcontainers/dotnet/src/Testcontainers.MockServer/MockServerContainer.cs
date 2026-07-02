namespace Testcontainers.MockServer;

using System.Reflection;
using System.Text.RegularExpressions;
using DotNet.Testcontainers.Containers;
// Alias with global:: — inside the Testcontainers.MockServer namespace the bare
// identifier "MockServer" would otherwise bind to this namespace, not the client's.
using MockServerClient = global::MockServer.Client.MockServerClient;

/// <summary>
/// A Testcontainers container for MockServer. Starts a <c>mockserver/mockserver</c> Docker image,
/// waits for the server to become ready, and provides connection helpers.
/// <para>
/// MockServer serves HTTP, HTTPS, SOCKS, and HTTP CONNECT on a single unified port (default 1080).
/// </para>
/// </summary>
public sealed class MockServerContainer : DockerContainer
{
    /// <summary>
    /// The MockServer Docker Hub image name (without a tag).
    /// </summary>
    public const string ImageName = "mockserver/mockserver";

    /// <summary>
    /// The default container port (HTTP, HTTPS, SOCKS, and HTTP CONNECT all served on one port).
    /// </summary>
    public const int DefaultPort = 1080;

    /// <summary>
    /// The default fully-qualified Docker image, derived from this package's own
    /// version so the container image stays in lockstep with the release, mirroring the
    /// Java module. The tag is <c>mockserver-&lt;version&gt;</c>, or <c>latest</c> when the
    /// version cannot be resolved to a clean release version.
    /// </summary>
    public static string DefaultImage { get; } = ResolveDefaultImage();

    private readonly MockServerConfiguration _configuration;

    private MockServerClient? _client;

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerContainer" /> class.
    /// </summary>
    /// <param name="configuration">The container configuration.</param>
    public MockServerContainer(MockServerConfiguration configuration)
        : base(configuration)
    {
        _configuration = configuration;
    }

    /// <summary>
    /// Gets the mapped host port for MockServer.
    /// </summary>
    /// <returns>The host port mapped to the MockServer container port.</returns>
    public ushort GetMappedPort()
    {
        return GetMappedPublicPort(DefaultPort);
    }

    /// <summary>
    /// Gets the HTTP endpoint URL for MockServer.
    /// </summary>
    /// <returns>The URL in the form <c>http://host:port</c>.</returns>
    public string GetUrl()
    {
        return $"http://{Hostname}:{GetMappedPort()}";
    }

    /// <summary>
    /// Gets the HTTPS endpoint URL for MockServer.
    /// MockServer serves HTTP and HTTPS on the same unified port.
    /// </summary>
    /// <returns>The URL in the form <c>https://host:port</c>.</returns>
    public string GetSecureUrl()
    {
        return $"https://{Hostname}:{GetMappedPort()}";
    }

    /// <summary>
    /// Gets the MockServer connection string in the format <c>host:port</c>.
    /// </summary>
    /// <returns>The connection string.</returns>
    public string GetConnectionString()
    {
        return $"{Hostname}:{GetMappedPort()}";
    }

    /// <summary>
    /// Returns a <see cref="MockServerClient" /> connected to this container. The client is
    /// created lazily on first call and cached. Mirrors the Java module's <c>getClient()</c>.
    /// </summary>
    /// <returns>A MockServerClient wired to the container's mapped host and port.</returns>
    public MockServerClient GetClient()
    {
        return _client ??= new MockServerClient(Hostname, GetMappedPort());
    }

    private static string ResolveDefaultImage()
    {
        var informational = typeof(MockServerContainer).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;

        // InformationalVersion may carry build metadata (e.g. "7.3.0+<sha>") — take the semver prefix.
        var semver = informational?.Split('+')[0];
        if (semver != null && Regex.IsMatch(semver, @"^\d+\.\d+\.\d+$"))
        {
            return $"{ImageName}:mockserver-{semver}";
        }

        return $"{ImageName}:latest";
    }
}
