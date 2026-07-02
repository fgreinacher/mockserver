// Minimal REST client for talking to a running MockServer instance from the
// editor. Kept free of the `vscode` API so it can be unit-tested directly, and
// it takes an injectable `fetch` so tests can run without a live server.

import { parse as parseJsonc, ParseError, printParseErrorCode } from "jsonc-parser";

export type FetchLike = (
    input: string,
    init?: {
        method?: string;
        headers?: Record<string, string>;
        body?: string | Uint8Array;
    }
) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>;

export function buildBaseUrl(port: number): string {
    return `http://localhost:${port}`;
}

/**
 * Build the HTML document for the in-editor dashboard webview: a full-size
 * `<iframe>` pointing at the running server's dashboard. VS Code webviews block
 * framing by default, so the document carries a Content-Security-Policy that
 * explicitly allows framing localhost (`frame-src http://localhost:* http://127.0.0.1:*`)
 * and inline styles for the full-bleed iframe. Kept free of the `vscode` API so
 * it can be unit-tested directly.
 */
export function buildDashboardWebviewHtml(dashboardUrl: string): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; frame-src http://localhost:* http://127.0.0.1:*;">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MockServer Dashboard</title>
    <style>
        html, body { margin: 0; padding: 0; height: 100%; }
        iframe { width: 100%; height: 100vh; border: none; }
    </style>
</head>
<body>
    <iframe src="${dashboardUrl}" title="MockServer Dashboard"></iframe>
