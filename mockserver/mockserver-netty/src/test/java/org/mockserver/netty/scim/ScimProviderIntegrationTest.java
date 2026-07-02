package org.mockserver.netty.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.scim.ScimProviderConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;

/**
 * End-to-end integration test for the mock SCIM 2.0 provider. Starts a real server, registers a
 * provider through {@link org.mockserver.client.MockServerClient#mockScimProvider}, and drives the
 * generated endpoints over real HTTP, asserting the SCIM wire shapes.
 */
public class ScimProviderIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientAndServer mockServer;
    private HttpClient httpClient;
    private String baseUrl;

    @Before
    public void startServer() {
        mockServer = startClientAndServer();
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + mockServer.getPort();
    }

    @After
    public void stopServer() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path));
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        if (body != null) {
            builder.header("content-type", "application/scim+json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return send(method, path, body, null);
    }

    @Test
    public void fullUserCrudRoundTrip() throws Exception {
        mockServer.mockScimProvider();

        // CREATE
        HttpResponse<String> created = send("POST", "/scim/v2/Users",
            "{\"userName\":\"bjensen@example.com\",\"active\":true}");
        assertThat(created.statusCode(), is(201));
        assertThat(created.headers().firstValue("content-type").orElse(""), containsString("application/scim+json"));
        JsonNode createdBody = MAPPER.readTree(created.body());
        String id = createdBody.get("id").asText();
        assertThat(id.isEmpty(), is(false));
        assertThat(createdBody.get("schemas").get(0).asText(), is("urn:ietf:params:scim:schemas:core:2.0:User"));
        assertThat(createdBody.get("meta").get("resourceType").asText(), is("User"));
        assertThat(createdBody.get("meta").get("created"), notNullValue());
        String location = created.headers().firstValue("Location").orElse("");
        assertThat(location, containsString("/scim/v2/Users/" + id));

        // GET by id
        HttpResponse<String> got = send("GET", "/scim/v2/Users/" + id, null);
        assertThat(got.statusCode(), is(200));
        assertThat(MAPPER.readTree(got.body()).get("userName").asText(), is("bjensen@example.com"));

        // PUT replace
        HttpResponse<String> replaced = send("PUT", "/scim/v2/Users/" + id,
            "{\"userName\":\"bjensen@example.com\",\"active\":false}");
        assertThat(replaced.statusCode(), is(200));
        JsonNode replacedBody = MAPPER.readTree(replaced.body());
        assertThat(replacedBody.get("active").asBoolean(), is(false));
        assertThat(replacedBody.get("id").asText(), is(id));
        // created preserved through PUT
        assertThat(replacedBody.get("meta").get("created").asText(),
            is(createdBody.get("meta").get("created").asText()));

        // DELETE
        HttpResponse<String> deleted = send("DELETE", "/scim/v2/Users/" + id, null);
        assertThat(deleted.statusCode(), is(204));

        // GET -> 404 error envelope
        HttpResponse<String> missing = send("GET", "/scim/v2/Users/" + id, null);
        assertThat(missing.statusCode(), is(404));
        JsonNode errorBody = MAPPER.readTree(missing.body());
        assertThat(errorBody.get("schemas").get(0).asText(), is("urn:ietf:params:scim:api:messages:2.0:Error"));
        assertThat(errorBody.get("status").asText(), is("404"));
    }

    @Test
    public void listReturnsListResponseEnvelope() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"a\"}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"b\"}");

        HttpResponse<String> list = send("GET", "/scim/v2/Users", null);
        assertThat(list.statusCode(), is(200));
        JsonNode body = MAPPER.readTree(list.body());
        assertThat(body.get("schemas").get(0).asText(), is("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
        assertThat(body.get("totalResults").asInt(), is(2));
        assertThat(body.get("Resources").size(), is(2));
    }

    @Test
    public void filterEqReturnsExactlyOne() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"alice\"}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"bob\"}");

        HttpResponse<String> filtered = send("GET",
            "/scim/v2/Users?filter=" + urlEncode("userName eq \"alice\""), null);
        assertThat(filtered.statusCode(), is(200));
        JsonNode body = MAPPER.readTree(filtered.body());
        assertThat(body.get("totalResults").asInt(), is(1));
        assertThat(body.get("Resources").get(0).get("userName").asText(), is("alice"));
    }

    @Test
    public void sortByAscendingOrdersResources() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"charlie\"}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"alice\"}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"bob\"}");

        HttpResponse<String> sorted = send("GET", "/scim/v2/Users?sortBy=userName", null);
        assertThat(sorted.statusCode(), is(200));
        JsonNode resources = MAPPER.readTree(sorted.body()).get("Resources");
        assertThat(resources.get(0).get("userName").asText(), is("alice"));
        assertThat(resources.get(1).get("userName").asText(), is("bob"));
        assertThat(resources.get(2).get("userName").asText(), is("charlie"));
    }

    @Test
    public void sortByDescendingReversesOrder() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"charlie\"}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"alice\"}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"bob\"}");

        HttpResponse<String> sorted = send("GET",
            "/scim/v2/Users?sortBy=userName&sortOrder=descending", null);
        assertThat(sorted.statusCode(), is(200));
        JsonNode resources = MAPPER.readTree(sorted.body()).get("Resources");
        assertThat(resources.get(0).get("userName").asText(), is("charlie"));
        assertThat(resources.get(1).get("userName").asText(), is("bob"));
        assertThat(resources.get(2).get("userName").asText(), is("alice"));
    }

    @Test
    public void sortByNestedAttributePath() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"c\",\"name\":{\"familyName\":\"Brown\"}}");
        send("POST", "/scim/v2/Users", "{\"userName\":\"a\",\"name\":{\"familyName\":\"Anderson\"}}");

        HttpResponse<String> sorted = send("GET",
            "/scim/v2/Users?sortBy=" + urlEncode("name.familyName"), null);
        assertThat(sorted.statusCode(), is(200));
        JsonNode resources = MAPPER.readTree(sorted.body()).get("Resources");
        assertThat(resources.get(0).get("userName").asText(), is("a"));
        assertThat(resources.get(1).get("userName").asText(), is("c"));
    }

    @Test
    public void invalidSortByReturns400() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"a\"}");

        HttpResponse<String> invalid = send("GET",
            "/scim/v2/Users?sortBy=" + urlEncode("bad path!"), null);
        assertThat(invalid.statusCode(), is(400));
        JsonNode body = MAPPER.readTree(invalid.body());
        assertThat(body.get("scimType").asText(), is("invalidValue"));
    }

    @Test
    public void invalidSortOrderReturns400() throws Exception {
        mockServer.mockScimProvider();
        send("POST", "/scim/v2/Users", "{\"userName\":\"a\"}");

        HttpResponse<String> invalid = send("GET",
            "/scim/v2/Users?sortBy=userName&sortOrder=sideways", null);
        assertThat(invalid.statusCode(), is(400));
        assertThat(MAPPER.readTree(invalid.body()).get("scimType").asText(), is("invalidValue"));
    }

    @Test
    public void patchReplaceAddRemovePersists() throws Exception {
        mockServer.mockScimProvider();
        HttpResponse<String> created = send("POST", "/scim/v2/Users",
            "{\"userName\":\"p\",\"active\":true,\"emails\":[{\"value\":\"a@x.com\"}]}");
        String id = MAPPER.readTree(created.body()).get("id").asText();

        // replace
        send("PATCH", "/scim/v2/Users/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]," +
                "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}");
        // add to array
        send("PATCH", "/scim/v2/Users/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]," +
                "\"Operations\":[{\"op\":\"add\",\"path\":\"emails\",\"value\":{\"value\":\"b@x.com\"}}]}");
        // remove
        send("PATCH", "/scim/v2/Users/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]," +
                "\"Operations\":[{\"op\":\"remove\",\"path\":\"displayName\"}]}");

        JsonNode got = MAPPER.readTree(send("GET", "/scim/v2/Users/" + id, null).body());
        assertThat(got.get("active").asBoolean(), is(false));
        assertThat(got.get("emails").size(), is(2));
    }

    @Test
    public void groupMemberPatchAddAndRemove() throws Exception {
        mockServer.mockScimProvider();
        HttpResponse<String> created = send("POST", "/scim/v2/Groups",
            "{\"displayName\":\"Admins\",\"members\":[]}");
        assertThat(created.statusCode(), is(201));
        String id = MAPPER.readTree(created.body()).get("id").asText();

        send("PATCH", "/scim/v2/Groups/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]," +
                "\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":{\"value\":\"user-1\"}}]}");
        JsonNode afterAdd = MAPPER.readTree(send("GET", "/scim/v2/Groups/" + id, null).body());
        assertThat(afterAdd.get("members").size(), is(1));

        send("PATCH", "/scim/v2/Groups/" + id,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]," +
                "\"Operations\":[{\"op\":\"remove\",\"path\":\"members\"}]}");
        JsonNode afterRemove = MAPPER.readTree(send("GET", "/scim/v2/Groups/" + id, null).body());
        assertThat(afterRemove.has("members"), is(false));
    }

    @Test
    public void errorEnvelopes() throws Exception {
        mockServer.mockScimProvider();

        // unknown id
        HttpResponse<String> unknown = send("GET", "/scim/v2/Users/does-not-exist", null);
        assertThat(unknown.statusCode(), is(404));

        // missing userName
        HttpResponse<String> missingUserName = send("POST", "/scim/v2/Users", "{\"active\":true}");
        assertThat(missingUserName.statusCode(), is(400));
        assertThat(MAPPER.readTree(missingUserName.body()).get("scimType").asText(), is("invalidValue"));

        // malformed PatchOp
        HttpResponse<String> created = send("POST", "/scim/v2/Users", "{\"userName\":\"x\"}");
        String id = MAPPER.readTree(created.body()).get("id").asText();
        HttpResponse<String> malformed = send("PATCH", "/scim/v2/Users/" + id, "{\"schemas\":[]}");
        assertThat(malformed.statusCode(), is(400));
        assertThat(MAPPER.readTree(malformed.body()).get("scimType").asText(), is("invalidSyntax"));
    }

    @Test
    public void discoveryShapes() throws Exception {
        mockServer.mockScimProvider();

        JsonNode spc = MAPPER.readTree(send("GET", "/scim/v2/ServiceProviderConfig", null).body());
        assertThat(spc.get("patch").get("supported").asBoolean(), is(true));
        assertThat(spc.get("filter").get("supported").asBoolean(), is(true));
        assertThat(spc.get("bulk").get("supported").asBoolean(), is(false));
        assertThat(spc.get("authenticationSchemes").get(0).get("type").asText(), is("oauthbearertoken"));

        JsonNode resourceTypes = MAPPER.readTree(send("GET", "/scim/v2/ResourceTypes", null).body());
        assertThat(resourceTypes.get("schemas").get(0).asText(), is("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
        assertThat(resourceTypes.get("totalResults").asInt(), is(2));

        JsonNode schemas = MAPPER.readTree(send("GET", "/scim/v2/Schemas", null).body());
        assertThat(schemas.get("Resources").size(), is(2));
    }

    @Test
    public void bearerGateRejectsWithoutTokenAndAcceptsWithToken() throws Exception {
        ScimProviderConfiguration config = new ScimProviderConfiguration()
            .setRequireBearerToken(true)
            .setExpectedBearerToken("secret-token");
        // Fix 3 (WRITE_ONLY round-trip): expectedBearerToken is WRITE_ONLY so the server never echoes it
        // back; the typed client re-injects it on the outbound PUT so the value-pinned gate still works.
        org.mockserver.mock.Expectation[] returned = mockServer.mockScimProvider(config);

        // (a) the server never echoes the secret token back out in the returned expectations
        String returnedJson = MAPPER.writeValueAsString(returned);
        assertThat("expected bearer token must not be echoed back", returnedJson.contains("secret-token"), is(false));

        // no token -> 401 + WWW-Authenticate
        HttpResponse<String> noToken = send("GET", "/scim/v2/Users", null);
        assertThat(noToken.statusCode(), is(401));
        assertThat(noToken.headers().firstValue("WWW-Authenticate").orElse(""), containsString("Bearer"));
        assertThat(MAPPER.readTree(noToken.body()).get("schemas").get(0).asText(),
            is("urn:ietf:params:scim:api:messages:2.0:Error"));

        // wrong token -> 401
        HttpResponse<String> wrongToken = send("GET", "/scim/v2/Users", null, "wrong");
        assertThat(wrongToken.statusCode(), is(401));

        // correct token -> 200
        HttpResponse<String> withToken = send("GET", "/scim/v2/Users", null, "secret-token");
        assertThat(withToken.statusCode(), is(200));

        // discovery stays open even with bearer required
        HttpResponse<String> discovery = send("GET", "/scim/v2/ServiceProviderConfig", null);
        assertThat(discovery.statusCode(), is(200));
    }

    @Test
    public void customBasePathAndInitialUsers() throws Exception {
        List<ObjectNode> initial = new ArrayList<>();
        ObjectNode seed = MAPPER.createObjectNode();
        seed.put("id", "seed-1");
        seed.put("userName", "seeded");
        initial.add(seed);
        ScimProviderConfiguration config = new ScimProviderConfiguration()
            .setBasePath("/api/scim/v2")
            .setInitialUsers(initial);
        mockServer.mockScimProvider(config);

        HttpResponse<String> got = send("GET", "/api/scim/v2/Users/seed-1", null);
        assertThat(got.statusCode(), is(200));
        JsonNode body = MAPPER.readTree(got.body());
        assertThat(body.get("userName").asText(), is("seeded"));
        assertThat(body.get("meta").get("location").asText(), containsString("/api/scim/v2/Users/seed-1"));
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
