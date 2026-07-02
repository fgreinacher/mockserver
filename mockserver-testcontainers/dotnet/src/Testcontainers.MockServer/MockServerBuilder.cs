namespace Testcontainers.MockServer;

using Docker.DotNet.Models;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Configurations;
using DotNet.Testcontainers.Containers;

/// <inheritdoc cref="ContainerBuilder{TBuilderEntity,TContainerEntity,TConfigurationEntity}" />
public sealed class MockServerBuilder
    : ContainerBuilder<MockServerBuilder, MockServerContainer, MockServerConfiguration>
{
    /// <summary>
    /// The default MockServer Docker image name.
    /// </summary>
    public const string MockServerImage = MockServerContainer.ImageName;

    /// <summary>
    /// The default fully-qualified MockServer Docker image, derived from the package version
    /// (see <see cref="MockServerContainer.DefaultImage" />) so it stays in lockstep with the release.
    /// </summary>
    public static string DefaultImage => MockServerContainer.DefaultImage;

    /// <summary>
    /// The default MockServer port. MockServer serves HTTP, HTTPS, SOCKS, and HTTP CONNECT
    /// on a single unified port.
    /// </summary>
    public const int MockServerPort = 1080;

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerBuilder" /> class.
    /// </summary>
    public MockServerBuilder()
        : this(new MockServerConfiguration())
    {
        DockerResourceConfiguration = Init().DockerResourceConfiguration;
    }

    private MockServerBuilder(MockServerConfiguration resourceConfiguration)
        : base(resourceConfiguration)
    {
        DockerResourceConfiguration = resourceConfiguration;
    }

    /// <inheritdoc />
    protected override MockServerConfiguration DockerResourceConfiguration { get; }

    /// <summary>
    /// Sets the MockServer log level.
    /// </summary>
    /// <param name="logLevel">The log level (e.g., "INFO", "DEBUG", "WARN", "ERROR", "TRACE").</param>
    /// <returns>A configured instance of <see cref="MockServerBuilder" />.</returns>
    public MockServerBuilder WithLogLevel(string logLevel)
    {
        return Merge(DockerResourceConfiguration, new MockServerConfiguration(logLevel: logLevel))
            .WithEnvironment("MOCKSERVER_LOG_LEVEL", logLevel);
    }

    /// <summary>
    /// Sets a MockServer property as an environment variable.
    /// </summary>
    /// <param name="key">The MockServer env-var key (e.g., "MOCKSERVER_MAX_EXPECTATIONS").</param>
    /// <param name="value">The value to set.</param>
    /// <returns>A configured instance of <see cref="MockServerBuilder" />.</returns>
    public MockServerBuilder WithMockServerProperty(string key, string value)
    {
        return WithEnvironment(key, value);
    }

    /// <inheritdoc />
    public override MockServerContainer Build()
    {
        Validate();
        return new MockServerContainer(DockerResourceConfiguration);
    }

    /// <inheritdoc />
    protected override MockServerBuilder Init()
    {
        return base.Init()
            .WithImage(MockServerContainer.DefaultImage)
            .WithPortBinding(MockServerPort, true)
            .WithEnvironment("SERVER_PORT", MockServerPort.ToString())
            .WithWaitStrategy(Wait.ForUnixContainer()
                .UntilHttpRequestIsSucceeded(request => request
                    .ForPort((ushort)MockServerPort)
                    .ForPath("/mockserver/status")
                    .WithMethod(HttpMethod.Put)
                    .ForStatusCode(System.Net.HttpStatusCode.OK)));
    }

    /// <inheritdoc />
    protected override MockServerBuilder Clone(IResourceConfiguration<CreateContainerParameters> resourceConfiguration)
    {
        return Merge(DockerResourceConfiguration, new MockServerConfiguration(resourceConfiguration));
    }

    /// <inheritdoc />
    protected override MockServerBuilder Clone(IContainerConfiguration resourceConfiguration)
    {
        return Merge(DockerResourceConfiguration, new MockServerConfiguration(resourceConfiguration));
    }

    /// <inheritdoc />
    protected override MockServerBuilder Merge(
        MockServerConfiguration oldValue,
        MockServerConfiguration newValue)
    {
        return new MockServerBuilder(new MockServerConfiguration(oldValue, newValue));
    }
}
