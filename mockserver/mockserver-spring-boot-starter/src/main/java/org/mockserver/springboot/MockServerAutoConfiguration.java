package org.mockserver.springboot;

import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import static org.mockserver.configuration.Configuration.configuration;

/**
 * Spring Boot auto-configuration that starts a {@link ClientAndServer} and exposes it as a
 * {@link MockServerClient} bean, driven by {@code mockserver.*} properties (see {@link MockServerProperties}).
 *
 * <p><strong>Development / test only.</strong> This starts a real MockServer instance inside the
 * application context, so it is intended for integration tests and local development - not production.
 * It is disabled by default and only activates when {@code mockserver.enabled=true} is set.</p>
 *
 * <p>Lifecycle: the {@link ClientAndServer} bean is created when the application context is refreshed
 * (server starts) and stopped when the context is closed (via the bean {@code destroyMethod}).</p>
 *
 * <p>A single {@link ClientAndServer} bean is registered. Because {@link ClientAndServer} extends
 * {@link MockServerClient}, that one bean satisfies injection points typed as either
 * {@code ClientAndServer} or {@code MockServerClient} (and {@code getBean(MockServerClient.class)}).
 * Register your own {@code ClientAndServer} bean to override the auto-configured one - the
 * {@link ConditionalOnMissingBean} guard then backs off.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mockserver", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MockServerProperties.class)
public class MockServerAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockServerAutoConfiguration.class);

    /**
     * Starts a MockServer instance and exposes it as a {@link ClientAndServer} bean. Because
     * {@link ClientAndServer} extends {@link MockServerClient}, this single bean also satisfies
     * injection points that request a {@link MockServerClient}.
     *
     * <p>The bean's {@code destroyMethod} is {@code stop}, so the embedded server is shut down when the
     * Spring context is closed.</p>
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public ClientAndServer mockServerClientAndServer(MockServerProperties properties) {
        Configuration configuration = configuration();
        if (StringUtils.hasText(properties.getInitializationJson())) {
            configuration.initializationJsonPath(properties.getInitializationJson());
        }
        if (StringUtils.hasText(properties.getLogLevel())) {
            configuration.logLevel(properties.getLogLevel());
        }
        Integer port = properties.getPort() == null ? 0 : properties.getPort();
        ClientAndServer clientAndServer = ClientAndServer.startClientAndServer(configuration, port);
        LOGGER.info("Started MockServer via Spring Boot starter on port {}", clientAndServer.getPort());
        return clientAndServer;
    }
}