</body>
</html>`;
}

/**
 * A scratch request spec parsed from a `*.mockserver-request.json` file: a small
 * ad-hoc HTTP request a developer fires at the running mock from the editor.
 * `method` and `path` are required; `headers` and `body` are optional.
 */
export interface RequestSpec {
    method: string;
    path: string;
    headers?: Record<string, string>;
    body?: string;
}

/** A scratch request's response: the HTTP status and the (possibly empty) body. */
export interface ScratchResponse {
    status: number;
    body: string;
}

/**
 * The outcome of analysing a scratch request against the server's registered
 * expectations via `PUT /mockserver/debugMismatch`: whether the request matched a
 * registered expectation, and — when it did not — the nearest-miss summary (the
 * closest expectation by matched-field count, with its per-field differences).
 */
export interface MatchAnalysis {
    /** True when the request matched at least one registered expectation. */
    matched: boolean;
    /** The id of the matched expectation (when `matched`), or the closest one. */
    expectationId?: string;
    /** Matched field count of the matched/closest expectation, when known. */
    matchedFields?: number;
    /** Total field count compared, when known. */
    totalFields?: number;
    /**
     * Per-field difference messages from the closest non-matching expectation,
     * keyed by field name (e.g. `path`, `method`, `body`). Empty when matched or
     * when the server returned no differences.
     */
    differences: Record<string, string[]>;
    /** True when the server has no registered expectations to compare against. */
    noExpectations: boolean;
}

/**
 * Parse a scratch request file's text into a {@link RequestSpec}. Throws a clear
 * error if the JSON is invalid, the top level is not an object, or `method`/`path`
 * are missing or not strings.
 */
export function parseRequestSpec(text: string): RequestSpec {
    let parsed: unknown;
    try {
        parsed = JSON.parse(text);
    } catch (e) {
        throw new Error(`Not valid JSON: ${(e as Error).message}`);
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("Expected a request object with \"method\" and \"path\"");
    }
    const spec = parsed as Record<string, unknown>;
    if (typeof spec.method !== "string" || spec.method.trim().length === 0) {
        throw new Error('Request spec is missing a "method" (e.g. "GET")');
    }
    if (typeof spec.path !== "string" || spec.path.trim().length === 0) {
        throw new Error('Request spec is missing a "path" (e.g. "/api/x")');
    }
    if (spec.headers !== undefined) {
        if (typeof spec.headers !== "object" || spec.headers === null || Array.isArray(spec.headers)) {
            throw new Error('"headers" must be an object of header name to value');
        }
        for (const [name, value] of Object.entries(spec.headers as Record<string, unknown>)) {
            if (typeof value !== "string") {
                throw new Error(`"headers.${name}" must be a string`);
            }
        }
    }
    if (spec.body !== undefined && typeof spec.body !== "string") {
        throw new Error('"body" must be a string');
    }
    return {
        method: spec.method,
        path: spec.path,
        headers: spec.headers as Record<string, string> | undefined,
        body: spec.body as string | undefined,
    };
}

const OPENAPI_YAML_KEY = /^\s*["']?(openapi|swagger)["']?\s*:/im;

/**
 * Heuristic: does `text` look like an OpenAPI/Swagger specification? True when it
 * has a top-level `openapi` (3.x) or `swagger` (2.0) field, in either JSON or YAML.
 * Used to warn clearly when the wrong kind of file is submitted (e.g. an OpenAPI
 * spec sent to "load expectations", or an expectation file sent to "generate").
 */
export function looksLikeOpenApiSpec(text: string): boolean {
    try {
        const parsed = JSON.parse(text);
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
            return "openapi" in parsed || "swagger" in parsed;
        }
        return false; // valid JSON but an array/primitive — not a spec
    } catch {
        return OPENAPI_YAML_KEY.test(text); // not JSON → check for a YAML spec key
    }
}

/**
 * Fire a scratch request at the running server: send `spec.method spec.path`
 * (relative to `baseUrl`) with the spec's headers and body, and return the
 * response status and body text. Unlike the expectation helpers this does NOT
 * throw on a non-2xx status — the caller wants to see error responses too.
 */
export async function sendScratchRequest(
    baseUrl: string,
    spec: RequestSpec,
    fetchFn: FetchLike
): Promise<ScratchResponse> {
    const res = await fetchFn(`${baseUrl}${spec.path}`, {
        method: spec.method,
        headers: spec.headers,
        body: spec.body,
    });
    return { status: res.status, body: await res.text() };
}

/**
 * Convert a {@link RequestSpec} into the `httpRequest` definition shape MockServer's
 * `debugMismatch` endpoint deserialises: `{ method, path, headers, body }`. Headers
 * become the object-map form (`{ name: [value] }`) the server understands; an absent
 * header map / body is simply omitted.
 */
export function toRequestDefinition(spec: RequestSpec): Record<string, unknown> {
    const definition: Record<string, unknown> = { method: spec.method, path: spec.path };
    if (spec.headers && Object.keys(spec.headers).length > 0) {
        const headers: Record<string, string[]> = {};
        for (const [name, value] of Object.entries(spec.headers)) {
            headers[name] = [value];
        }
        definition.headers = headers;
    }
    if (spec.body !== undefined) {
        definition.body = spec.body;
    }
    return definition;
}

/**
 * Reduce a raw `debugMismatch` response body into a {@link MatchAnalysis}. The body
 * shape is `{ totalExpectations, results: [{ matches, differences, ... }], closestMatch? }`.
 * `matched` is true when any result matched; otherwise the closest non-matching
 * result supplies the nearest-miss differences. Defensive against missing fields.
 */
export function parseMatchAnalysis(body: string): MatchAnalysis {
    let parsed: { totalExpectations?: number; results?: unknown[]; closestMatch?: unknown };
    try {
        parsed = JSON.parse(body);
    } catch {
        return { matched: false, differences: {}, noExpectations: false };
    }
    const results = Array.isArray(parsed.results) ? parsed.results : [];
    // "No expectations" only when the server genuinely has none. When totalExpectations
    // is unknown, fall back to an empty results array; but a positive totalExpectations
    // with an empty results array (all truncated) is a miss, not "no expectations".
    const noExpectations =
        typeof parsed.totalExpectations === "number"
            ? parsed.totalExpectations === 0
            : results.length === 0;
    const matchedResult = results.find(
        (r) => r && typeof r === "object" && (r as Record<string, unknown>).matches === true
    ) as Record<string, unknown> | undefined;
    if (matchedResult) {
        return {
            matched: true,
            expectationId:
                typeof matchedResult.expectationId === "string" ? matchedResult.expectationId : undefined,
            matchedFields:
                typeof matchedResult.matchedFieldCount === "number"
                    ? matchedResult.matchedFieldCount
                    : undefined,
            totalFields:
                typeof matchedResult.totalFieldCount === "number"
                    ? matchedResult.totalFieldCount
                    : undefined,
            differences: {},
            noExpectations: false,
        };
    }
    // No match — surface the closest expectation's differences. The server reports
    // `closestMatch` with the id/counts; pull the matching result's `differences`.
    const closest = (parsed.closestMatch ?? {}) as Record<string, unknown>;
    const closestId = typeof closest.expectationId === "string" ? closest.expectationId : undefined;
    const closestResult = results.find(
        (r) =>
            r &&
            typeof r === "object" &&
            (r as Record<string, unknown>).expectationId === closestId
    ) as Record<string, unknown> | undefined;
    const differences: Record<string, string[]> = {};
    const rawDiffs = closestResult?.differences;
    if (rawDiffs && typeof rawDiffs === "object" && !Array.isArray(rawDiffs)) {
        for (const [field, value] of Object.entries(rawDiffs as Record<string, unknown>)) {
            if (Array.isArray(value)) {
                differences[field] = value.filter((v): v is string => typeof v === "string");
            }
        }
    }
    return {
        matched: false,
        expectationId: closestId,
        matchedFields: typeof closest.matchedFields === "number" ? closest.matchedFields : undefined,
        totalFields: typeof closest.totalFields === "number" ? closest.totalFields : undefined,
        differences,
        noExpectations,
    };
}

/**
 * Ask the server whether a request would match any registered expectation, and if
 * not why, via `PUT /mockserver/debugMismatch` (body = the request definition).
 * Returns a {@link MatchAnalysis}. Throws (with status + body) on a non-ok response
 * so a server without this endpoint surfaces clearly to the caller.
 */
export async function analyseMatch(
    baseUrl: string,
    spec: RequestSpec,
    fetchFn: FetchLike
): Promise<MatchAnalysis> {
    const res = await fetchFn(`${baseUrl}/mockserver/debugMismatch`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(toRequestDefinition(spec)),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    return parseMatchAnalysis(await res.text());
}

/**
 * Render a {@link MatchAnalysis} as a readable one-or-more-line summary for the
 * scratch-request response view: a clear matched/not-matched headline, and — on a
 * miss — the closest expectation and its per-field differences (the "nearest miss").
 */
export function formatMatchAnalysis(analysis: MatchAnalysis): string {
    if (analysis.matched) {
        const id = analysis.expectationId ? ` (expectation ${analysis.expectationId})` : "";
        return `✓ Matched a registered expectation${id}.`;
    }
    if (analysis.noExpectations) {
        return "✗ No registered expectations to match against.";
    }
    const lines: string[] = ["✗ Did not match any registered expectation."];
    if (analysis.expectationId) {
        const fields =
            typeof analysis.matchedFields === "number" && typeof analysis.totalFields === "number"
                ? ` (${analysis.matchedFields}/${analysis.totalFields} fields matched)`
                : "";
        lines.push(`Closest: expectation ${analysis.expectationId}${fields}`);
    }
    for (const [field, messages] of Object.entries(analysis.differences)) {
        for (const message of messages) {
            lines.push(`  • ${field}: ${message}`);
        }
    }
    return lines.join("\n");
}

/**
 * Verify, via `PUT /mockserver/verify`, that the server received a request matching
 * `requestDefinition` at least once (`atLeast` 1). Returns `{ verified, reason }`:
 * a 202 means verified; a 406 carries the human-readable failure reason in its body.
 * Throws only on an unexpected (non-202/406) status.
 */
export async function verifyRequestReceived(
    baseUrl: string,
    requestDefinition: Record<string, unknown>,
    fetchFn: FetchLike
): Promise<{ verified: boolean; reason: string }> {
    const res = await fetchFn(`${baseUrl}/mockserver/verify`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            httpRequest: requestDefinition,
            // atMost -1 = no upper bound. The server's VerificationTimesDTO.atMost is a
            // primitive int defaulting to 0 when omitted, which makes `times <= atMost`
            // false for any real receipt — so an absent atMost would always 406.
            times: { atLeast: 1, atMost: -1 },
        }),
    });
    if (res.status === 202) {
        return { verified: true, reason: "" };
    }
    const body = await res.text();
    if (res.status === 406) {
        return { verified: false, reason: body };
    }
    throw new Error(`MockServer returned ${res.status}: ${body}`);
}

/**
 * Extract the request definitions (`httpRequest`) from an expectation file's text,
 * for verifying that each was received. Skips expectations whose request is an
 * `openAPI`/`httpRequest`-less control definition. Returns `[]` when none are found.
 */
export function extractRequestDefinitions(text: string): Record<string, unknown>[] {
    const expectations = parseExpectations(text);
    const definitions: Record<string, unknown>[] = [];
    for (const expectation of expectations) {
        if (expectation && typeof expectation === "object") {
            const httpRequest = (expectation as Record<string, unknown>).httpRequest;
            if (httpRequest && typeof httpRequest === "object" && !Array.isArray(httpRequest)) {
                definitions.push(httpRequest as Record<string, unknown>);
            }
        }
    }
    return definitions;
}

/**
 * Clear from the running server every expectation declared in `text`, via
 * `PUT /mockserver/clear?type=expectations` with each expectation's `httpRequest`
 * as the matcher. Returns the number of clear calls issued (one per declared
 * request). Throws (with status + body) on the first non-ok response.
 */
export async function clearExpectations(
    baseUrl: string,
    text: string,
    fetchFn: FetchLike
): Promise<number> {
    const definitions = extractRequestDefinitions(text);
    for (const definition of definitions) {
        const res = await fetchFn(`${baseUrl}/mockserver/clear?type=expectations`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(definition),
        });
        if (!res.ok) {
            throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
        }
    }
    return definitions.length;
}

/**
 * Parse an expectation file's text into a list of expectations. A file may hold
 * a single expectation object or an array of them (initialization JSON).
 * Throws a clear error if the JSON is invalid or the shape is unexpected.
 */
export function parseExpectations(text: string): unknown[] {
    // Use a JSONC-tolerant parser so `*.mockserver.jsonc` files (comments and
    // trailing commas) load correctly. A naive comment-strip would corrupt `//`
    // inside string values such as URLs, so a real tokenizer is required.
    const errors: ParseError[] = [];
    const parsed: unknown = parseJsonc(text, errors, { allowTrailingComma: true });
    if (errors.length > 0) {
        const first = errors[0];
        throw new Error(
            `Not valid JSON: ${printParseErrorCode(first.error)} at offset ${first.offset}`
        );
    }
    if (Array.isArray(parsed)) {
        if (parsed.length === 0) {
            throw new Error("Expectation file is an empty array");
        }
        return parsed;
    }
    if (parsed && typeof parsed === "object") {
        return [parsed];
    }
    throw new Error("Expected an expectation object or an array of expectations");
}

