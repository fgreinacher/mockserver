package org.mockserver.scim;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;
import org.mockserver.model.HttpClassCallback;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Generates MockServer {@link Expectation}s that serve a complete SCIM 2.0 provider: CRUD over
 * {@code Users} and {@code Groups} plus the SCIM discovery endpoints
 * ({@code /ServiceProviderConfig}, {@code /ResourceTypes}, {@code /Schemas}).
 *
 * <p>Mirrors {@link org.mockserver.oidc.OidcProviderGenerator}: dynamic endpoints are served by
 * {@link HttpClassCallback}s that resolve their state from {@link ScimResourceStore}, while the
 * static discovery documents are served by plain {@code response()} expectations. Every expectation
 * carries a stable {@code withId(...)} keyed by base path, so re-running {@code PUT /mockserver/scim}
 * upserts rather than duplicates.
 */
public class ScimProviderGenerator {

    private final ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);

    /**
     * Generates SCIM provider expectations from the given configuration and registers the backing
     * Users/Groups stores in {@link ScimResourceStore}.
     *
     * @param config the provider configuration (must not be null)
     * @return the generated expectations (never empty)
     */
    public List<Expectation> generate(ScimProviderConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("SCIM provider configuration is required");
        }
        String basePath = normalizeBasePath(config.getBasePath());

        IdStrategy idStrategy = config.getIdStrategy() == null ? IdStrategy.UUID : config.getIdStrategy();
        CrudDataStore users = new CrudDataStore("id", idStrategy, config.getInitialUsers());
        CrudDataStore groups = new CrudDataStore("id", idStrategy, config.getInitialGroups());

        ScimResourceStore.getInstance().registerProvider(new ScimResourceStore.Provider(
            basePath, users, groups,
            config.isRequireBearerToken(), config.getExpectedBearerToken(),
            config.isEnforceFilter(), config.isEnforcePatch()
        ));

        List<Expectation> expectations = new ArrayList<>();

        String idPrefix = "scim" + basePath.replace('/', '.');

        // Users + Groups CRUD
        for (ScimShaper.ResourceType type : ScimShaper.ResourceType.values()) {
            String segment = type.getPathSegment();
            String collectionPath = basePath + "/" + segment;
            String resourcePath = collectionPath + "/{id}";

            expectations.add(new Expectation(
                request().withMethod("GET").withPath(collectionPath))
                .withId(idPrefix + "." + segment + ".list")
                .thenRespond(HttpClassCallback.callback(ScimCollectionCallback.class.getName())));

            expectations.add(new Expectation(
                request().withMethod("POST").withPath(collectionPath))
                .withId(idPrefix + "." + segment + ".create")
                .thenRespond(HttpClassCallback.callback(ScimCollectionCallback.class.getName())));

            for (String method : new String[]{"GET", "PUT", "PATCH", "DELETE"}) {
                expectations.add(new Expectation(
                    request().withMethod(method).withPath(resourcePath).withPathParameter("id", ".*"))
                    .withId(idPrefix + "." + segment + "." + method.toLowerCase())
                    .thenRespond(HttpClassCallback.callback(ScimResourceCallback.class.getName())));
            }
        }

        // Discovery — static documents
        expectations.add(staticExpectation(
            "GET", basePath + "/ServiceProviderConfig",
            idPrefix + ".serviceProviderConfig",
            serviceProviderConfig(config)));
        expectations.add(staticExpectation(
            "GET", basePath + "/ResourceTypes",
            idPrefix + ".resourceTypes",
            resourceTypes(basePath)));
        expectations.add(staticExpectation(
            "GET", basePath + "/Schemas",
            idPrefix + ".schemas",
            schemas()));

        return expectations;
    }

    private Expectation staticExpectation(String method, String path, String id, ObjectNode body) {
        return new Expectation(request().withMethod(method).withPath(path))
            .withId(id)
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", ScimShaper.CONTENT_TYPE)
                .withBody(serialize(body)));
    }

    private ObjectNode serviceProviderConfig(ScimProviderConfiguration config) {
        ObjectNode root = ObjectMapperFactory.createObjectMapper().createObjectNode();
        root.putArray("schemas").add(ScimShaper.SERVICE_PROVIDER_CONFIG_SCHEMA);
        root.put("documentationUri", "https://www.mock-server.com/mock_server/mocking_scim.html");
        root.putObject("patch").put("supported", config.isEnforcePatch());
        root.putObject("bulk").put("supported", false).put("maxOperations", 0).put("maxPayloadSize", 0);
        root.putObject("filter").put("supported", config.isEnforceFilter()).put("maxResults", CrudDataStore.DEFAULT_MAX_ITEMS);
        root.putObject("changePassword").put("supported", false);
        root.putObject("sort").put("supported", true);
        root.putObject("etag").put("supported", false);
        ArrayNode authSchemes = root.putArray("authenticationSchemes");
        ObjectNode bearer = authSchemes.addObject();
        bearer.put("type", "oauthbearertoken");
        bearer.put("name", "OAuth Bearer Token");
        bearer.put("description", "Authentication scheme using the OAuth Bearer Token Standard");
        bearer.put("primary", true);
        return root;
    }

    private ObjectNode resourceTypes(String basePath) {
        ObjectNode user = resourceType("User", "User", basePath + "/Users", ScimShaper.USER_SCHEMA);
        ObjectNode group = resourceType("Group", "Group", basePath + "/Groups", ScimShaper.GROUP_SCHEMA);
        List<ObjectNode> resources = new ArrayList<>();
        resources.add(user);
        resources.add(group);
        return new ScimShaper().listResponse(resources, 2, 1);
    }

    private ObjectNode resourceType(String id, String name, String endpoint, String schema) {
        ObjectNode node = ObjectMapperFactory.createObjectMapper().createObjectNode();
        node.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:ResourceType");
        node.put("id", id);
        node.put("name", name);
        node.put("endpoint", endpoint);
        node.put("schema", schema);
        return node;
    }

    private ObjectNode schemas() {
        ObjectNode userSchema = schema(ScimShaper.USER_SCHEMA, "User", "User Account",
            new String[]{"userName", "externalId"});
        ObjectNode groupSchema = schema(ScimShaper.GROUP_SCHEMA, "Group", "Group",
            new String[]{"displayName"});
        List<ObjectNode> resources = new ArrayList<>();
        resources.add(userSchema);
        resources.add(groupSchema);
        return new ScimShaper().listResponse(resources, 2, 1);
    }

    private ObjectNode schema(String id, String name, String description, String[] attributeNames) {
        ObjectNode node = ObjectMapperFactory.createObjectMapper().createObjectNode();
        node.put("id", id);
        node.put("name", name);
        node.put("description", description);
        ArrayNode attributes = node.putArray("attributes");
        for (String attributeName : attributeNames) {
            ObjectNode attribute = attributes.addObject();
            attribute.put("name", attributeName);
            attribute.put("type", "string");
            attribute.put("multiValued", false);
            attribute.put("required", "userName".equals(attributeName) || "displayName".equals(attributeName));
            attribute.put("caseExact", false);
            attribute.put("mutability", "readWrite");
            attribute.put("returned", "default");
            attribute.put("uniqueness", "none");
        }
        return node;
    }

    /**
     * Validates and normalizes the base path: must be non-blank, start with {@code /}, and must not
     * overlap the {@code /mockserver} control-plane namespace. A trailing slash is stripped.
     */
    static String normalizeBasePath(String basePath) {
        String value = basePath == null ? "" : basePath.trim();
        if (value.isEmpty()) {
            value = "/scim/v2";
        }
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("SCIM basePath must start with '/': " + basePath);
        }
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.equals("/mockserver") || value.startsWith("/mockserver/")) {
            throw new IllegalArgumentException("SCIM basePath must not overlap the /mockserver control plane: " + basePath);
        }
        return value;
    }

    private String serialize(Object value) {
        try {
            return objectWriter.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize SCIM discovery document to JSON", e);
        }
    }
}
