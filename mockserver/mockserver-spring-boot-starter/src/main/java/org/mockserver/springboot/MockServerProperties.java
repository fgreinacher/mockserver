package org.mockserver.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MockServer Spring Boot starter, bound from the {@code mockserver.*}
 * namespace of the Spring {@code Environment} (application.properties / application.yml / system properties).
 *
 * <p>This starter is intended for local development and integration testing - it starts a real
 * {@link org.mockserver.integration.ClientAndServer} inside the application context. It is disabled by
 * default; set {@code mockserver.enabled=true} to switch it on.</p>
 */
@ConfigurationProperties(prefix = "mockserver")
public class MockServerProperties {

    /**
     * Whether to start MockServer and expose a {@code MockServerClient} bean. Disabled by default so that
     * merely having the starter on the classpath never changes application behaviour.
     */
    private boolean enabled = false;

    /**
     * Port to bind MockServer to. Defaults to {@code 0}, which binds a free ephemeral port; the actual
     * port is then available from the {@code MockServerClient}/{@code ClientAndServer} bean via
     * {@code getPort()}.
     */
    private Integer port = 0;

    /**
     * Optional path (file-system path or classpath resource) to an initialization JSON file describing
     * expectations to load on startup. Maps to {@code Configuration.initializationJsonPath}.
     */
    private String initializationJson;

    /**
     * Optional MockServer log level (e.g. {@code WARN}, {@code INFO}, {@code DEBUG}, {@code TRACE}, or
     * {@code OFF}). Maps to {@code Configuration.logLevel}.
     */
    private String logLevel;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getInitializationJson() {
        return initializationJson;
    }

    public void setInitializationJson(String initializationJson) {
        this.initializationJson = initializationJson;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }
}