/**
 * Load the file's expectation(s) into the running server via
 * `PUT /mockserver/expectation` (which accepts a single expectation or an array).
 * Returns the number of expectations sent.
 */
export async function loadExpectations(
    baseUrl: string,
    text: string,
    fetchFn: FetchLike
): Promise<number> {
    const expectations = parseExpectations(text);
    const res = await fetchFn(`${baseUrl}/mockserver/expectation`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(expectations),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    return expectations.length;
}

/**
 * Retrieve the expectations currently registered on the server, as pretty JSON,
 * via `PUT /mockserver/retrieve?type=active_expectations&format=json`.
 */
export async function retrieveActiveExpectations(
    baseUrl: string,
    fetchFn: FetchLike
): Promise<string> {
    const res = await fetchFn(
        `${baseUrl}/mockserver/retrieve?type=active_expectations&format=json`,
        { method: "PUT", headers: { Accept: "application/json" } }
    );
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return JSON.stringify(JSON.parse(body), null, 2) + "\n";
    } catch {
        return body; // return as-is if the server sent something unparseable
    }
}

/**
 * Generate MockServer expectations from an OpenAPI / Swagger specification via
 * `PUT /mockserver/openapi`. The spec text is sent inline as `specUrlOrPayload`:
 * a JSON spec is sent as a parsed object, anything else (YAML) as a string —
 * the server parses both. The 201 response IS the generated expectations array,
 * returned pretty-printed and ready to save as a `*.mockserver.json` file.
 */
