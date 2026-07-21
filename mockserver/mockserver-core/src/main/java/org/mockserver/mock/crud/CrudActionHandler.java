package org.mockserver.mock.crud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.List;

import static org.mockserver.model.HttpResponse.response;

public class CrudActionHandler {

    private final CrudDataStore store;
    private final String basePath;
    private final ObjectMapper objectMapper;

    public CrudActionHandler(CrudDataStore store, String basePath) {
        this.store = store;
        this.basePath = normalizeBasePath(basePath);
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
    }

    public HttpResponse handleList(HttpRequest request) {
        final CrudListQuery query;
        try {
            query = CrudListQuery.parse(request);
        } catch (IllegalArgumentException e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":" + objectMapper.getNodeFactory().textNode(e.getMessage()) + "}", MediaType.JSON_UTF_8);
        }
        try {
            List<ObjectNode> filteredAndSorted = query.filterAndSort(store.getAll());
            int total = filteredAndSorted.size();
            List<ObjectNode> page = query.paginate(filteredAndSorted);
            HttpResponse response = response()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(page), MediaType.JSON_UTF_8);
            // Surface pagination metadata via headers only when a list query was supplied,
            // so a plain list request keeps its exact legacy response (body + no extra headers).
            if (query.isActive()) {
                response.withHeader("X-Total-Count", String.valueOf(total));
                response.withHeader("X-Page", String.valueOf(query.getPage()));
                if (query.getSize() != null) {
                    response.withHeader("X-Page-Size", String.valueOf(query.getSize()));
                }
            }
            return response;
        } catch (Exception e) {
            return response()
                .withStatusCode(500)
                .withBody("{\"error\":\"internal server error\"}", MediaType.JSON_UTF_8);
        }
    }

    public HttpResponse handleGetById(HttpRequest request) {
        try {
            String id = extractIdFromPath(request);
            if (id == null) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"missing id\"}", MediaType.JSON_UTF_8);
            }
            ObjectNode item = store.getById(id);
            if (item == null) {
                return response()
                    .withStatusCode(404)
                    .withBody("{\"error\":\"not found\"}", MediaType.JSON_UTF_8);
            }
            return response()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(item), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(500)
                .withBody("{\"error\":\"internal server error\"}", MediaType.JSON_UTF_8);
        }
    }

    public HttpResponse handleCreate(HttpRequest request) {
        try {
            String body = request.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"request body is required\"}", MediaType.JSON_UTF_8);
            }
            ObjectNode item = (ObjectNode) objectMapper.readTree(body);
            ObjectNode created = store.create(item);
            if (created == null) {
                return response()
                    .withStatusCode(507)
                    .withBody("{\"error\":\"data store is full (max " + store.getMaxItems() + " items)\"}", MediaType.JSON_UTF_8);
            }
            return response()
                .withStatusCode(201)
                .withBody(objectMapper.writeValueAsString(created), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"invalid request body\"}", MediaType.JSON_UTF_8);
        }
    }

    public HttpResponse handleUpdate(HttpRequest request) {
        try {
            String id = extractIdFromPath(request);
            if (id == null) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"missing id\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"request body is required\"}", MediaType.JSON_UTF_8);
            }
            ObjectNode item = (ObjectNode) objectMapper.readTree(body);
            ObjectNode updated = store.update(id, item);
            if (updated == null) {
                return response()
                    .withStatusCode(404)
                    .withBody("{\"error\":\"not found\"}", MediaType.JSON_UTF_8);
            }
            return response()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(updated), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"invalid request body\"}", MediaType.JSON_UTF_8);
        }
    }

    public HttpResponse handlePatch(HttpRequest request) {
        try {
            String id = extractIdFromPath(request);
            if (id == null) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"missing id\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isEmpty()) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"request body is required\"}", MediaType.JSON_UTF_8);
            }
            ObjectNode partial = (ObjectNode) objectMapper.readTree(body);
            ObjectNode patched = store.patch(id, partial);
            if (patched == null) {
                return response()
                    .withStatusCode(404)
                    .withBody("{\"error\":\"not found\"}", MediaType.JSON_UTF_8);
            }
            return response()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(patched), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"invalid request body\"}", MediaType.JSON_UTF_8);
        }
    }

    public HttpResponse handleDelete(HttpRequest request) {
        try {
            String id = extractIdFromPath(request);
            if (id == null) {
                return response()
                    .withStatusCode(400)
                    .withBody("{\"error\":\"missing id\"}", MediaType.JSON_UTF_8);
            }
            boolean deleted = store.delete(id);
            if (!deleted) {
                return response()
                    .withStatusCode(404)
                    .withBody("{\"error\":\"not found\"}", MediaType.JSON_UTF_8);
            }
            return response()
                .withStatusCode(204);
        } catch (Exception e) {
            return response()
                .withStatusCode(500)
                .withBody("{\"error\":\"internal server error\"}", MediaType.JSON_UTF_8);
        }
    }

    public String getBasePath() {
        return basePath;
    }

    String extractIdFromPath(HttpRequest request) {
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        if (path == null) {
            return null;
        }
        String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String prefix = basePath;
        if (!normalizedPath.startsWith(prefix) || normalizedPath.length() <= prefix.length() + 1) {
            return null;
        }
        String remainder = normalizedPath.substring(prefix.length() + 1);
        if (remainder.contains("/")) {
            return null;
        }
        return remainder;
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null) {
            return "";
        }
        return basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
    }
}
