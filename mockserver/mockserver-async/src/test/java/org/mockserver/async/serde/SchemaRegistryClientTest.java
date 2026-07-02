package org.mockserver.async.serde;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SchemaRegistryClient} using a stubbed {@link HttpClient}
 * (no live Schema Registry).
 */
public class SchemaRegistryClientTest {

    private static final String AVRO_SCHEMA =
        "{\"type\":\"record\",\"name\":\"Order\",\"fields\":[{\"name\":\"orderId\",\"type\":\"int\"}]}";

    private HttpClient httpClient;
    private SchemaRegistryClient client;

    @Before
    public void setUp() {
        httpClient = mock(HttpClient.class);
        client = new SchemaRegistryClient("http://registry:8081/", httpClient);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldGetSchemaById() throws Exception {
        HttpResponse<String> resp = response(200, "{\"schema\":" + quote(AVRO_SCHEMA) + "}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        String schema = client.getSchemaById(5);
        assertThat(schema, is(AVRO_SCHEMA));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString(), is("http://registry:8081/schemas/ids/5"));
        assertThat(captor.getValue().method(), is("GET"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldCacheSchemaById() throws Exception {
        HttpResponse<String> resp = response(200, "{\"schema\":" + quote(AVRO_SCHEMA) + "}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        client.getSchemaById(9);
        client.getSchemaById(9);
        // second call served from cache — only one HTTP round-trip
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldRegisterSchemaAndReturnId() throws Exception {
        HttpResponse<String> resp = response(200, "{\"id\":100007}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        int id = client.register("orders-value", AVRO_SCHEMA);
        assertThat(id, is(100007));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString(),
            is("http://registry:8081/subjects/orders-value/versions"));
        assertThat(captor.getValue().method(), is("POST"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldUrlEncodeSubjectContainingSlash() throws Exception {
        HttpResponse<String> resp = response(200, "{\"id\":13}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        int id = client.register("acme/orders-value", AVRO_SCHEMA);
        assertThat(id, is(13));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        // the '/' in the subject must be percent-encoded so it stays a single path segment
        assertThat(captor.getValue().uri().toString(),
            is("http://registry:8081/subjects/acme%2Forders-value/versions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldCacheRegistration() throws Exception {
        HttpResponse<String> resp = response(200, "{\"id\":42}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        client.register("orders-value", AVRO_SCHEMA);
        client.register("orders-value", AVRO_SCHEMA);
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldThrowOnNon200GettingSchema() throws Exception {
        HttpResponse<String> resp = response(404, "{\"error_code\":40403}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.getSchemaById(999));
        assertThat(ex.getMessage(), containsString("404"));
    }

    @Test
    public void shouldRejectBlankBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> new SchemaRegistryClient("  "));
    }

    private static String quote(String s) {
        // JSON-encode a string value (escape quotes) for embedding as the "schema" field
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