export async function generateExpectationsFromOpenApi(
    baseUrl: string,
    specText: string,
    fetchFn: FetchLike
): Promise<string> {
    let specUrlOrPayload: unknown;
    try {
        // A JSON spec is sent as an object so the server treats it unambiguously
        // as an inline payload; YAML falls through and is sent as a string.
        specUrlOrPayload = JSON.parse(specText);
    } catch {
        specUrlOrPayload = specText;
    }
    const res = await fetchFn(`${baseUrl}/mockserver/openapi`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ specUrlOrPayload }),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return JSON.stringify(JSON.parse(body), null, 2) + "\n";
    } catch {
        return body;
    }
}

export interface RequestLog {
    /** The received request log, pretty-printed as JSON. */
    content: string;
    /** True when the server has not recorded any requests yet (`[]` or blank). */
    empty: boolean;
}

/**
 * Retrieve the log of requests the server has received, as pretty JSON, via
 * `PUT /mockserver/retrieve?type=requests&format=json`. Returns the content plus
 * whether it is empty (no requests recorded yet — `[]` or a blank body).
 */
export async function retrieveRequestLog(
    baseUrl: string,
    fetchFn: FetchLike
): Promise<RequestLog> {
    const res = await fetchFn(
        `${baseUrl}/mockserver/retrieve?type=requests&format=json`,
        { method: "PUT", headers: { Accept: "application/json" } }
    );
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        const parsed = JSON.parse(body);
        const empty = Array.isArray(parsed) && parsed.length === 0;
        return { content: JSON.stringify(parsed, null, 2) + "\n", empty };
    } catch {
        return { content: body, empty: body.trim().length === 0 };
    }
}

/**
 * Reset the running server via `PUT /mockserver/reset`, clearing all expectations
 * and the recorded request log. Throws (with status + body) on a non-ok response.
 */
