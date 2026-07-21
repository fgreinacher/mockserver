package org.mockserver.mock.crud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class CrudActionHandlerTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private CrudDataStore store;
    private CrudActionHandler handler;

    @Before
    public void setUp() {
        ObjectNode alice = objectMapper.createObjectNode().put("id", 1).put("name", "Alice").put("email", "alice@example.com");
        ObjectNode bob = objectMapper.createObjectNode().put("id", 2).put("name", "Bob").put("email", "bob@example.com");
        store = new CrudDataStore("id", IdStrategy.AUTO_INCREMENT, Arrays.asList(alice, bob));
        handler = new CrudActionHandler(store, "/api/users");
    }

    // GET list

    @Test
    public void shouldListAllItems() throws Exception {
        // given
        HttpRequest request = request("/api/users").withMethod("GET");

        // when
        HttpResponse response = handler.handleList(request);

        // then
        assertThat(response.getStatusCode(), is(200));
        ArrayNode body = (ArrayNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.size(), is(2));
        assertThat(body.get(0).get("name").asText(), is("Alice"));
        assertThat(body.get(1).get("name").asText(), is("Bob"));
    }

    @Test
    public void shouldListEmptyStore() throws Exception {
        // given
        CrudDataStore emptyStore = new CrudDataStore("id", IdStrategy.AUTO_INCREMENT);
        CrudActionHandler emptyHandler = new CrudActionHandler(emptyStore, "/api/users");
        HttpRequest request = request("/api/users").withMethod("GET");

        // when
        HttpResponse response = emptyHandler.handleList(request);

        // then
        assertThat(response.getStatusCode(), is(200));
        ArrayNode body = (ArrayNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.size(), is(0));
    }

    // GET list — pagination / sorting / filtering

    private CrudActionHandler handlerWith(ObjectNode... items) {
        CrudDataStore s = new CrudDataStore("id", IdStrategy.AUTO_INCREMENT, Arrays.asList(items));
        return new CrudActionHandler(s, "/api/users");
    }

    private ObjectNode person(int id, String name) {
        return objectMapper.createObjectNode().put("id", id).put("name", name);
    }

    @Test
    public void shouldReturnPlainListUnchangedWhenNoQueryParams() throws Exception {
        HttpResponse response = handler.handleList(request("/api/users").withMethod("GET"));

        assertThat(response.getStatusCode(), is(200));
        // no pagination headers on a plain list request (legacy behaviour preserved)
        assertThat(response.getFirstHeader("X-Total-Count"), is(""));
        ArrayNode body = (ArrayNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.size(), is(2));
    }

    @Test
    public void shouldSortByFieldAscendingAndDescending() throws Exception {
        CrudActionHandler h = handlerWith(person(1, "Charlie"), person(2, "Alice"), person(3, "Bob"));

        ArrayNode asc = (ArrayNode) objectMapper.readTree(h.handleList(
            request("/api/users").withMethod("GET").withQueryStringParameter("sortBy", "name")).getBodyAsString());
        assertThat(asc.get(0).get("name").asText(), is("Alice"));
        assertThat(asc.get(1).get("name").asText(), is("Bob"));
        assertThat(asc.get(2).get("name").asText(), is("Charlie"));

        ArrayNode desc = (ArrayNode) objectMapper.readTree(h.handleList(
            request("/api/users").withMethod("GET")
                .withQueryStringParameter("sortBy", "name")
                .withQueryStringParameter("sortOrder", "desc")).getBodyAsString());
        assertThat(desc.get(0).get("name").asText(), is("Charlie"));
        assertThat(desc.get(2).get("name").asText(), is("Alice"));
    }

    @Test
    public void shouldSortMissingSortValuesLast() throws Exception {
        ObjectNode noName = objectMapper.createObjectNode().put("id", 9);
        CrudActionHandler h = handlerWith(person(1, "Bob"), noName, person(3, "Alice"));

        ArrayNode asc = (ArrayNode) objectMapper.readTree(h.handleList(
            request("/api/users").withMethod("GET").withQueryStringParameter("sortBy", "name")).getBodyAsString());
        assertThat(asc.get(0).get("name").asText(), is("Alice"));
        assertThat(asc.get(1).get("name").asText(), is("Bob"));
        // the item missing the sort field is last regardless of order
        assertThat(asc.get(2).get("id").asInt(), is(9));
    }

    @Test
    public void shouldPaginateWithPageAndSizeAcrossBoundaries() throws Exception {
        CrudActionHandler h = handlerWith(person(1, "A"), person(2, "B"), person(3, "C"), person(4, "D"), person(5, "E"));

        HttpResponse firstPage = h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "0").withQueryStringParameter("size", "2"));
        ArrayNode p0 = (ArrayNode) objectMapper.readTree(firstPage.getBodyAsString());
        assertThat(p0.size(), is(2));
        assertThat(p0.get(0).get("name").asText(), is("A"));
        assertThat(p0.get(1).get("name").asText(), is("B"));
        // total-count header reflects the full (filtered) set, not the page
        assertThat(firstPage.getFirstHeader("X-Total-Count"), is("5"));
        assertThat(firstPage.getFirstHeader("X-Page"), is("0"));
        assertThat(firstPage.getFirstHeader("X-Page-Size"), is("2"));

        ArrayNode p1 = (ArrayNode) objectMapper.readTree(h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "1").withQueryStringParameter("size", "2")).getBodyAsString());
        assertThat(p1.size(), is(2));
        assertThat(p1.get(0).get("name").asText(), is("C"));

        // last partial page
        ArrayNode p2 = (ArrayNode) objectMapper.readTree(h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "2").withQueryStringParameter("size", "2")).getBodyAsString());
        assertThat(p2.size(), is(1));
        assertThat(p2.get(0).get("name").asText(), is("E"));

        // page beyond the end is an empty list, not an error
        ArrayNode p3 = (ArrayNode) objectMapper.readTree(h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "3").withQueryStringParameter("size", "2")).getBodyAsString());
        assertThat(p3.size(), is(0));
    }

    @Test
    public void shouldFilterByField() throws Exception {
        CrudActionHandler h = handlerWith(person(1, "Alice"), person(2, "Bob"), person(3, "alice"));

        ArrayNode filtered = (ArrayNode) objectMapper.readTree(h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("filterField", "name")
            .withQueryStringParameter("filterValue", "alice")).getBodyAsString());
        // case-insensitive equality matches both "Alice" and "alice"
        assertThat(filtered.size(), is(2));
    }

    @Test
    public void shouldCombineFilterSortAndPaginate() throws Exception {
        ObjectNode a = objectMapper.createObjectNode().put("id", 1).put("name", "Zoe").put("team", "red");
        ObjectNode b = objectMapper.createObjectNode().put("id", 2).put("name", "Amy").put("team", "red");
        ObjectNode c = objectMapper.createObjectNode().put("id", 3).put("name", "Max").put("team", "blue");
        ObjectNode d = objectMapper.createObjectNode().put("id", 4).put("name", "Bea").put("team", "red");
        CrudActionHandler h = handlerWith(a, b, c, d);

        HttpResponse response = h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("filterField", "team")
            .withQueryStringParameter("filterValue", "red")
            .withQueryStringParameter("sortBy", "name")
            .withQueryStringParameter("page", "0")
            .withQueryStringParameter("size", "2"));
        ArrayNode body = (ArrayNode) objectMapper.readTree(response.getBodyAsString());
        // red team sorted by name: Amy, Bea, Zoe -> first page of size 2: Amy, Bea
        assertThat(body.size(), is(2));
        assertThat(body.get(0).get("name").asText(), is("Amy"));
        assertThat(body.get(1).get("name").asText(), is("Bea"));
        assertThat(response.getFirstHeader("X-Total-Count"), is("3"));
    }

    @Test
    public void shouldReturn400ForInvalidPage() {
        HttpResponse response = handler.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "abc"));
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("page"));
    }

    @Test
    public void shouldReturn400ForNegativePage() {
        HttpResponse response = handler.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "-1"));
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("page"));
    }

    @Test
    public void shouldReturn400ForInvalidSortOrder() {
        HttpResponse response = handler.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("sortBy", "name")
            .withQueryStringParameter("sortOrder", "sideways"));
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("sortOrder"));
    }

    @Test
    public void shouldReturn400WhenFilterFieldWithoutValue() {
        HttpResponse response = handler.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("filterField", "name"));
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("filterField"));
    }

    @Test
    public void shouldNotOverflowWithLargePageAndSize() throws Exception {
        CrudActionHandler h = handlerWith(person(1, "A"), person(2, "B"), person(3, "C"));
        // page 1 with a huge size must not overflow int math in subList — page beyond the
        // end is an empty 200 response, not a 500
        HttpResponse response = h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "1")
            .withQueryStringParameter("size", String.valueOf(Integer.MAX_VALUE)));
        assertThat(response.getStatusCode(), is(200));
        ArrayNode body = (ArrayNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.size(), is(0));
        assertThat(response.getFirstHeader("X-Total-Count"), is("3"));

        // page 0 with a huge size returns everything
        ArrayNode all = (ArrayNode) objectMapper.readTree(h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("page", "0")
            .withQueryStringParameter("size", String.valueOf(Integer.MAX_VALUE))).getBodyAsString());
        assertThat(all.size(), is(3));
    }

    @Test
    public void shouldReturn400WhenSortOrderWithoutSortBy() {
        HttpResponse response = handler.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("sortOrder", "desc"));
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("sortBy"));
    }

    @Test
    public void shouldTreatNonPositiveSizeAsUnlimited() throws Exception {
        CrudActionHandler h = handlerWith(person(1, "A"), person(2, "B"), person(3, "C"));
        ArrayNode body = (ArrayNode) objectMapper.readTree(h.handleList(request("/api/users").withMethod("GET")
            .withQueryStringParameter("size", "0")).getBodyAsString());
        assertThat(body.size(), is(3));
    }

    // GET by ID

    @Test
    public void shouldGetItemById() throws Exception {
        // given
        HttpRequest request = request("/api/users/1").withMethod("GET");

        // when
        HttpResponse response = handler.handleGetById(request);

        // then
        assertThat(response.getStatusCode(), is(200));
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("name").asText(), is("Alice"));
        assertThat(body.get("email").asText(), is("alice@example.com"));
    }

    @Test
    public void shouldReturn404ForNonExistentItem() {
        // given
        HttpRequest request = request("/api/users/999").withMethod("GET");

        // when
        HttpResponse response = handler.handleGetById(request);

        // then
        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getBodyAsString(), containsString("not found"));
    }

    @Test
    public void shouldReturn400ForMissingIdInGetById() {
        // given
        HttpRequest request = request("/api/users").withMethod("GET");

        // when
        HttpResponse response = handler.handleGetById(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("missing id"));
    }

    // POST create

    @Test
    public void shouldCreateItem() throws Exception {
        // given
        HttpRequest request = request("/api/users")
            .withMethod("POST")
            .withBody("{\"name\":\"Charlie\",\"email\":\"charlie@example.com\"}");

        // when
        HttpResponse response = handler.handleCreate(request);

        // then
        assertThat(response.getStatusCode(), is(201));
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("name").asText(), is("Charlie"));
        assertThat(body.get("email").asText(), is("charlie@example.com"));
        assertThat(body.has("id"), is(true));
        assertThat(body.get("id").asLong(), is(3L));
        assertThat(store.size(), is(3));
    }

    @Test
    public void shouldReturn400ForEmptyBodyOnCreate() {
        // given
        HttpRequest request = request("/api/users").withMethod("POST");

        // when
        HttpResponse response = handler.handleCreate(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("request body is required"));
    }

    @Test
    public void shouldReturn400ForInvalidJsonOnCreate() {
        // given
        HttpRequest request = request("/api/users")
            .withMethod("POST")
            .withBody("not json");

        // when
        HttpResponse response = handler.handleCreate(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid request body"));
    }

    // PUT update

    @Test
    public void shouldUpdateItem() throws Exception {
        // given
        HttpRequest request = request("/api/users/1")
            .withMethod("PUT")
            .withBody("{\"name\":\"Alice Updated\",\"email\":\"alice.updated@example.com\"}");

        // when
        HttpResponse response = handler.handleUpdate(request);

        // then
        assertThat(response.getStatusCode(), is(200));
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("name").asText(), is("Alice Updated"));
        assertThat(body.get("email").asText(), is("alice.updated@example.com"));
        assertThat(body.get("id").asLong(), is(1L));
    }

    @Test
    public void shouldReturn404WhenUpdatingNonExistentItem() {
        // given
        HttpRequest request = request("/api/users/999")
            .withMethod("PUT")
            .withBody("{\"name\":\"Ghost\"}");

        // when
        HttpResponse response = handler.handleUpdate(request);

        // then
        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getBodyAsString(), containsString("not found"));
    }

    @Test
    public void shouldReturn400ForEmptyBodyOnUpdate() {
        // given
        HttpRequest request = request("/api/users/1").withMethod("PUT");

        // when
        HttpResponse response = handler.handleUpdate(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("request body is required"));
    }

    @Test
    public void shouldReturn400ForMissingIdInUpdate() {
        // given
        HttpRequest request = request("/api/users")
            .withMethod("PUT")
            .withBody("{\"name\":\"Alice\"}");

        // when
        HttpResponse response = handler.handleUpdate(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("missing id"));
    }

    // PATCH

    @Test
    public void shouldPatchItem() throws Exception {
        // given
        HttpRequest request = request("/api/users/1")
            .withMethod("PATCH")
            .withBody("{\"email\":\"alice.patched@example.com\"}");

        // when
        HttpResponse response = handler.handlePatch(request);

        // then
        assertThat(response.getStatusCode(), is(200));
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("name").asText(), is("Alice"));
        assertThat(body.get("email").asText(), is("alice.patched@example.com"));
        assertThat(body.get("id").asLong(), is(1L));
    }

    @Test
    public void shouldReturn404WhenPatchingNonExistentItem() {
        // given
        HttpRequest request = request("/api/users/999")
            .withMethod("PATCH")
            .withBody("{\"name\":\"Ghost\"}");

        // when
        HttpResponse response = handler.handlePatch(request);

        // then
        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getBodyAsString(), containsString("not found"));
    }

    @Test
    public void shouldReturn400ForEmptyBodyOnPatch() {
        // given
        HttpRequest request = request("/api/users/1").withMethod("PATCH");

        // when
        HttpResponse response = handler.handlePatch(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("request body is required"));
    }

    @Test
    public void shouldReturn400ForMissingIdInPatch() {
        // given
        HttpRequest request = request("/api/users")
            .withMethod("PATCH")
            .withBody("{\"name\":\"Alice\"}");

        // when
        HttpResponse response = handler.handlePatch(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("missing id"));
    }

    @Test
    public void shouldReturn400ForInvalidJsonOnPatch() {
        // given
        HttpRequest request = request("/api/users/1")
            .withMethod("PATCH")
            .withBody("not json");

        // when
        HttpResponse response = handler.handlePatch(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid request body"));
    }

    @Test
    public void shouldPatchPreserveUnchangedFields() throws Exception {
        // given
        HttpRequest request = request("/api/users/1")
            .withMethod("PATCH")
            .withBody("{\"name\":\"Alice Patched\"}");

        // when
        HttpResponse response = handler.handlePatch(request);

        // then
        assertThat(response.getStatusCode(), is(200));
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.get("name").asText(), is("Alice Patched"));
        assertThat(body.get("email").asText(), is("alice@example.com"));
    }

    // DELETE

    @Test
    public void shouldDeleteItem() {
        // given
        HttpRequest request = request("/api/users/1").withMethod("DELETE");

        // when
        HttpResponse response = handler.handleDelete(request);

        // then
        assertThat(response.getStatusCode(), is(204));
        assertThat(store.size(), is(1));
        assertThat(store.getById("1"), is(nullValue()));
    }

    @Test
    public void shouldReturn404WhenDeletingNonExistentItem() {
        // given
        HttpRequest request = request("/api/users/999").withMethod("DELETE");

        // when
        HttpResponse response = handler.handleDelete(request);

        // then
        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getBodyAsString(), containsString("not found"));
    }

    @Test
    public void shouldReturn400ForMissingIdInDelete() {
        // given
        HttpRequest request = request("/api/users").withMethod("DELETE");

        // when
        HttpResponse response = handler.handleDelete(request);

        // then
        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("missing id"));
    }

    // Path extraction

    @Test
    public void shouldExtractIdFromPath() {
        // given
        HttpRequest request = request("/api/users/42").withMethod("GET");

        // when
        String id = handler.extractIdFromPath(request);

        // then
        assertThat(id, is("42"));
    }

    @Test
    public void shouldExtractStringIdFromPath() {
        // given
        HttpRequest request = request("/api/users/abc-123").withMethod("GET");

        // when
        String id = handler.extractIdFromPath(request);

        // then
        assertThat(id, is("abc-123"));
    }

    @Test
    public void shouldReturnNullForBasePathOnly() {
        // given
        HttpRequest request = request("/api/users").withMethod("GET");

        // when
        String id = handler.extractIdFromPath(request);

        // then
        assertThat(id, is(nullValue()));
    }

    @Test
    public void shouldHandleTrailingSlashOnBasePath() {
        // given
        CrudActionHandler handlerWithSlash = new CrudActionHandler(store, "/api/users/");
        HttpRequest request = request("/api/users/1").withMethod("GET");

        // when
        String id = handlerWithSlash.extractIdFromPath(request);

        // then
        assertThat(id, is("1"));
    }

    // UUID strategy

    @Test
    public void shouldCreateItemWithUuidStrategy() throws Exception {
        // given
        CrudDataStore uuidStore = new CrudDataStore("id", IdStrategy.UUID);
        CrudActionHandler uuidHandler = new CrudActionHandler(uuidStore, "/api/items");
        HttpRequest request = request("/api/items")
            .withMethod("POST")
            .withBody("{\"name\":\"Item1\"}");

        // when
        HttpResponse response = uuidHandler.handleCreate(request);

        // then
        assertThat(response.getStatusCode(), is(201));
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getBodyAsString());
        assertThat(body.has("id"), is(true));
        assertThat(body.get("id").asText(), not(emptyOrNullString()));
    }
}
