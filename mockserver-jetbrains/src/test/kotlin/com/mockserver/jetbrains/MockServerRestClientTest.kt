package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure (no-IDE, no-network) parts of [MockServerRestClient]:
 * URL building, request construction, the OpenAPI JSON-vs-YAML body branch, and
 * response handling. No running IDE or live server is required.
 */
class MockServerRestClientTest {

    private fun bodyOf(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
        val sb = StringBuilder()
        val subscriber = object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(item: java.nio.ByteBuffer) { sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item)) }
            override fun onError(t: Throwable) {}
            override fun onComplete() {}
        }
        publisher.subscribe(subscriber)
        return sb.toString()
    }

    // --- buildBaseUrl ---------------------------------------------------

    @Test
    fun `base url uses localhost and the given port`() {
        assertEquals("http://localhost:1080", MockServerRestClient.buildBaseUrl(1080))
        assertEquals("http://localhost:2080", MockServerRestClient.buildBaseUrl(2080))
    }

    // --- load expectations ----------------------------------------------

    @Test
    fun `load expectations PUTs to expectation endpoint with json content type and body verbatim`() {
        val text = """{ "httpRequest": { "path": "/a" } }"""
        val req = MockServerRestClient.buildLoadExpectationsRequest("http://localhost:1080", text)
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/expectation", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""))
        assertEquals(text, bodyOf(req))
    }

    @Test
    fun `load expectations sends an array body unchanged`() {
        val text = """[ { "httpRequest": { "path": "/a" } }, { "httpRequest": { "path": "/b" } } ]"""
        val req = MockServerRestClient.buildLoadExpectationsRequest("http://localhost:1080", text)
        assertEquals(text, bodyOf(req))
    }

    // --- reset ----------------------------------------------------------

    @Test
    fun `reset PUTs to the reset endpoint with no body`() {
        val req = MockServerRestClient.buildResetRequest("http://localhost:1080")
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/reset", req.uri().toString())
        assertEquals(0L, req.bodyPublisher().orElseThrow().contentLength())
    }

    // --- retrieve recorded ----------------------------------------------

    @Test
    fun `retrieve recorded PUTs with type and json format query params`() {
        val req = MockServerRestClient.buildRetrieveRecordedRequest("http://localhost:1080", "json")
        assertEquals("PUT", req.method())
        assertEquals(
            "http://localhost:1080/mockserver/retrieve?type=recorded_expectations&format=json",
            req.uri().toString()
        )
    }

    @Test
    fun `retrieve recorded honours the requested format`() {
        val req = MockServerRestClient.buildRetrieveRecordedRequest("http://localhost:1080", "java")
        assertTrue(req.uri().toString().endsWith("format=java"))
    }

    // --- retrieve drift -------------------------------------------------

    @Test
    fun `retrieve drift GETs the drift endpoint`() {
        val req = MockServerRestClient.buildRetrieveDriftRequest("http://localhost:1080")
        assertEquals("GET", req.method())
        assertEquals("http://localhost:1080/mockserver/drift", req.uri().toString())
    }

    @Test
    fun `retrieve drift appends the limit query param when provided`() {
        val req = MockServerRestClient.buildRetrieveDriftRequest("http://localhost:1080", 25)
        assertEquals("GET", req.method())
        assertEquals("http://localhost:1080/mockserver/drift?limit=25", req.uri().toString())
    }

    // --- wasm modules ---------------------------------------------------

    @Test
    fun `wasm upload PUTs raw bytes to the modules endpoint with octet-stream content type`() {
        val bytes = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val req = MockServerRestClient.buildWasmUploadRequest("http://localhost:1080", "myRule", bytes)
        assertEquals("PUT", req.method())
        assertTrue(req.uri().toString().contains("/mockserver/wasm/modules"), req.uri().toString())
        assertTrue(req.uri().toString().contains("name=myRule"), req.uri().toString())
        assertEquals("application/octet-stream", req.headers().firstValue("Content-Type").orElse(""))
        assertEquals(bytes.size.toLong(), req.bodyPublisher().orElseThrow().contentLength())
    }

    @Test
    fun `wasm upload url-encodes the module name`() {
        val req = MockServerRestClient.buildWasmUploadRequest("http://localhost:1080", "my rule/v2", byteArrayOf(1, 2))
        val uri = req.uri().toString()
        assertTrue(uri.contains("name=my+rule%2Fv2"), uri)
    }

    @Test
    fun `wasm list GETs the modules endpoint`() {
        val req = MockServerRestClient.buildListWasmRequest("http://localhost:1080")
        assertEquals("GET", req.method())
        assertEquals("http://localhost:1080/mockserver/wasm/modules", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Accept").orElse(""))
    }

    @Test
    fun `wasm test POSTs base64 module and envelope-v2 sample request to the test endpoint`() {
        val bytes = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val spec = MockServerRestClient.WasmTestSpec(
            method = "POST",
            path = "/orders",
            queryStringParameters = mapOf("tenant" to listOf("acme")),
            headers = mapOf("X-Tenant" to listOf("acme")),
            cookies = mapOf("session" to "abc123"),
            body = "{}"
        )
        val req = MockServerRestClient.buildWasmTestRequest("http://localhost:1080", bytes, spec)
        assertEquals("POST", req.method())
        assertEquals("http://localhost:1080/mockserver/wasm/test", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""))

        val payload = JsonParser.parseString(bodyOf(req)).asJsonObject
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(bytes),
            payload.get("module").asString
        )
        val request = payload.getAsJsonObject("request")
        assertEquals("POST", request.get("method").asString)
        assertEquals("/orders", request.get("path").asString)
        assertEquals("acme", request.getAsJsonObject("queryStringParameters").getAsJsonArray("tenant")[0].asString)
        assertEquals("acme", request.getAsJsonObject("headers").getAsJsonArray("X-Tenant")[0].asString)
        assertEquals("abc123", request.getAsJsonObject("cookies").get("session").asString)
        assertEquals("{}", request.get("body").asString)
    }

    @Test
    fun `wasm test omits the body field for a body-less spec`() {
        val req = MockServerRestClient.buildWasmTestRequest(
            "http://localhost:1080",
            byteArrayOf(1, 2, 3),
            MockServerRestClient.WasmTestSpec(method = "GET", path = "/")
        )
        val request = JsonParser.parseString(bodyOf(req)).asJsonObject.getAsJsonObject("request")
        assertFalse(request.has("body"), "body should be omitted when the spec has no body")
    }

    @Test
    fun `parseWasmTestMatched reads the matched flag and fails closed on junk`() {
        assertTrue(MockServerRestClient.parseWasmTestMatched("{\"matched\":true}"))
        assertFalse(MockServerRestClient.parseWasmTestMatched("{\"matched\":false}"))
        assertFalse(MockServerRestClient.parseWasmTestMatched("not json"))
        assertFalse(MockServerRestClient.parseWasmTestMatched("{}"))
    }

    // --- formatDriftReport ----------------------------------------------

    @Test
    fun `formatDriftReport treats an empty drift payload as empty`() {
        val report = MockServerRestClient.formatDriftReport("""{ "count": 0, "drifts": [] }""")
        assertTrue(report.empty)
        assertEquals(0, report.count)
        assertTrue(report.report.contains("0 record(s)"))
    }

    @Test
    fun `formatDriftReport renders one line per drift with type and field`() {
        val body = """
            {
              "count": 2,
              "drifts": [
                { "expectationId": "e1", "driftType": "STATUS_CODE", "field": "statusCode",
                  "expectedValue": "200", "actualValue": "500", "confidence": 0.9, "epochTimeMs": 1 },
                { "expectationId": "e2", "driftType": "HEADER", "field": "Content-Type",
                  "expectedValue": "text/plain", "actualValue": "application/json", "confidence": 0.7, "epochTimeMs": 2 }
              ]
            }
        """.trimIndent()
        val report = MockServerRestClient.formatDriftReport(body)
        assertFalse(report.empty)
        assertEquals(2, report.count)
        assertTrue(report.report.contains("STATUS_CODE"), report.report)
        assertTrue(report.report.contains("statusCode"), report.report)
        assertTrue(report.report.contains("HEADER"), report.report)
        assertTrue(report.report.contains("Content-Type"), report.report)
        assertTrue(report.report.contains("expected 200 / actual 500"), report.report)
        assertTrue(report.report.contains("expectation e1"), report.report)
    }

    @Test
    fun `formatDriftReport falls back to the drifts array size when count is missing`() {
        val body = """{ "drifts": [ { "driftType": "BODY", "field": "x" } ] }"""
        val report = MockServerRestClient.formatDriftReport(body)
        assertEquals(1, report.count)
        assertFalse(report.empty)
    }

    @Test
    fun `formatDriftReport renders missing expected and actual values as a dash`() {
        val body = """{ "count": 1, "drifts": [ { "driftType": "BODY", "field": "name", "confidence": 0.5, "expectationId": "e3" } ] }"""
        val report = MockServerRestClient.formatDriftReport(body)
        assertFalse(report.empty)
        assertTrue(report.report.contains("expected — / actual —"), report.report)
    }

    @Test
    fun `formatDriftReport handles a non-json body as non-empty raw passthrough`() {
        val report = MockServerRestClient.formatDriftReport("not json at all")
        assertFalse(report.empty)
        assertTrue(report.report.contains("not json at all"))
    }

    @Test
    fun `formatDriftReport treats a blank body as empty`() {
        val report = MockServerRestClient.formatDriftReport("   ")
        assertTrue(report.empty)
        assertEquals(0, report.count)
    }

    // --- OpenAPI body branch --------------------------------------------

    @Test
    fun `openapi body embeds a JSON spec as an object payload`() {
        val spec = """{ "openapi": "3.0.0", "info": { "title": "t", "version": "1" } }"""
        val body = MockServerRestClient.buildOpenApiBody(spec)
        val root = JsonParser.parseString(body).asJsonObject
        val payload = root.get("specUrlOrPayload")
        assertTrue(payload.isJsonObject, "JSON spec should be embedded as an object, was: $payload")
        assertEquals("3.0.0", payload.asJsonObject.get("openapi").asString)
    }

    @Test
    fun `openapi body sends a YAML spec as a string payload`() {
        val spec = "openapi: 3.0.0\ninfo:\n  title: t\n  version: '1'\n"
        val body = MockServerRestClient.buildOpenApiBody(spec)
        val payload = JsonParser.parseString(body).asJsonObject.get("specUrlOrPayload")
        assertTrue(payload.isJsonPrimitive && payload.asJsonPrimitive.isString)
        assertEquals(spec, payload.asString)
    }

    @Test
    fun `openapi body treats a bare JSON string as a string payload (not embedded)`() {
        // JsonParser would happily parse "\"hello\"" as a primitive; ensure only
        // objects/arrays are embedded so an accidental bare value is sent raw.
        val spec = "\"just a string\""
        val payload = JsonParser.parseString(MockServerRestClient.buildOpenApiBody(spec))
            .asJsonObject.get("specUrlOrPayload")
        assertTrue(payload.isJsonPrimitive && payload.asJsonPrimitive.isString)
        assertEquals(spec, payload.asString)
    }

    @Test
    fun `generate from openapi PUTs to openapi endpoint with json content type`() {
        val req = MockServerRestClient.buildGenerateFromOpenApiRequest("http://localhost:1080", "{}")
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/openapi", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""))
    }

    // --- response helpers -----------------------------------------------

    @Test
    fun `empty body is detected as empty`() {
        assertTrue(MockServerRestClient.isEmptyExpectationsBody(""))
        assertTrue(MockServerRestClient.isEmptyExpectationsBody("   \n  "))
    }

    @Test
    fun `empty json array is detected as empty`() {
        assertTrue(MockServerRestClient.isEmptyExpectationsBody("[]"))
        assertTrue(MockServerRestClient.isEmptyExpectationsBody("  [ ]  "))
    }

    @Test
    fun `non-empty array is not empty`() {
        assertFalse(MockServerRestClient.isEmptyExpectationsBody("""[ { "httpRequest": {} } ]"""))
    }

    @Test
    fun `non-json non-blank body is not treated as empty`() {
        assertFalse(MockServerRestClient.isEmptyExpectationsBody("some Java DSL text"))
    }

    @Test
    fun `pretty print formats valid json`() {
        val pretty = MockServerRestClient.prettyPrintJson("""[{"a":1}]""")
        assertTrue(pretty.contains("\n"), "expected multi-line pretty output, was: $pretty")
        // Round-trips to the same structure.
        assertEquals(
            JsonParser.parseString("""[{"a":1}]"""),
            JsonParser.parseString(pretty)
        )
    }

    @Test
    fun `pretty print returns non-json input unchanged`() {
        val raw = "not json at all"
        assertEquals(raw, MockServerRestClient.prettyPrintJson(raw))
    }

    @Test
    fun `result ok reflects 2xx status`() {
        assertTrue(MockServerRestClient.Result(200, "").ok)
        assertTrue(MockServerRestClient.Result(201, "").ok)
        assertFalse(MockServerRestClient.Result(404, "").ok)
        assertFalse(MockServerRestClient.Result(500, "").ok)
    }

    @Test
    fun `looksLikeOpenApiSpec detects json and yaml specs`() {
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("""{ "openapi": "3.0.0", "paths": {} }"""))
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("""{ "swagger": "2.0", "paths": {} }"""))
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("openapi: 3.0.0\npaths: {}"))
        assertTrue(MockServerRestClient.looksLikeOpenApiSpec("swagger: \"2.0\"\npaths: {}"))
    }

    @Test
    fun `looksLikeOpenApiSpec rejects expectations and junk`() {
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("""{ "httpRequest": {}, "httpResponse": {} }"""))
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("""[ { "httpResponse": {} } ]"""))
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec(""))
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("just some text"))
        // a value merely containing the substring is not a top-level key
        assertFalse(MockServerRestClient.looksLikeOpenApiSpec("""{ "note": "openapi is great" }"""))
    }

    // --- parseRequestSpec -----------------------------------------------

    @Test
    fun `parseRequestSpec reads method path headers and body`() {
        val spec = MockServerRestClient.parseRequestSpec(
            """{ "method": "POST", "path": "/api/x", "headers": { "X-A": "1", "X-B": "2" }, "body": "hi" }"""
        )
        assertEquals("POST", spec.method)
        assertEquals("/api/x", spec.path)
        assertEquals(mapOf("X-A" to "1", "X-B" to "2"), spec.headers)
        assertEquals("hi", spec.body)
    }

    @Test
    fun `parseRequestSpec defaults headers and body when omitted`() {
        val spec = MockServerRestClient.parseRequestSpec("""{ "method": "GET", "path": "/" }""")
        assertEquals("GET", spec.method)
        assertEquals("/", spec.path)
        assertTrue(spec.headers.isEmpty())
        assertNull(spec.body)
    }

    @Test
    fun `parseRequestSpec rejects a missing method`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "path": "/api/x" }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a blank method`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "method": "  ", "path": "/api/x" }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a missing path`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "method": "GET" }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a non-string header value`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""{ "method": "GET", "path": "/", "headers": { "X-A": 1 } }""")
        }
    }

    @Test
    fun `parseRequestSpec rejects a non-object input`() {
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("""[ { "method": "GET", "path": "/" } ]""")
        }
        assertThrows<IllegalArgumentException> {
            MockServerRestClient.parseRequestSpec("not json")
        }
    }

    // --- buildScratchRequest --------------------------------------------

    @Test
    fun `buildScratchRequest targets base plus path with the given method`() {
        val spec = MockServerRestClient.RequestSpec("DELETE", "/api/x")
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals("DELETE", req.method())
        assertEquals("http://localhost:1080/api/x", req.uri().toString())
    }

    @Test
    fun `buildScratchRequest sets each header`() {
        val spec = MockServerRestClient.RequestSpec("GET", "/", mapOf("X-A" to "1", "Accept" to "application/json"))
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals("1", req.headers().firstValue("X-A").orElse(""))
        assertEquals("application/json", req.headers().firstValue("Accept").orElse(""))
    }

    @Test
    fun `buildScratchRequest sends the body when present`() {
        val spec = MockServerRestClient.RequestSpec("POST", "/api/x", body = """{"a":1}""")
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals("POST", req.method())
        assertEquals("""{"a":1}""", bodyOf(req))
    }

    @Test
    fun `buildScratchRequest uses no body for a body-less spec`() {
        val spec = MockServerRestClient.RequestSpec("GET", "/")
        val req = MockServerRestClient.buildScratchRequest("http://localhost:1080", spec)
        assertEquals(0L, req.bodyPublisher().orElseThrow().contentLength())
    }

    @Test
    fun `isJsonObjectOrArray distinguishes shapes`() {
        assertTrue(MockServerRestClient.isJsonObjectOrArray("""{ "a": 1 }"""))
        assertTrue(MockServerRestClient.isJsonObjectOrArray("""[ 1, 2 ]"""))
        assertFalse(MockServerRestClient.isJsonObjectOrArray("not json"))
        assertFalse(MockServerRestClient.isJsonObjectOrArray("42"))
        assertFalse(MockServerRestClient.isJsonObjectOrArray("openapi: 3.0.0"))
    }

    // --- distributed-trace correlation ----------------------------------

    @Test
    fun `retrieve requests PUTs to retrieve endpoint for requests as json`() {
        val req = MockServerRestClient.buildRetrieveRequestsRequest("http://localhost:1080")
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/retrieve?type=requests&format=json", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Accept").orElse(""))
        assertEquals(0L, req.bodyPublisher().orElseThrow().contentLength())
    }

    @Test
    fun `extractTraceId reads the trace id from a full traceparent`() {
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MockServerRestClient.extractTraceId(traceparent))
    }

    @Test
    fun `extractTraceId accepts a bare 32-hex trace id`() {
        assertEquals(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            MockServerRestClient.extractTraceId("4bf92f3577b34da6a3ce929d0e0e4736")
        )
    }

    @Test
    fun `extractTraceId lower-cases an uppercase trace id`() {
        assertEquals(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            MockServerRestClient.extractTraceId("4BF92F3577B34DA6A3CE929D0E0E4736")
        )
        assertEquals(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            MockServerRestClient.extractTraceId("00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01")
        )
    }

    @Test
    fun `extractTraceId returns null for junk`() {
        assertNull(MockServerRestClient.extractTraceId("not-a-trace"))
        assertNull(MockServerRestClient.extractTraceId(""))
        assertNull(MockServerRestClient.extractTraceId("4bf92f3577b34da6a3ce929d0e0e47")) // too short
        assertNull(MockServerRestClient.extractTraceId("zzf92f3577b34da6a3ce929d0e0e4736")) // non-hex
    }

    @Test
    fun `buildTraceUrl substitutes the traceId placeholder`() {
        assertEquals(
            "http://localhost:16686/trace/4bf92f3577b34da6a3ce929d0e0e4736",
            MockServerRestClient.buildTraceUrl(
                "http://localhost:16686/trace/{traceId}",
                "4bf92f3577b34da6a3ce929d0e0e4736"
            )
        )
    }

    @Test
    fun `buildTraceUrl substitutes every traceId occurrence`() {
        assertEquals(
            "https://g/explore?traceId=abc123&q=abc123",
            MockServerRestClient.buildTraceUrl("https://g/explore?traceId={traceId}&q={traceId}", "abc123")
        )
    }

    @Test
    fun `buildTraceUrl appends the trace id when the template has no placeholder`() {
        assertEquals(
            "http://localhost:16686/trace/abc",
            MockServerRestClient.buildTraceUrl("http://localhost:16686/trace/", "abc")
        )
    }

    @Test
    fun `buildTraceUrl URL-encodes the trace id`() {
        assertEquals(
            "http://x/a%20b%2Fc",
            MockServerRestClient.buildTraceUrl("http://x/{traceId}", "a b/c")
        )
    }

    @Test
    fun `buildTraceUrl returns null for a blank template`() {
        assertNull(MockServerRestClient.buildTraceUrl("", "abc"))
        assertNull(MockServerRestClient.buildTraceUrl("   ", "abc"))
    }

    @Test
    fun `filterRequestsByTrace keeps only requests with a matching traceparent (object-map headers, the real server shape)`() {
        // MockServer's retrieve?type=requests serializes headers as a JSON object
        // keyed by header name — { "traceparent": ["..."] } — NOT an array.
        val requests = """
            [
              {
                "method": "GET",
                "path": "/a",
                "headers": {
                  "Host": ["localhost"],
                  "traceparent": ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"]
                }
              },
              {
                "method": "GET",
                "path": "/b",
                "headers": {
                  "traceparent": ["00-00000000000000000000000000000000-0000000000000000-00"]
                }
              }
            ]
        """.trimIndent()

        val result = MockServerRestClient.filterRequestsByTrace(requests, "4bf92f3577b34da6a3ce929d0e0e4736")
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", result.traceId)
        val matches = JsonParser.parseString(result.matchesJson).asJsonArray
        assertEquals(1, matches.size())
        assertEquals("/a", matches[0].asJsonObject.get("path").asString)
    }

    @Test
    fun `filterRequestsByTrace matches the traceparent header name case-insensitively (object-map)`() {
        val requests = """
            [
              { "path": "/a", "headers": { "TraceParent": ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"] } }
            ]
        """.trimIndent()

        val result = MockServerRestClient.filterRequestsByTrace(
            requests,
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        )
        val matches = JsonParser.parseString(result.matchesJson).asJsonArray
        assertEquals(1, matches.size())
    }

    @Test
    fun `filterRequestsByTrace also accepts the array-of-name-values header form (defensive fallback)`() {
        val requests = """
            [
              { "path": "/a", "headers": [ { "name": "traceparent", "values": ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"] } ] }
            ]
        """.trimIndent()
        val result = MockServerRestClient.filterRequestsByTrace(requests, "4bf92f3577b34da6a3ce929d0e0e4736")
        val matches = JsonParser.parseString(result.matchesJson).asJsonArray
        assertEquals(1, matches.size())
    }

    @Test
    fun `filterRequestsByTrace returns null traceId and empty array for junk input`() {
        val requests = """[ { "path": "/a", "headers": [] } ]"""
        val result = MockServerRestClient.filterRequestsByTrace(requests, "not-a-trace")
        assertNull(result.traceId)
        assertEquals("[]", result.matchesJson)
    }

    @Test
    fun `filterRequestsByTrace is defensive against missing or non-array headers`() {
        val requests = """
            [
              { "path": "/a" },
              { "path": "/b", "headers": "oops" },
              { "path": "/c", "headers": [ { "name": "X" } ] }
            ]
        """.trimIndent()
        val result = MockServerRestClient.filterRequestsByTrace(requests, "4bf92f3577b34da6a3ce929d0e0e4736")
        assertEquals("[]", result.matchesJson)
    }

    @Test
    fun `filterRequestsByTrace returns empty array when the body is not a JSON array`() {
        val result = MockServerRestClient.filterRequestsByTrace("""{ "not": "an array" }""", "4bf92f3577b34da6a3ce929d0e0e4736")
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", result.traceId)
        assertEquals("[]", result.matchesJson)
    }

    // --- debugMismatch / match analysis ---------------------------------

    @Test
    fun `debugMismatch PUTs the request definition json to the debugMismatch endpoint`() {
        val definition = """{"method":"GET","path":"/a"}"""
        val req = MockServerRestClient.buildDebugMismatchRequest("http://localhost:1080", definition)
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/debugMismatch", req.uri().toString())
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""))
        assertEquals(definition, bodyOf(req))
    }

    @Test
    fun `requestSpecToDefinitionJson maps headers to the object-map form and omits empty parts`() {
        val full = MockServerRestClient.requestSpecToDefinitionJson(
            MockServerRestClient.RequestSpec("POST", "/api/x", linkedMapOf("Accept" to "application/json"), "hi")
        )
        val obj = JsonParser.parseString(full).asJsonObject
        assertEquals("POST", obj.get("method").asString)
        assertEquals("/api/x", obj.get("path").asString)
        assertEquals("application/json", obj.getAsJsonObject("headers").getAsJsonArray("Accept").get(0).asString)
        assertEquals("hi", obj.get("body").asString)

        val minimal = JsonParser.parseString(
            MockServerRestClient.requestSpecToDefinitionJson(MockServerRestClient.RequestSpec("GET", "/a"))
        ).asJsonObject
        assertFalse(minimal.has("headers"))
        assertFalse(minimal.has("body"))
    }

    @Test
    fun `parseMatchAnalysis reports a match when any result matches`() {
        val body = """{"totalExpectations":2,"results":[{"expectationId":"e1","matches":false},{"expectationId":"e2","matches":true}]}"""
        val analysis = MockServerRestClient.parseMatchAnalysis(body)
        assertTrue(analysis.matched)
        assertEquals("e2", analysis.expectationId)
        assertFalse(analysis.noExpectations)
    }

    @Test
    fun `parseMatchAnalysis surfaces the closest miss with its differences`() {
        val body = """
            {"totalExpectations":1,
             "results":[{"expectationId":"e1","matches":false,"differences":{"path":["expected /a but was /b"]}}],
             "closestMatch":{"expectationId":"e1","matchedFields":4,"totalFields":5}}
        """.trimIndent()
        val analysis = MockServerRestClient.parseMatchAnalysis(body)
        assertFalse(analysis.matched)
        assertEquals("e1", analysis.expectationId)
        assertEquals(4, analysis.matchedFields)
        assertEquals(listOf("expected /a but was /b"), analysis.differences["path"])
    }

    @Test
    fun `parseMatchAnalysis flags noExpectations when the server has none`() {
        val analysis = MockServerRestClient.parseMatchAnalysis("""{"totalExpectations":0,"results":[]}""")
        assertFalse(analysis.matched)
        assertTrue(analysis.noExpectations)
    }

    @Test
    fun `formatMatchAnalysis renders matched, no-expectations, and nearest-miss summaries`() {
        assertTrue(
            MockServerRestClient.formatMatchAnalysis(
                MockServerRestClient.MatchAnalysis(true, "e2", null, null, emptyMap(), false)
            ).contains("MATCHED")
        )
        assertTrue(
            MockServerRestClient.formatMatchAnalysis(
                MockServerRestClient.MatchAnalysis(false, null, null, null, emptyMap(), true)
            ).contains("no registered expectations")
        )
        val miss = MockServerRestClient.formatMatchAnalysis(
            MockServerRestClient.MatchAnalysis(false, "e1", 4, 5, mapOf("path" to listOf("expected /a but was /b")), false)
        )
        assertTrue(miss.contains("NOT MATCHED"))
        assertTrue(miss.contains("Closest: expectation e1"))
        assertTrue(miss.contains("path: expected /a but was /b"))
    }

    // --- verify / clear -------------------------------------------------

    @Test
    fun `verify wraps the request definition in httpRequest and times atLeast one`() {
        val req = MockServerRestClient.buildVerifyRequest("http://localhost:1080", """{"path":"/a"}""")
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/verify", req.uri().toString())
        val obj = JsonParser.parseString(bodyOf(req)).asJsonObject
        assertEquals("/a", obj.getAsJsonObject("httpRequest").get("path").asString)
        assertEquals(1, obj.getAsJsonObject("times").get("atLeast").asInt)
        // atMost -1 = no upper bound; an absent/0 atMost would make the server always 406.
        assertEquals(-1, obj.getAsJsonObject("times").get("atMost").asInt)
    }

    @Test
    fun `clear expectations PUTs the request definition to clear with type expectations`() {
        val definition = """{"path":"/a"}"""
        val req = MockServerRestClient.buildClearExpectationsRequest("http://localhost:1080", definition)
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/clear?type=expectations", req.uri().toString())
        assertEquals(definition, bodyOf(req))
    }

    @Test
    fun `extractRequestDefinitions pulls each httpRequest and skips request-less expectations`() {
        val text = """
            [
              { "httpRequest": { "method": "GET", "path": "/a" }, "httpResponse": { "statusCode": 200 } },
              { "httpResponse": { "statusCode": 204 } },
              { "httpRequest": { "path": "/b" } }
            ]
        """.trimIndent()
        val defs = MockServerRestClient.extractRequestDefinitions(text)
        assertEquals(2, defs.size)
        assertEquals("/a", JsonParser.parseString(defs[0]).asJsonObject.get("path").asString)
        assertEquals("/b", JsonParser.parseString(defs[1]).asJsonObject.get("path").asString)
    }

    @Test
    fun `extractRequestDefinitions accepts a single expectation object`() {
        val defs = MockServerRestClient.extractRequestDefinitions("""{ "httpRequest": { "path": "/a" }, "httpResponse": {} }""")
        assertEquals(1, defs.size)
        assertEquals("/a", JsonParser.parseString(defs[0]).asJsonObject.get("path").asString)
    }

    @Test
    fun `extractRequestDefinitions returns empty for non-json or request-less input`() {
        assertTrue(MockServerRestClient.extractRequestDefinitions("not json").isEmpty())
        assertTrue(MockServerRestClient.extractRequestDefinitions("""[{ "httpResponse": {} }]""").isEmpty())
    }
}