export async function resetServer(baseUrl: string, fetchFn: FetchLike): Promise<void> {
    const res = await fetchFn(`${baseUrl}/mockserver/reset`, { method: "PUT" });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

export type RecordedFormat = "json" | "java";

export interface RecordedExpectations {
    /** Formatted content (pretty JSON, or Java DSL as the server emitted it). */
    content: string;
    /** True when the server has not recorded any expectations yet. */
    empty: boolean;
}

/**
 * Retrieve expectations generated from traffic the server has recorded while
 * proxying/forwarding, via `PUT /mockserver/retrieve?type=recorded_expectations`,
 * as either JSON (pretty-printed) or Java DSL — the "record real traffic into
 * code" flow. Returns the content plus whether it is empty (no recorded traffic).
 */
export async function retrieveRecordedExpectations(
    baseUrl: string,
    format: RecordedFormat,
    fetchFn: FetchLike
): Promise<RecordedExpectations> {
    const res = await fetchFn(
        `${baseUrl}/mockserver/retrieve?type=recorded_expectations&format=${format}`,
        { method: "PUT" }
    );
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    if (format === "java") {
        return { content: body, empty: body.trim().length === 0 };
    }
    // JSON: pretty-print and detect the empty-array case.
    try {
        const parsed = JSON.parse(body);
        const empty = Array.isArray(parsed) && parsed.length === 0;
        return { content: JSON.stringify(parsed, null, 2) + "\n", empty };
    } catch {
        return { content: body, empty: body.trim().length === 0 };
    }
}

export interface DriftReport {
    /** Number of drift records the server reported. */
    count: number;
    /** A readable, plain-text summary of the drift records. */
    report: string;
    /** True when the server reported no drift (count === 0). */
    empty: boolean;
}

/**
 * Format a single drift value for the report. The server may omit
 * `expectedValue`/`actualValue`, send a primitive, or send a nested object —
 * render objects/arrays compactly as JSON and show "—" when absent.
 */
function formatDriftValue(value: unknown): string {
    if (value === undefined || value === null) {
        return "—";
    }
    if (typeof value === "object") {
        try {
            return JSON.stringify(value);
        } catch {
            return String(value);
        }
    }
    return String(value);
}

/**
 * A single mock-drift record as reported by `GET /mockserver/drift`. The server
 * may omit `expectedValue`/`actualValue` (e.g. a field added or removed). All
 * other fields are always present.
 */
export interface DriftRecord {
    /** The id of the expectation whose stub has drifted from the real upstream. */
    expectationId: string;
    /** The kind of drift, e.g. `HEADER_CHANGED`, `STATUS`, `SCHEMA_FIELD_ADDED`. */
    driftType: string;
    /** The drifted field (a JSON path or response location), e.g. `$.status`. */
    field: string;
    /** The value the stub expectation declares (absent for some drift types). */
    expectedValue?: unknown;
    /** The value the real upstream returned (absent for some drift types). */
    actualValue?: unknown;
    /** The detector's confidence in the drift, 0..1 (1.0 = certain). */
    confidence: number;
    /** When the drift was observed, in epoch milliseconds. */
    epochTimeMs: number;
}

/**
 * Fetch the drift endpoint and return the parsed `{ count, drifts }` body, or
 * `undefined` when the body is not parseable JSON (so callers can fall back to
 * the raw text). Shared by {@link retrieveDrift} and {@link retrieveDriftRecords}.
 * Throws (with status + body) on a non-ok response.
 */
async function fetchDrift(
    baseUrl: string,
    fetchFn: FetchLike,
    limit?: number
): Promise<{ parsed?: { count?: number; drifts?: unknown[] }; body: string }> {
    const query = typeof limit === "number" ? `?limit=${limit}` : "";
    const res = await fetchFn(`${baseUrl}/mockserver/drift${query}`, {
        method: "GET",
        headers: { Accept: "application/json" },
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return { parsed: JSON.parse(body), body };
    } catch {
        return { parsed: undefined, body };
    }
}

/**
 * Retrieve the latest mock-drift records via `GET /mockserver/drift`. Drift is
 * recorded when the server proxies traffic to a real upstream and a matching
 * stub expectation differs structurally from the real response. Returns the
 * record count, a readable text summary, and whether it is empty (no drift).
 * `limit` (when provided) caps how many records the server returns.
 */
export async function retrieveDrift(
    baseUrl: string,
    fetchFn: FetchLike,
    limit?: number
): Promise<DriftReport> {
    const { parsed, body } = await fetchDrift(baseUrl, fetchFn, limit);
    if (parsed === undefined) {
        // Non-JSON or unexpected body — surface the raw text so nothing is lost.
        return { count: 0, report: body, empty: body.trim().length === 0 };
    }
    const drifts = Array.isArray(parsed.drifts) ? parsed.drifts : [];
    const count = typeof parsed.count === "number" ? parsed.count : drifts.length;
    const header = `MockServer drift report — ${count} record(s)`;
    if (count === 0 && drifts.length === 0) {
        return { count: 0, report: header, empty: true };
    }
    const lines = drifts.map((entry) => {
        const d = (entry ?? {}) as Record<string, unknown>;
        const expected = formatDriftValue(d.expectedValue);
        const actual = formatDriftValue(d.actualValue);
        return (
            `[${formatDriftValue(d.driftType)}] ${formatDriftValue(d.field)}: ` +
            `expected ${expected} / actual ${actual} ` +
            `(confidence ${formatDriftValue(d.confidence)}, expectation ${formatDriftValue(d.expectationId)})`
        );
    });
    return { count, report: [header, ...lines].join("\n") + "\n", empty: count === 0 };
}

/**
 * Retrieve the latest mock-drift records via `GET /mockserver/drift` as a
 * structured array of {@link DriftRecord}, for programmatic use (e.g. mapping to
 * editor diagnostics). Throws (with status + body) on a non-ok response; returns
 * `[]` when the server reports no drift or sends a non-JSON / unexpected body.
 * `limit` (when provided) caps how many records the server returns.
 */
export async function retrieveDriftRecords(
    baseUrl: string,
    fetchFn: FetchLike,
    limit?: number
): Promise<DriftRecord[]> {
    const { parsed } = await fetchDrift(baseUrl, fetchFn, limit);
    if (parsed === undefined || !Array.isArray(parsed.drifts)) {
        return [];
    }
    return parsed.drifts as DriftRecord[];
}

// A W3C `traceparent` header value: `version-traceId-parentId-flags`, e.g.
// `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`. The capture group is
// the 32-hex trace id. Case-insensitive: the spec lowercases hex, but be lenient.
const TRACEPARENT_RE = /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/i;
// A bare 32-hex trace id on its own.
const TRACE_ID_RE = /^[0-9a-f]{32}$/i;

/**
 * Extract the 32-hex W3C trace id (lowercased) from `input`. Accepts either a
 * full `traceparent` header value (`version-traceId-parentId-flags`) — in which
 * case the trace-id component is returned — or a bare 32-hex trace id, returned
 * as-is (lowercased). Returns `null` for anything else. Pure and `vscode`-free.
 */
export function extractTraceId(input: string): string | null {
    const trimmed = input.trim();
    const full = TRACEPARENT_RE.exec(trimmed);
    if (full) {
        return full[1].toLowerCase();
    }
    if (TRACE_ID_RE.test(trimmed)) {
        return trimmed.toLowerCase();
    }
    return null;
}

/**
 * Pull the `traceparent` header value(s) from a recorded request object. Handles
 * both header shapes MockServer may emit:
 * - the object-map form `{ "traceparent": ["00-..."] }` or `{ "traceparent": "00-..." }`,
 *   which is what the server actually emits for `retrieve?type=requests`, and
 * - the array form `[{ "name": "traceparent", "values": ["00-..."] }]`, kept as a
 *   defensive fallback for other serialisations.
 * Header-name matching is case-insensitive. Returns an empty array when no headers
 * (or no traceparent header) are present, or the request is not an object.
 */
function traceparentValues(request: unknown): string[] {
    if (!request || typeof request !== "object") {
        return [];
    }
    const headers = (request as Record<string, unknown>).headers;
    const values: string[] = [];
    const collect = (raw: unknown): void => {
        if (typeof raw === "string") {
            values.push(raw);
        } else if (Array.isArray(raw)) {
            for (const v of raw) {
                if (typeof v === "string") {
                    values.push(v);
                }
            }
        }
    };
    if (Array.isArray(headers)) {
        for (const entry of headers) {
            if (entry && typeof entry === "object") {
                const name = (entry as Record<string, unknown>).name;
                if (typeof name === "string" && name.toLowerCase() === "traceparent") {
                    collect((entry as Record<string, unknown>).values);
                }
            }
        }
    } else if (headers && typeof headers === "object") {
        for (const [name, raw] of Object.entries(headers as Record<string, unknown>)) {
            if (name.toLowerCase() === "traceparent") {
                collect(raw);
            }
        }
    }
    return values;
}

/**
 * True when `request` carries a `traceparent` header (case-insensitive name) whose
 * value, parsed as a W3C `traceparent`, has the given `traceId` (case-insensitive).
 * Defensive: missing/non-array headers and malformed values are simply skipped.
 */
export function requestMatchesTrace(request: unknown, traceId: string): boolean {
    const wanted = traceId.toLowerCase();
    return traceparentValues(request).some((value) => extractTraceId(value) === wanted);
}

/**
 * Filter a request-log JSON array (the body of
 * `PUT /mockserver/retrieve?type=requests&format=json`) down to the requests that
 * belong to one distributed trace. `input` may be a bare 32-hex trace id or a full
 * `traceparent` value. Returns the resolved `traceId` (lowercased, or `null` when
 * `input` is neither a trace id nor a traceparent) and the matching requests
 * (empty when the trace id is unresolvable or the JSON is not an array). Pure and
 * `vscode`-free so it can be unit-tested directly.
 */
export function filterRequestsByTrace(
    requestsJsonText: string,
    input: string
): { traceId: string | null; matches: unknown[] } {
    const traceId = extractTraceId(input);
    if (traceId === null) {
        return { traceId: null, matches: [] };
    }
    let parsed: unknown;
    try {
        parsed = JSON.parse(requestsJsonText);
    } catch {
        return { traceId, matches: [] };
    }
    if (!Array.isArray(parsed)) {
        return { traceId, matches: [] };
    }
    return { traceId, matches: parsed.filter((req) => requestMatchesTrace(req, traceId)) };
}

/**
 * Build a trace-backend URL for `traceId` from a user-configured `template`, for
 * opening the correlated OpenTelemetry trace in a browser (e.g. Jaeger/Tempo/Grafana).
 * The first `{traceId}` placeholder is substituted (URL-encoded); when the template
 * has no placeholder the (encoded) trace id is appended. Returns `null` when the
 * template is blank — the caller should then degrade to showing the bare trace id.
 * Pure and `vscode`-free so it can be unit-tested directly.
 */
export function buildTraceUrl(template: string, traceId: string): string | null {
    const trimmed = template.trim();
    if (trimmed.length === 0) {
        return null;
    }
    const encoded = encodeURIComponent(traceId);
    if (trimmed.includes("{traceId}")) {
        return trimmed.split("{traceId}").join(encoded);
    }
    return trimmed + encoded;
}

/** A drift mapped to a position in an open expectation file, ready to surface as a diagnostic. */
export interface DriftDiagnostic {
    /** Zero-based line in the document the diagnostic attaches to (0 when no match found). */
    line: number;
    /** Human-readable message describing the drift. */
    message: string;
    /** Severity bucket, mapped from the drift type / confidence. */
    severity: "error" | "warning" | "info";
    /** The raw drift record, so a quick-fix can map it back to the JSON node. */
    record: DriftRecord;
}

/**
 * Map drift severity from a record. The mapping (a heuristic — drift is advisory):
 * - `error`   — a status-code drift (`driftType` contains `STATUS`), a removed
 *               schema field (`SCHEMA_FIELD_REMOVED`), or a certain drift
 *               (`confidence >= 1.0`): the stub is very likely wrong.
 * - `warning` — a newly added schema field (`SCHEMA_FIELD_ADDED`): the upstream
 *               grew a field the stub doesn't model.
 * - `info`    — everything else (value changes, low-confidence drift).
 */
function driftSeverity(record: DriftRecord): "error" | "warning" | "info" {
    const type = record.driftType ?? "";
    if (type.includes("STATUS") || type.startsWith("SCHEMA_FIELD_REMOVED") || record.confidence >= 1.0) {
        return "error";
    }
    if (type === "SCHEMA_FIELD_ADDED") {
        return "warning";
    }
    return "info";
}

/**
 * Find the zero-based line in `docText` of the expectation whose `"id"` equals
 * `expectationId`. Heuristic: scan for a line that contains both an `"id"` key
 * and the id string as a quoted value. Returns -1 when no such line is found.
 */
function findExpectationLine(docText: string, expectationId: string): number {
    if (!expectationId) {
        return -1;
    }
    const quotedId = `"${expectationId}"`;
    const lines = docText.split("\n");
    for (let i = 0; i < lines.length; i++) {
        if (lines[i].includes('"id"') && lines[i].includes(quotedId)) {
            return i;
        }
    }
    return -1;
}

/**
 * Build the URL for uploading a WASM custom-rule module to the running server:
 * `PUT /mockserver/wasm/modules?name=<moduleName>`. The module name is URL-encoded
 * so names with spaces or reserved characters are sent safely.
 */
export function buildWasmUploadUrl(baseUrl: string, name: string): string {
    return `${baseUrl}/mockserver/wasm/modules?name=${encodeURIComponent(name)}`;
}

/**
 * Upload a compiled `.wasm` custom-rule module to the running server via
 * `PUT /mockserver/wasm/modules?name=<name>`, sending the raw module bytes with a
 * `Content-Type: application/octet-stream` header. Once uploaded the module can be
 * referenced by name in an expectation body matcher (`{ "type": "WASM", "moduleName": "<name>" }`).
 * Throws (with status + body) on a non-ok response — so the server's
 * "WASM support is disabled" 403 message surfaces verbatim.
 */
export async function uploadWasmModule(
    baseUrl: string,
    name: string,
    bytes: Uint8Array,
    fetchFn: FetchLike
): Promise<void> {
    const res = await fetchFn(buildWasmUploadUrl(baseUrl, name), {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream" },
        body: bytes,
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

/**
 * Retrieve the names of the WASM custom-rule modules currently registered on the
 * server, as pretty JSON, via `GET /mockserver/wasm/modules`. Throws (with status +
 * body) on a non-ok response; returns the raw body if it is not parseable JSON.
 */
export async function retrieveWasmModules(baseUrl: string, fetchFn: FetchLike): Promise<string> {
    const res = await fetchFn(`${baseUrl}/mockserver/wasm/modules`, {
        method: "GET",
        headers: { Accept: "application/json" },
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const body = await res.text();
    try {
        return JSON.stringify(JSON.parse(body), null, 2) + "\n";
    } catch {
        return body;
    }
}

/** A sample request to test a WASM module against (all fields optional). */
export interface WasmTestRequest {
    method?: string;
    path?: string;
    queryStringParameters?: Record<string, string[]>;
    headers?: Record<string, string[]>;
    cookies?: Record<string, string>;
    body?: string;
}

/**
 * Test a compiled `.wasm` custom-rule module against a sample request via
 * `POST /mockserver/wasm/test`, without uploading it or creating an expectation.
 * The module bytes are base64-encoded into the `module` field; the sample
 * `request` (method / path / queryStringParameters / headers / cookies / body,
 * all optional) is sent as envelope-v2 JSON. Returns `true`/`false` from the
 * server's `{ "matched": <bool> }` response. Throws (with status + body) on a
 * non-ok response — so the server's "WASM support is disabled" 403 surfaces
 * verbatim. Matching itself is fail-closed server-side: an invalid module
 * reports `matched: false` rather than an error.
 */
export async function testWasmModule(
    baseUrl: string,
    bytes: Uint8Array,
    request: WasmTestRequest,
    fetchFn: FetchLike
): Promise<boolean> {
    let binary = "";
    for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    const base64 =
        typeof btoa === "function"
            ? btoa(binary)
            : Buffer.from(bytes).toString("base64");
    const res = await fetchFn(`${baseUrl}/mockserver/wasm/test`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ module: base64, request }),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const parsed = JSON.parse(await res.text()) as { matched?: boolean };
    return parsed.matched === true;
}

/**
 * Map drift records to {@link DriftDiagnostic}s anchored to the matching
 * expectation lines in `docText`. For each record, the line is found by scanning
 * for a JSON `"id"` line whose value equals `record.expectationId`; when no line
 * matches, the diagnostic attaches to line 0 so it is still surfaced. The message
 * reads `Drift [<driftType>] <field>: expected <e> / actual <a> (confidence <c>)`,
 * with absent values shown as `—`. Pure and `vscode`-free so it can be unit-tested.
 */
export function mapDriftToDiagnostics(records: DriftRecord[], docText: string): DriftDiagnostic[] {
    return records.map((record) => {
        const found = findExpectationLine(docText, record.expectationId);
        const expected = formatDriftValue(record.expectedValue);
        const actual = formatDriftValue(record.actualValue);
        const message =
            `Drift [${record.driftType}] ${record.field}: ` +
            `expected ${expected} / actual ${actual} ` +
            `(confidence ${formatDriftValue(record.confidence)})`;
        return {
            line: found >= 0 ? found : 0,
            message,
            severity: driftSeverity(record),
            record,
        };
    });
}

/**
 * Describe the suggested fix for a drift record as a one-line, human-readable
 * action title for a quick-fix lightbulb. Pure and `vscode`-free.
 *
 * "Update stub to match upstream" — the upstream now returns `actualValue` at
 * `field`; the stub still declares `expectedValue`. We name the field and the
 * value the upstream returned so the fix is self-explanatory before applying.
 */
export function describeDriftFix(record: DriftRecord): string {
    const actual = formatDriftValue(record.actualValue);
    if (record.driftType === "SCHEMA_FIELD_ADDED") {
        return `Update stub: add ${record.field} (upstream now returns ${actual})`;
    }
    if (record.driftType.startsWith("SCHEMA_FIELD_REMOVED")) {
        return `Update stub: remove ${record.field} (upstream no longer returns it)`;
    }
    return `Update stub: set ${record.field} to ${actual} (matches upstream)`;
}

// ---------------------------------------------------------------------------
// Breakpoint matcher management (Phase 5 debugger) — registration / list /
// remove / clear. Resolution is over the callback WebSocket, not REST.
// ---------------------------------------------------------------------------

/** A registered breakpoint matcher as returned by the list endpoint. */
export interface BreakpointMatcherEntry {
    id: string;
    httpRequest?: Record<string, unknown>;
    phases: string[];
    clientId?: string;
    skipCount?: number;
}

/**
 * Register a breakpoint matcher via `PUT /mockserver/breakpoint/matcher`. The
 * body must already carry `clientId` (the callback-WS client to dispatch to).
 * Returns the server-assigned `{ id, phases }`. Throws (with status + body) on
 * a non-ok response so a server without breakpoints surfaces clearly.
 */
export async function registerBreakpointMatcher(
    baseUrl: string,
    body: Record<string, unknown>,
    fetchFn: FetchLike
): Promise<{ id: string; phases: string[] }> {
    const res = await fetchFn(`${baseUrl}/mockserver/breakpoint/matcher`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    const text = await res.text();
    try {
        const parsed = JSON.parse(text) as { id?: string; phases?: string[] };
        return { id: parsed.id ?? "", phases: Array.isArray(parsed.phases) ? parsed.phases : [] };
    } catch {
        return { id: "", phases: [] };
    }
}

/** List registered breakpoint matchers via `GET /mockserver/breakpoint/matchers`. */
export async function listBreakpointMatchers(
    baseUrl: string,
    fetchFn: FetchLike
): Promise<BreakpointMatcherEntry[]> {
    const res = await fetchFn(`${baseUrl}/mockserver/breakpoint/matchers`, {
        method: "GET",
        headers: { Accept: "application/json" },
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    try {
        const parsed = JSON.parse(await res.text()) as { matchers?: BreakpointMatcherEntry[] };
        return Array.isArray(parsed.matchers) ? parsed.matchers : [];
    } catch {
        return [];
    }
}

/** Remove a breakpoint matcher by id via `PUT /mockserver/breakpoint/matcher/remove`. */
export async function removeBreakpointMatcher(
    baseUrl: string,
    id: string,
    fetchFn: FetchLike
): Promise<void> {
    const res = await fetchFn(`${baseUrl}/mockserver/breakpoint/matcher/remove`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id }),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

/** Clear all breakpoint matchers via `PUT /mockserver/breakpoint/matcher/clear`. */
export async function clearBreakpointMatchers(baseUrl: string, fetchFn: FetchLike): Promise<void> {
    const res = await fetchFn(`${baseUrl}/mockserver/breakpoint/matcher/clear`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

// ---------------------------------------------------------------------------
// Chaos experiment panel (Phase 6) — start / status / stop over
// `/mockserver/chaosExperiment`. DTO shapes mirror the dashboard's
// chaosExperiment.ts client (the authoritative reference).
// ---------------------------------------------------------------------------

/** Status snapshot returned by `GET /mockserver/chaosExperiment`. */
export interface ChaosExperimentStatus {
    name: string | null;
    status: string;
    currentStageIndex: number;
    totalStages: number;
    stageElapsedMillis: number;
    stageRemainingMillis: number;
    loopIteration: number;
    totalElapsedMillis: number;
}

/** Retrieve the current chaos-experiment status. */
export async function getChaosExperimentStatus(
    baseUrl: string,
    fetchFn: FetchLike
): Promise<ChaosExperimentStatus> {
    const res = await fetchFn(`${baseUrl}/mockserver/chaosExperiment`, {
        method: "GET",
        headers: { Accept: "application/json" },
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    return JSON.parse(await res.text()) as ChaosExperimentStatus;
}

/** Start a chaos experiment via `PUT /mockserver/chaosExperiment`. */
export async function startChaosExperiment(
    baseUrl: string,
    definition: Record<string, unknown>,
    fetchFn: FetchLike
): Promise<void> {
    const res = await fetchFn(`${baseUrl}/mockserver/chaosExperiment`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(definition),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

/** Stop and clear the current chaos experiment via `DELETE /mockserver/chaosExperiment`. */
export async function stopChaosExperiment(baseUrl: string, fetchFn: FetchLike): Promise<void> {
    const res = await fetchFn(`${baseUrl}/mockserver/chaosExperiment`, { method: "DELETE" });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
}

/**
 * Format a chaos status snapshot as a readable, multi-line summary for an editor
 * tab: status, the running experiment's stage progress, and elapsed time. Pure.
 */
export function formatChaosStatus(status: ChaosExperimentStatus): string {
    const lines = [`MockServer chaos experiment — status: ${status.status}`];
    if (status.name) {
        lines.push(`Experiment: ${status.name}`);
    }
    if (status.status === "running" || status.status === "starting") {
        lines.push(
            `Stage ${status.currentStageIndex + 1} of ${status.totalStages} ` +
                `(${Math.round(status.stageRemainingMillis / 1000)}s remaining in stage)`
        );
        if (status.loopIteration > 0) {
            lines.push(`Loop iteration: ${status.loopIteration}`);
        }
    }
    return lines.join("\n") + "\n";
}

// ---------------------------------------------------------------------------
// Contract / resiliency test runner (Phase 6) — `PUT /mockserver/contractTest`.
// ---------------------------------------------------------------------------

/** A single operation's contract-test outcome. */
export interface ContractTestOperationResult {
    operationId: string;
    method: string;
    path: string;
    statusCodeReceived: number;
    passed: boolean;
    validationErrors: string[];
}

/** The full contract-test report returned by the server. */
export interface ContractTestReport {
    baseUrl: string;
    totalOperations: number;
    passed: number;
    failed: number;
    allPassed: boolean;
    results: ContractTestOperationResult[];
}

/**
 * Run an OpenAPI contract test against a live service via
 * `PUT /mockserver/contractTest`. `spec` is a URL/path/inline spec; `baseUrl` is
 * the service under test. Throws (with status + body) on a non-ok response.
 */
export async function runContractTest(
    serverBaseUrl: string,
    request: { spec: string; baseUrl: string; operationId?: string },
    fetchFn: FetchLike
): Promise<ContractTestReport> {
    const body: Record<string, unknown> = { spec: request.spec, baseUrl: request.baseUrl };
    if (request.operationId && request.operationId.trim()) {
        body.operationId = request.operationId.trim();
    }
    const res = await fetchFn(`${serverBaseUrl}/mockserver/contractTest`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        throw new Error(`MockServer returned ${res.status}: ${await res.text()}`);
    }
    return JSON.parse(await res.text()) as ContractTestReport;
}

/**
 * Format a contract-test report as a readable, per-operation pass/fail summary
 * for an editor tab. Pure and `vscode`-free.
 */
export function formatContractTestReport(report: ContractTestReport): string {
    const header =
        `MockServer contract test against ${report.baseUrl} — ` +
        `${report.passed}/${report.totalOperations} passed` +
        (report.allPassed ? " (all passed)" : `, ${report.failed} failed`);
    const lines = report.results.map((r) => {
        const mark = r.passed ? "✓" : "✗";
        const base = `${mark} ${r.method} ${r.path} [${r.operationId}] → HTTP ${r.statusCodeReceived}`;
        if (r.passed || r.validationErrors.length === 0) {
            return base;
        }
        return [base, ...r.validationErrors.map((e) => `    • ${e}`)].join("\n");
    });
    return [header, "", ...lines].join("\n") + "\n";
}
