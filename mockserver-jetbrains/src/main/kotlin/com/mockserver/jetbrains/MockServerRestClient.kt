package com.mockserver.jetbrains

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Minimal REST client for talking to a running MockServer instance from the IDE.
 *
 * This object is deliberately free of any IntelliJ-platform API so it can be unit
 * tested directly with no IDE and no live server: every network operation is split
 * into a *pure* request-builder (`build*Request`) that can be asserted on, and a
 * thin `send` wrapper. The contracts mirror the VS Code extension's
 * `mockServerClient.ts`.
 *
 * IMPORTANT: callers must never invoke [send] (or the high-level `*` helpers) on
 * the IntelliJ event-dispatch thread — these block on the network. Run them on a
 * pooled/background thread and marshal UI work back onto the EDT.
 */
object MockServerRestClient {

    private val PRETTY: Gson = GsonBuilder().setPrettyPrinting().create()
    private val COMPACT: Gson = Gson()

    /** Base URL for a server reachable on localhost at [port], e.g. `http://localhost:1080`. */
    fun buildBaseUrl(port: Int): String = "http://localhost:$port"

    private fun newClient(): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    /** Result of a high-level call: the HTTP status plus the (possibly formatted) body. */
    data class Result(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    // ---------------------------------------------------------------------
    // Pure request builders (no I/O) — unit-testable.
    // ---------------------------------------------------------------------

    /**
     * `PUT /mockserver/expectation` — load one expectation or an array of
     * expectations. The body is the file text as-is (MockServer accepts either
     * shape); we only set the JSON content type.
     */
    fun buildLoadExpectationsRequest(baseUrl: String, fileText: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/expectation"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(fileText))
            .build()

    /**
     * `PUT /mockserver/reset` — clear all expectations and recorded logs from the
     * running MockServer. The endpoint takes no body.
     */
    fun buildResetRequest(baseUrl: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/reset"))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

    /**
     * `PUT /mockserver/retrieve?type=recorded_expectations&format=<format>` —
     * retrieve expectations generated from recorded (proxied/forwarded) traffic.
     */
    fun buildRetrieveRecordedRequest(baseUrl: String, format: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/retrieve?type=recorded_expectations&format=$format"))
            .header("Accept", "application/json")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

    /**
     * `PUT /mockserver/retrieve?type=requests&format=json` — retrieve the requests
     * MockServer has received as a JSON array. Each request's `headers` is an array
     * of `{ "name", "values": [...] }` entries. The endpoint takes no body. Used by
     * distributed-trace correlation to find every received request belonging to a
     * given W3C trace.
     */
    fun buildRetrieveRequestsRequest(baseUrl: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/retrieve?type=requests&format=json"))
            .header("Accept", "application/json")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

    /**
     * `GET /mockserver/drift` — retrieve the mock-drift records MockServer recorded
     * when it proxied/forwarded traffic to a real upstream and a matching stub
     * differed. When [limit] is provided it is appended as `?limit=<n>`.
     */
    fun buildRetrieveDriftRequest(baseUrl: String, limit: Int? = null): HttpRequest {
        val uri = if (limit != null) "$baseUrl/mockserver/drift?limit=$limit" else "$baseUrl/mockserver/drift"
        return HttpRequest.newBuilder()
            .uri(URI.create(uri))
            .header("Accept", "application/json")
            .GET()
            .build()
    }

    /**
     * `GET /mockserver/chaosExperiment` — fetch the current chaos-experiment status
     * (running stage, remaining ms, auto-halt state). Returns 200 with the status JSON,
     * or 404 when no experiment has run since the last reset.
     */
    fun buildChaosStatusRequest(baseUrl: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/chaosExperiment"))
            .header("Accept", "application/json")
            .GET()
            .build()

    /**
     * `PUT /mockserver/chaosExperiment` — start (or replace) a chaos experiment from the
     * [experimentJson] definition (`{ name, stages: [...] }`). Returns 200 + status, or
     * 400 on a validation error. The body is sent as-is.
     */
    fun buildChaosStartRequest(baseUrl: String, experimentJson: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/chaosExperiment"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(experimentJson))
            .build()

    /**
     * `DELETE /mockserver/chaosExperiment` — stop the running experiment and clear all
     * chaos. Idempotent; returns 204.
     */
    fun buildChaosStopRequest(baseUrl: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/chaosExperiment"))
            .DELETE()
            .build()

    /**
     * `PUT /mockserver/openapi` — generate expectations from an OpenAPI/Swagger
     * spec. A JSON spec is sent as a parsed object so the server treats it
     * unambiguously as an inline payload; anything else (YAML) is sent as a
     * string, which the server also parses. See [buildOpenApiBody].
     */
    fun buildGenerateFromOpenApiRequest(baseUrl: String, specText: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/openapi"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(buildOpenApiBody(specText)))
            .build()

    /**
     * Build the `{"specUrlOrPayload": ...}` body for the OpenAPI endpoint. When
     * [specText] parses as JSON the value is the embedded object/array; otherwise
     * (YAML, or anything unparseable) it is the raw string.
     */
    fun buildOpenApiBody(specText: String): String {
        val parsed: JsonElement? = tryParseJson(specText)
        val payload: JsonElement = parsed ?: COMPACT.toJsonTree(specText)
        val wrapper = com.google.gson.JsonObject()
        wrapper.add("specUrlOrPayload", payload)
        return COMPACT.toJson(wrapper)
    }

    /**
     * `PUT /mockserver/wasm/modules?name=<name>` — upload a compiled WebAssembly
     * (`.wasm`) custom-rule module by name. The body is the raw module [bytes] sent
     * as `application/octet-stream`; [name] is URL-encoded into the query string.
     * Once uploaded the module can be referenced by name as a WASM body matcher.
     * A 403 is returned when the server has WASM support disabled.
     */
    fun buildWasmUploadRequest(baseUrl: String, name: String, bytes: ByteArray): HttpRequest {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/wasm/modules?name=$encodedName"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
    }

    /**
     * `GET /mockserver/wasm/modules` — list the names of the WASM custom-rule
     * modules currently registered on the running MockServer (JSON array).
     */
    fun buildListWasmRequest(baseUrl: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/wasm/modules"))
            .header("Accept", "application/json")
            .GET()
            .build()

    /**
     * A sample request to test a WASM module against via [buildWasmTestRequest]. All parts are
     * optional (an empty spec tests a body-only request). [queryStringParameters]/[headers] map
     * each name to its list of values; [cookies] maps each name to a single value — mirroring the
     * envelope-v2 shape MockServer exposes to a `match_request` module.
     */
    data class WasmTestSpec(
        val method: String = "",
        val path: String = "",
        val queryStringParameters: Map<String, List<String>> = emptyMap(),
        val headers: Map<String, List<String>> = emptyMap(),
        val cookies: Map<String, String> = emptyMap(),
        val body: String? = null,
    )

    /**
     * `POST /mockserver/wasm/test` — test a compiled WASM module against a sample request without
     * uploading it or creating an expectation. The module [bytes] are Base64-encoded into the
     * `module` field and [spec] is serialised as the `request` (envelope-v2 shape:
     * method / path / queryStringParameters / headers / cookies / body). The server responds with
     * `{ "matched": <bool> }` (parse with [parseWasmTestMatched]); a 403 means WASM support is
     * disabled. Matching is fail-closed server-side — an invalid module reports `matched: false`.
     */
    fun buildWasmTestRequest(baseUrl: String, bytes: ByteArray, spec: WasmTestSpec): HttpRequest {
        val request = JsonObject().apply {
            addProperty("method", spec.method)
            addProperty("path", spec.path)
            add("queryStringParameters", JsonObject().apply {
                spec.queryStringParameters.forEach { (name, values) ->
                    add(name, JsonArray().apply { values.forEach { add(it) } })
                }
            })
            add("headers", JsonObject().apply {
                spec.headers.forEach { (name, values) ->
                    add(name, JsonArray().apply { values.forEach { add(it) } })
                }
            })
            add("cookies", JsonObject().apply {
                spec.cookies.forEach { (name, value) -> addProperty(name, value) }
            })
            if (spec.body != null) {
                addProperty("body", spec.body)
            }
        }
        val payload = JsonObject().apply {
            addProperty("module", Base64.getEncoder().encodeToString(bytes))
            add("request", request)
        }
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/wasm/test"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(COMPACT.toJson(payload)))
            .build()
    }

    /**
     * Parse the `{ "matched": <bool> }` body returned by `POST /mockserver/wasm/test`, returning
     * `false` for anything that is not an object with a truthy `matched` field (fail-closed).
     */
    fun parseWasmTestMatched(body: String): Boolean =
        try {
            val node = JsonParser.parseString(body)
            node.isJsonObject && node.asJsonObject.has("matched") && node.asJsonObject.get("matched").asBoolean
        } catch (ex: Exception) {
            false
        }

    /**
     * `PUT /mockserver/debugMismatch` — ask the server whether [requestDefinitionJson]
     * (an `httpRequest` definition: `{ method, path, headers, body }`) would match any
     * registered expectation, and — when it would not — the per-field differences of
     * the closest non-matching expectation. The body is the request definition JSON.
     */
    fun buildDebugMismatchRequest(baseUrl: String, requestDefinitionJson: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/debugMismatch"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(requestDefinitionJson))
            .build()

    /**
     * `PUT /mockserver/verify` — verify the server received a request matching
     * [requestDefinitionJson] at least once. The body wraps the request definition in
     * `{ "httpRequest": <def>, "times": { "atLeast": 1 } }`. A 202 means verified; a
     * 406 carries the human-readable failure reason in its body.
     */
    fun buildVerifyRequest(baseUrl: String, requestDefinitionJson: String): HttpRequest {
        val definition = tryParseJson(requestDefinitionJson) ?: COMPACT.toJsonTree(requestDefinitionJson)
        // atMost -1 = no upper bound. The server's VerificationTimesDTO.atMost is a
        // primitive int defaulting to 0 when omitted, which makes `times <= atMost`
        // false for any real receipt — so an absent atMost would always 406.
        val times = JsonObject().apply { addProperty("atLeast", 1); addProperty("atMost", -1) }
        val wrapper = JsonObject().apply {
            add("httpRequest", definition)
            add("times", times)
        }
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/verify"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(COMPACT.toJson(wrapper)))
            .build()
    }

    /**
     * `PUT /mockserver/clear?type=expectations` — clear the expectation(s) whose
     * request matches [requestDefinitionJson]. The body is the request definition JSON.
     */
    fun buildClearExpectationsRequest(baseUrl: String, requestDefinitionJson: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/clear?type=expectations"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(requestDefinitionJson))
            .build()

    // ---------------------------------------------------------------------
    // Ad-hoc ("scratch") request support — pure + unit-testable.
    // ---------------------------------------------------------------------

    /**
     * An ad-hoc HTTP request the user authored in the editor to fire at the running
     * MockServer. Mirrors the VS Code "Send Test Request" spec shape:
     * `{ "method": "GET", "path": "/api/x", "headers": { "K": "V" }, "body": "..." }`.
     * [method] and [path] are required; [headers] and [body] are optional.
     */
    data class RequestSpec(
        val method: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    )

    /**
     * Parse a scratch-request spec from [text]. Mirrors the VS Code `parseRequestSpec`
     * semantics: the text must be a JSON object with non-blank string `method` and
     * `path`; `headers` (if present) must be an object whose values are all strings;
     * `body` (if present) must be a string. Throws [IllegalArgumentException] with a
     * clear message otherwise.
     */
    fun parseRequestSpec(text: String): RequestSpec {
        val element: JsonElement = try {
            JsonParser.parseString(text)
        } catch (ex: Exception) {
            throw IllegalArgumentException("The active editor isn't valid JSON: ${ex.message}")
        }
        if (!element.isJsonObject) {
            throw IllegalArgumentException(
                "The request must be a JSON object, e.g. " +
                    """{ "method": "GET", "path": "/api/x", "headers": {}, "body": "" }."""
            )
        }
        val obj = element.asJsonObject
        val method = requiredString(obj, "method")
        val path = requiredString(obj, "path")

        val headers = LinkedHashMap<String, String>()
        if (obj.has("headers") && !obj.get("headers").isJsonNull) {
            val headersElement = obj.get("headers")
            if (!headersElement.isJsonObject) {
                throw IllegalArgumentException("\"headers\" must be a JSON object of string values.")
            }
            for ((key, value) in headersElement.asJsonObject.entrySet()) {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                    throw IllegalArgumentException("Header \"$key\" must have a string value.")
                }
                headers[key] = value.asString
            }
        }

        var body: String? = null
        if (obj.has("body") && !obj.get("body").isJsonNull) {
            val bodyElement = obj.get("body")
            if (!bodyElement.isJsonPrimitive || !bodyElement.asJsonPrimitive.isString) {
                throw IllegalArgumentException("\"body\" must be a string.")
            }
            body = bodyElement.asString
        }

        return RequestSpec(method, path, headers, body)
    }

    private fun requiredString(obj: JsonObject, field: String): String {
        if (!obj.has(field) || obj.get(field).isJsonNull) {
            throw IllegalArgumentException("\"$field\" is required and must be a non-blank string.")
        }
        val element = obj.get(field)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw IllegalArgumentException("\"$field\" is required and must be a non-blank string.")
        }
        val value = element.asString
        if (value.isBlank()) {
            throw IllegalArgumentException("\"$field\" is required and must be a non-blank string.")
        }
        return value
    }

    /**
     * Build the ad-hoc request described by [spec] against [baseUrl]. The URI is
     * `baseUrl + spec.path`, the method is `spec.method`, each header is set, and the
     * body is `spec.body` (an empty body for a body-less method, e.g. GET).
     */
    fun buildScratchRequest(baseUrl: String, spec: RequestSpec): HttpRequest {
        val builder = HttpRequest.newBuilder().uri(URI.create("$baseUrl${spec.path}"))
        for ((key, value) in spec.headers) {
            builder.header(key, value)
        }
        val bodyPublisher =
            if (spec.body.isNullOrEmpty()) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(spec.body)
        builder.method(spec.method, bodyPublisher)
        return builder.build()
    }

    /**
     * Convert a parsed [spec] into the `httpRequest` definition JSON the
     * `debugMismatch`, `verify`, and `clear` endpoints understand: `{ method, path,
     * headers, body }`, with headers in the object-map form `{ name: [value] }`. An
     * empty header map / null body is omitted.
     */
    fun requestSpecToDefinitionJson(spec: RequestSpec): String {
        val definition = JsonObject()
        definition.addProperty("method", spec.method)
        definition.addProperty("path", spec.path)
        if (spec.headers.isNotEmpty()) {
            val headers = JsonObject()
            for ((name, value) in spec.headers) {
                val values = JsonArray()
                values.add(value)
                headers.add(name, values)
            }
            definition.add("headers", headers)
        }
        if (spec.body != null) {
            definition.addProperty("body", spec.body)
        }
        return COMPACT.toJson(definition)
    }

    /**
     * The outcome of a `debugMismatch` analysis: whether the request [matched] a
     * registered expectation, the [expectationId] of the matched/closest expectation,
     * the closest expectation's matched/total field counts, its per-field [differences]
     * (field name → messages) on a miss, and whether the server had [noExpectations].
     */
    data class MatchAnalysis(
        val matched: Boolean,
        val expectationId: String?,
        val matchedFields: Int?,
        val totalFields: Int?,
        val differences: Map<String, List<String>>,
        val noExpectations: Boolean,
    )

    /**
     * Reduce a `debugMismatch` response [body] into a [MatchAnalysis]. The body shape
     * is `{ totalExpectations, results: [{ matches, differences, ... }], closestMatch? }`.
     * Defensive against missing fields and non-JSON input.
     */
    fun parseMatchAnalysis(body: String): MatchAnalysis {
        val parsed = tryParseJson(body)
        if (parsed == null || !parsed.isJsonObject) {
            return MatchAnalysis(false, null, null, null, emptyMap(), false)
        }
        val root = parsed.asJsonObject
        val results = if (root.has("results") && root.get("results").isJsonArray) root.getAsJsonArray("results") else JsonArray()
        val hasTotal = root.has("totalExpectations") && root.get("totalExpectations").isJsonPrimitive
        val totalExpectations = if (hasTotal) {
            try { root.get("totalExpectations").asInt } catch (_: Exception) { results.size() }
        } else {
            results.size()
        }
        // "No expectations" only when the server genuinely has none. A positive
        // totalExpectations with an empty results array (all truncated) is a miss.
        val noExpectations = if (hasTotal) totalExpectations == 0 else results.size() == 0

        for (element in results) {
            if (element.isJsonObject && element.asJsonObject.let { it.has("matches") && it.get("matches").isJsonPrimitive && it.get("matches").asBoolean }) {
                val matched = element.asJsonObject
                return MatchAnalysis(
                    matched = true,
                    expectationId = if (matched.has("expectationId") && matched.get("expectationId").isJsonPrimitive) matched.get("expectationId").asString else null,
                    matchedFields = intOrNull(matched, "matchedFieldCount"),
                    totalFields = intOrNull(matched, "totalFieldCount"),
                    differences = emptyMap(),
                    noExpectations = false,
                )
            }
        }

        val closest = if (root.has("closestMatch") && root.get("closestMatch").isJsonObject) root.getAsJsonObject("closestMatch") else null
        val closestId = if (closest != null && closest.has("expectationId") && closest.get("expectationId").isJsonPrimitive) closest.get("expectationId").asString else null
        val differences = LinkedHashMap<String, List<String>>()
        if (closestId != null) {
            for (element in results) {
                if (!element.isJsonObject) continue
                val result = element.asJsonObject
                val id = if (result.has("expectationId") && result.get("expectationId").isJsonPrimitive) result.get("expectationId").asString else continue
                if (id != closestId) continue
                if (result.has("differences") && result.get("differences").isJsonObject) {
                    for ((field, value) in result.getAsJsonObject("differences").entrySet()) {
                        if (value.isJsonArray) {
                            differences[field] = value.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
                        }
                    }
                }
            }
        }
        return MatchAnalysis(
            matched = false,
            expectationId = closestId,
            matchedFields = if (closest != null) intOrNull(closest, "matchedFields") else null,
            totalFields = if (closest != null) intOrNull(closest, "totalFields") else null,
            differences = differences,
            noExpectations = noExpectations,
        )
    }

    /** Read [field] from [obj] as an int (the server emits these as JSON numbers), or null when absent/non-numeric. */
    private fun intOrNull(obj: JsonObject, field: String): Int? {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive) return null
        return try { obj.get(field).asInt } catch (_: Exception) { null }
    }

    /**
     * Render a [MatchAnalysis] into a readable summary for the scratch-response view:
     * a clear matched / not-matched headline and, on a miss, the closest expectation
     * and its per-field differences (the "nearest miss").
     */
    fun formatMatchAnalysis(analysis: MatchAnalysis): String {
        if (analysis.matched) {
            val id = analysis.expectationId?.let { " (expectation $it)" } ?: ""
            return "MATCHED a registered expectation$id."
        }
        if (analysis.noExpectations) {
            return "NOT MATCHED — no registered expectations to match against."
        }
        val builder = StringBuilder("NOT MATCHED any registered expectation.")
        if (analysis.expectationId != null) {
            val fields = if (analysis.matchedFields != null && analysis.totalFields != null) {
                " (${analysis.matchedFields}/${analysis.totalFields} fields matched)"
            } else {
                ""
            }
            builder.append("\nClosest: expectation ").append(analysis.expectationId).append(fields)
        }
        for ((field, messages) in analysis.differences) {
            for (message in messages) {
                builder.append("\n  - ").append(field).append(": ").append(message)
            }
        }
        return builder.toString()
    }

    /**
     * Extract each expectation's `httpRequest` definition from an expectation file's
     * [text] (a single object or an array), as compact JSON strings — for verifying or
     * clearing every declared request. Expectations with no object `httpRequest` are
     * skipped. Returns an empty list when none are found or the text is not JSON.
     */
    fun extractRequestDefinitions(text: String): List<String> {
        val parsed = tryParseJson(text) ?: return emptyList()
        val expectations: List<JsonElement> = when {
            parsed.isJsonArray -> parsed.asJsonArray.toList()
            parsed.isJsonObject -> listOf(parsed)
            else -> emptyList()
        }
        val definitions = ArrayList<String>()
        for (element in expectations) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            if (obj.has("httpRequest") && obj.get("httpRequest").isJsonObject) {
                definitions.add(COMPACT.toJson(obj.getAsJsonObject("httpRequest")))
            }
        }
        return definitions
    }

    // ---------------------------------------------------------------------
    // Response helpers (no I/O) — unit-testable.
    // ---------------------------------------------------------------------

    /** True when a retrieve/generate body holds no expectations (blank or `[]`). */
    fun isEmptyExpectationsBody(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return true
        val parsed = tryParseJson(trimmed) ?: return false
        return parsed.isJsonArray && parsed.asJsonArray.isEmpty
    }

    /**
     * A formatted mock-drift report: the record [count], the human-readable [report]
     * text, and whether the server reported no drift ([empty]).
     */
    data class DriftReport(val count: Int, val report: String, val empty: Boolean)

    /**
     * Format the `GET /mockserver/drift` response body into a readable text report.
     *
     * The expected shape is
     * `{ "count": <n>, "drifts": [ { expectationId, driftType, field, expectedValue?, actualValue?, confidence, epochTimeMs } ] }`.
     * Robust to non-JSON or missing fields: an unparseable body falls back to the raw
     * text (treated as non-empty); a missing value renders as `—`; when `count` is
     * absent it falls back to the size of the `drifts` array.
     */
    fun formatDriftReport(body: String): DriftReport {
        val parsed = tryParseJson(body)
        if (parsed == null || !parsed.isJsonObject) {
            val raw = body.trim()
            return DriftReport(0, if (raw.isEmpty()) "MockServer drift report — 0 record(s)" else raw, raw.isEmpty())
        }
        val root = parsed.asJsonObject
        val drifts = if (root.has("drifts") && root.get("drifts").isJsonArray) root.getAsJsonArray("drifts") else null
        val count = if (root.has("count") && root.get("count").isJsonPrimitive) {
            try { root.get("count").asInt } catch (_: Exception) { drifts?.size() ?: 0 }
        } else {
            drifts?.size() ?: 0
        }
        val builder = StringBuilder()
        builder.append("MockServer drift report — ").append(count).append(" record(s)")
        if (drifts != null) {
            for (element in drifts) {
                if (!element.isJsonObject) continue
                val drift = element.asJsonObject
                builder.append('\n')
                    .append('[').append(driftString(drift, "driftType")).append("] ")
                    .append(driftString(drift, "field")).append(": expected ")
                    .append(driftString(drift, "expectedValue")).append(" / actual ")
                    .append(driftString(drift, "actualValue")).append(" (confidence ")
                    .append(driftString(drift, "confidence")).append(", expectation ")
                    .append(driftString(drift, "expectationId")).append(')')
            }
        }
        return DriftReport(count, builder.toString(), count == 0)
    }

    /** Render a drift field as a string, falling back to `—` when missing/null. */
    private fun driftString(obj: JsonObject, field: String): String {
        if (!obj.has(field) || obj.get(field).isJsonNull) return "—"
        val element = obj.get(field)
        return if (element.isJsonPrimitive) element.asString else element.toString()
    }

    /** Pretty-print a JSON body; returns the input unchanged if it is not valid JSON. */
    fun prettyPrintJson(body: String): String {
        val parsed = tryParseJson(body) ?: return body
        return PRETTY.toJson(parsed)
    }

    /** True when [text] parses as a JSON object or array (the shape an expectation file must take). */
    fun isJsonObjectOrArray(text: String): Boolean = tryParseJson(text) != null

    private val OPENAPI_YAML_KEY = Regex("""(?im)^\s*["']?(openapi|swagger)["']?\s*:""")

    /**
     * Heuristic check that [specText] looks like an OpenAPI/Swagger specification —
     * i.e. it has a top-level `openapi` (3.x) or `swagger` (2.0) field, in either
     * JSON or YAML. Used to warn the user clearly when the active editor is not a
     * spec, instead of sending it to the server and surfacing a raw 400.
     */
    fun looksLikeOpenApiSpec(specText: String): Boolean {
        val parsed = tryParseJson(specText)
        if (parsed != null && parsed.isJsonObject) {
            val obj = parsed.asJsonObject
            return obj.has("openapi") || obj.has("swagger")
        }
        // YAML (or any non-JSON-object content): look for a top-level openapi:/swagger: key.
        return OPENAPI_YAML_KEY.containsMatchIn(specText)
    }

    // ---------------------------------------------------------------------
    // Contract / resiliency test runner (no I/O) — unit-testable.
    // Mirrors the VS Code extension's `runContractTest` / `formatContractTestReport`.
    // ---------------------------------------------------------------------

    /**
     * `PUT /mockserver/contractTest` — run an OpenAPI contract test of the live service
     * at [serviceBaseUrl] against [specText] (a URL, path, or inline spec). The body is
     * `{ "spec": "<spec text>", "baseUrl": <service url>, "operationId"? }`.
     *
     * `spec` is ALWAYS sent as a JSON **string** (the raw editor text), matching the VS
     * Code extension and the server's own contract: the server reads `spec` with
     * Jackson `JsonNode.asText()` (HttpState.textOrNull), which returns "" for a JSON
     * object/array node — so embedding an inline JSON spec as an object would be read as
     * blank and rejected (400). The server parses the inline spec from the string itself.
     */
    fun buildContractTestRequest(
        baseUrl: String,
        specText: String,
        serviceBaseUrl: String,
        operationId: String? = null,
    ): HttpRequest {
        val body = JsonObject().apply {
            addProperty("spec", specText)
            addProperty("baseUrl", serviceBaseUrl)
            operationId?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("operationId", it) }
        }
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/contractTest"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(COMPACT.toJson(body)))
            .build()
    }

    /** One operation's contract-test outcome. */
    data class ContractOperationResult(
        val operationId: String,
        val method: String,
        val path: String,
        val statusCodeReceived: Int,
        val passed: Boolean,
        val validationErrors: List<String>,
    )

    /** The full contract-test report. */
    data class ContractTestReport(
        val baseUrl: String,
        val totalOperations: Int,
        val passed: Int,
        val failed: Int,
        val allPassed: Boolean,
        val results: List<ContractOperationResult>,
    )

    /**
     * Parse a `contractTest` response [body] into a [ContractTestReport]. Defensive
     * against missing fields. `totalOperations`/`passed`/`failed`/`allPassed` fall back
     * to values derived from the `results` array when the server omits them.
     */
    fun parseContractTestReport(body: String): ContractTestReport {
        val parsed = tryParseJson(body)
        if (parsed == null || !parsed.isJsonObject) {
            return ContractTestReport("", 0, 0, 0, true, emptyList())
        }
        val root = parsed.asJsonObject
        val resultsArray = if (root.has("results") && root.get("results").isJsonArray) root.getAsJsonArray("results") else JsonArray()
        val results = ArrayList<ContractOperationResult>()
        for (element in resultsArray) {
            if (!element.isJsonObject) continue
            val r = element.asJsonObject
            val errors = ArrayList<String>()
            if (r.has("validationErrors") && r.get("validationErrors").isJsonArray) {
                for (err in r.getAsJsonArray("validationErrors")) {
                    if (err.isJsonPrimitive) errors.add(err.asString)
                }
            }
            results.add(
                ContractOperationResult(
                    operationId = stringOr(r, "operationId", ""),
                    method = stringOr(r, "method", ""),
                    path = stringOr(r, "path", ""),
                    statusCodeReceived = intOrNull(r, "statusCodeReceived") ?: 0,
                    passed = r.has("passed") && r.get("passed").isJsonPrimitive && r.get("passed").asBoolean,
                    validationErrors = errors,
                )
            )
        }
        val derivedPassed = results.count { it.passed }
        val total = intOrNull(root, "totalOperations") ?: results.size
        val passedCount = intOrNull(root, "passed") ?: derivedPassed
        val failedCount = intOrNull(root, "failed") ?: (results.size - derivedPassed)
        val allPassed = if (root.has("allPassed") && root.get("allPassed").isJsonPrimitive) {
            root.get("allPassed").asBoolean
        } else {
            failedCount == 0
        }
        return ContractTestReport(
            baseUrl = stringOr(root, "baseUrl", ""),
            totalOperations = total,
            passed = passedCount,
            failed = failedCount,
            allPassed = allPassed,
            results = results,
        )
    }

    /**
     * Render a [report] as a readable, per-operation pass/fail summary (the JetBrains
     * parity of `formatContractTestReport`). Failing operations list their validation
     * errors indented beneath the line.
     */
    fun formatContractTestReport(report: ContractTestReport): String {
        val header = "MockServer contract test against ${report.baseUrl} — " +
            "${report.passed}/${report.totalOperations} passed" +
            if (report.allPassed) " (all passed)" else ", ${report.failed} failed"
        val lines = ArrayList<String>()
        lines.add(header)
        lines.add("")
        for (r in report.results) {
            val mark = if (r.passed) "PASS" else "FAIL"
            val base = "[$mark] ${r.method} ${r.path} [${r.operationId}] -> HTTP ${r.statusCodeReceived}"
            if (r.passed || r.validationErrors.isEmpty()) {
                lines.add(base)
            } else {
                lines.add(base)
                for (error in r.validationErrors) lines.add("    - $error")
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun stringOr(obj: JsonObject, field: String, fallback: String): String {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive) return fallback
        return try { obj.get(field).asString } catch (_: Exception) { fallback }
    }

    // ---------------------------------------------------------------------
    // Distributed-trace correlation (no I/O) — unit-testable.
    // ---------------------------------------------------------------------

    /** Full W3C `traceparent`: `version-traceId-parentId-flags` (2/32/16/2 hex). */
    private val TRACEPARENT = Regex("(?i)^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$")

    /** A bare 32-hex W3C trace id. */
    private val TRACE_ID = Regex("(?i)^[0-9a-f]{32}$")

    /**
     * Extract the 32-hex W3C trace id from [input], lower-cased. Accepts either a
     * full `traceparent` header value (`version-traceId-parentId-flags`) — in which
     * case the embedded trace id is returned — or a bare 32-hex trace id. Returns
     * `null` when [input] is neither.
     */
    fun extractTraceId(input: String): String? {
        val trimmed = input.trim()
        val match = TRACEPARENT.find(trimmed)
        if (match != null) {
            return match.groupValues[1].lowercase()
        }
        if (TRACE_ID.matches(trimmed)) {
            return trimmed.lowercase()
        }
        return null
    }

    /**
     * The result of filtering received requests by trace: the resolved [traceId]
     * (`null` when [input] was not a valid trace id / traceparent) and the pretty
     * JSON array [matchesJson] of the matching request objects (`"[]"` when none
     * match or the trace id was invalid).
     */
    data class TraceFilterResult(val traceId: String?, val matchesJson: String)

    /**
     * Filter the received-request array in [requestsJson] (as returned by
     * `PUT /mockserver/retrieve?type=requests&format=json`) down to the requests
     * belonging to the trace described by [input].
     *
     * A request matches when its `headers` array contains an entry whose name is
     * `traceparent` (case-insensitive) and any of whose values parses as a W3C
     * `traceparent` carrying the target trace id. Defensive against a missing or
     * non-array `headers` element and against malformed entries.
     */
    fun filterRequestsByTrace(requestsJson: String, input: String): TraceFilterResult {
        val traceId = extractTraceId(input) ?: return TraceFilterResult(null, "[]")
        val matches = JsonArray()
        val parsed = try {
            JsonParser.parseString(requestsJson)
        } catch (_: Exception) {
            return TraceFilterResult(traceId, "[]")
        }
        if (!parsed.isJsonArray) {
            return TraceFilterResult(traceId, "[]")
        }
        for (element in parsed.asJsonArray) {
            if (!element.isJsonObject) continue
            if (requestHasTrace(element.asJsonObject, traceId)) {
                matches.add(element)
            }
        }
        return TraceFilterResult(traceId, PRETTY.toJson(matches))
    }

    /** True when [request]'s `headers` carry a `traceparent` with [traceId]. */
    private fun requestHasTrace(request: JsonObject, traceId: String): Boolean {
        if (!request.has("headers")) return false
        val headers = request.get("headers")
        // Primary: the object-map form the server actually serializes —
        // { "traceparent": ["00-<trace>-..."], "host": ["..."] }.
        if (headers.isJsonObject) {
            for ((name, value) in headers.asJsonObject.entrySet()) {
                if (!name.equals("traceparent", ignoreCase = true)) continue
                if (value.isJsonArray && valuesContainTrace(value.asJsonArray, traceId)) return true
            }
            return false
        }
        // Defensive fallback: the array-of-{name,values} form.
        if (headers.isJsonArray) {
            for (headerElement in headers.asJsonArray) {
                if (!headerElement.isJsonObject) continue
                val header = headerElement.asJsonObject
                val name = if (header.has("name") && header.get("name").isJsonPrimitive) header.get("name").asString else continue
                if (!name.equals("traceparent", ignoreCase = true)) continue
                if (header.has("values") && header.get("values").isJsonArray &&
                    valuesContainTrace(header.getAsJsonArray("values"), traceId)) return true
            }
        }
        return false
    }

    /**
     * Build a trace-backend URL for [traceId] from a user-configured [template], for
     * opening the correlated OpenTelemetry trace in a browser (Jaeger/Tempo/Grafana).
     * Every `{traceId}` placeholder is substituted (URL-encoded); when the template has
     * no placeholder the (encoded) trace id is appended. Returns `null` when [template]
     * is blank — the caller should then degrade to surfacing the bare trace id. Mirrors
     * the VS Code extension's `buildTraceUrl`.
     */
    fun buildTraceUrl(template: String, traceId: String): String? {
        val trimmed = template.trim()
        if (trimmed.isEmpty()) return null
        // URL-encode for a path/query segment (RFC 3986). URLEncoder applies
        // application/x-www-form-urlencoded rules, so a space becomes "+"; rewrite it
        // to "%20" so the result matches the VS Code helper's encodeURIComponent and is
        // correct in a path segment. (In practice traceId is always 32-hex, so this is
        // defensive — but it keeps the two extensions byte-for-byte identical.)
        val encoded = URLEncoder.encode(traceId, Charsets.UTF_8).replace("+", "%20")
        return if (trimmed.contains("{traceId}")) {
            trimmed.replace("{traceId}", encoded)
        } else {
            trimmed + encoded
        }
    }

    private fun valuesContainTrace(values: JsonArray, traceId: String): Boolean {
        for (value in values) {
            if (!value.isJsonPrimitive) continue
            val match = TRACEPARENT.find(value.asString.trim()) ?: continue
            if (match.groupValues[1].lowercase() == traceId) return true
        }
        return false
    }

    private fun tryParseJson(text: String): JsonElement? =
        try {
            val element = JsonParser.parseString(text)
            // parseString accepts bare primitives ("foo", 12); for our purposes
            // only objects/arrays count as a real JSON spec/payload.
            if (element.isJsonObject || element.isJsonArray) element else null
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------------
    // I/O — must run off the EDT.
    // ---------------------------------------------------------------------

    /** Send a prepared request and capture the status + body as a string. */
    fun send(request: HttpRequest): Result {
        val response: HttpResponse<String> =
            newClient().send(request, HttpResponse.BodyHandlers.ofString())
        return Result(response.statusCode(), response.body() ?: "")
    }
}
