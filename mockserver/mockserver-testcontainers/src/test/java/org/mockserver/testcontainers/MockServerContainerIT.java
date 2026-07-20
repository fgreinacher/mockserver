package org.mockserver.testcontainers;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration tests for {@link MockServerContainer} that require a running Docker daemon.
 * <p>
 * <strong>This class does not currently execute in the Maven build.</strong> Surefire is configured
 * for {@code **}{@code /*Test.java} and Failsafe for {@code **}{@code /*IntegrationTest.java}; a
 * class ending in {@code IT} matches neither, and this module declares no override — so contrary to
 * the previous note here, it runs neither locally nor in CI. Renaming it to
 * {@code MockServerContainerIntegrationTest} (or adding a Failsafe include for
 * {@code **}{@code /*IT.java}) would activate it. Until then treat its coverage as absent.
 * <p>
 * The Docker gate below is written the correct way regardless: it goes through
 * {@link org.mockserver.test.DockerAvailability} rather than calling
 * {@code DockerClientFactory.instance().isDockerAvailable()} directly, because the raw probe throws
 * rather than returning {@code false} for anything other than {@code IllegalStateException}.
 */
class MockServerContainerIT {

    @Test
    void containerStartsAndAcceptsMockExpectation() throws Exception {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning false
        // for post-connection failures (e.g. Ryuk rejected by a user-namespace remapped
        // daemon), which would turn this skip into a hard ERROR.
        Assumptions.assumeTrue(
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable()),
            "Docker is not available — skipping integration test"
        );

        try (MockServerContainer container = new MockServerContainer(
            // Use a known good image for integration testing
            org.testcontainers.utility.DockerImageName.parse("mockserver/mockserver:latest")
        )) {
            container.start();

            // Create an expectation via the client
            MockServerClient client = container.getClient();
            client.when(
                request().withMethod("GET").withPath("/hello")
            ).respond(
                response().withStatusCode(200).withBody("world")
            );

            // Issue a matching HTTP request
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(container.getEndpoint() + "/hello"))
                .GET()
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            assertThat(httpResponse.statusCode(), is(200));
            assertThat(httpResponse.body(), is("world"));

            // Verify endpoint format
            assertThat(container.getEndpoint(), startsWith("http://"));
            assertThat(container.getSecureEndpoint(), startsWith("https://"));
        }
    }
}
