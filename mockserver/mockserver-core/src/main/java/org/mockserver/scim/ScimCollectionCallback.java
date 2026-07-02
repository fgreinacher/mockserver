package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scim.ScimResourceStore.Provider;

import java.util.ArrayList;
import java.util.List;

import static org.mockserver.scim.ScimCallbackSupport.*;

/**
 * Serves the SCIM collection endpoints: {@code GET <basePath>/Users|Groups} (list with optional
 * filter + pagination) and {@code POST <basePath>/Users|Groups} (create).
 */
public class ScimCollectionCallback extends ScimResourceCallbackBase {

    @Override
    public HttpResponse handle(HttpRequest request) {
        Provider provider = provider(request);
        if (provider == null) {
            return scimError(404, "no SCIM provider registered for this path", null);
        }
        HttpResponse unauthorized = bearerGate(request, provider);
        if (unauthorized != null) {
            return unauthorized;
        }

        ScimShaper.ResourceType type = resourceType(request, provider);
        String method = request.getMethod().getValue();
        if ("POST".equalsIgnoreCase(method)) {
            return create(request, provider, type);
        }
        return list(request, provider, type);
    }

    private HttpResponse list(HttpRequest request, Provider provider, ScimShaper.ResourceType type) {
        List<ObjectNode> stored = storeFor(provider, type).getAll();

        // filter
        String filterExpression = request.getFirstQueryStringParameter("filter");
        if (filterExpression != null && !filterExpression.isEmpty()) {
            if (!provider.isEnforceFilter()) {
                return scimError(501, "filtering is not supported", null);
            }
            try {
                ScimFilter filter = ScimFilter.parse(filterExpression);
                if (filter != null) {
                    stored = filter.apply(stored);
                }
            } catch (IllegalArgumentException e) {
                return scimError(400, e.getMessage(), "invalidFilter");
            }
        }

        // sorting (SCIM RFC 7644 §3.4.2.3: sortBy attribute path + ascending/descending sortOrder)
        String sortBy = request.getFirstQueryStringParameter("sortBy");
        String sortOrder = request.getFirstQueryStringParameter("sortOrder");
        try {
            ScimSort sort = ScimSort.parse(sortBy, sortOrder);
            if (sort != null) {
                stored = sort.apply(stored);
            }
        } catch (IllegalArgumentException e) {
            return scimError(400, e.getMessage(), "invalidValue");
        }

        int totalResults = stored.size();

        // pagination (1-based startIndex, count = page size)
        int startIndex = parsePositiveInt(request.getFirstQueryStringParameter("startIndex"), 1);
        if (startIndex < 1) {
            startIndex = 1;
        }
        Integer count = parseNullableInt(request.getFirstQueryStringParameter("count"));

        List<ObjectNode> page = new ArrayList<>();
        int from = startIndex - 1;
        for (int i = from; i < stored.size(); i++) {
            if (count != null && page.size() >= count) {
                break;
            }
            page.add(SHAPER.shapeResource(stored.get(i), type, publicBaseUrl(request), provider.getBasePath(), false));
        }

        return json(200, SHAPER.listResponse(page, totalResults, startIndex));
    }

    private HttpResponse create(HttpRequest request, Provider provider, ScimShaper.ResourceType type) {
        ObjectNode payload = parseObject(request.getBodyAsString());
        if (payload == null) {
            return scimError(400, "request body must be a JSON object", "invalidSyntax");
        }

        String requiredAttribute = type == ScimShaper.ResourceType.USER ? "userName" : "displayName";
        if (!payload.hasNonNull(requiredAttribute) || payload.get(requiredAttribute).asText().isEmpty()) {
            return scimError(400, requiredAttribute + " is required", "invalidValue");
        }

        // do not let the client dictate the server-assigned id
        payload.remove("id");
        // persist the creation timestamp so it survives subsequent reads and PUT replacements
        // (the shaper preserves an existing meta.created but regenerates the rest of meta per read)
        ObjectNode meta = payload.has("meta") && payload.get("meta").isObject()
            ? (ObjectNode) payload.get("meta")
            : payload.putObject("meta");
        meta.put("created", java.time.Instant.now().toString());
        ObjectNode created = storeFor(provider, type).create(payload);
        if (created == null) {
            return scimError(500, "resource store is full", null);
        }

        ObjectNode shaped = SHAPER.shapeResource(created, type, publicBaseUrl(request), provider.getBasePath(), true);
        String location = shaped.has("meta") && shaped.get("meta").has("location")
            ? shaped.get("meta").get("location").asText()
            : null;
        HttpResponse response = json(201, shaped);
        if (location != null) {
            response.withHeader("Location", location);
        }
        return response;
    }

    private static int parsePositiveInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Integer parseNullableInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? 0 : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
