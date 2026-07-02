# Event System, Logging & Verification

## Architecture Overview

All events in MockServer -- received requests, matched expectations, forwarded requests, verification results -- flow through a high-performance LMAX Disruptor ring buffer. A single consumer thread serializes all reads and writes, eliminating the need for locks.

```mermaid
graph TB
    subgraph "Producers (Netty I/O threads)"
        P1["HttpActionHandler
RECEIVED_REQUEST"]
        P2["HttpActionHandler
EXPECTATION_RESPONSE"]
        P3["HttpActionHandler
FORWARDED_REQUEST"]
        P4["HttpActionHandler
NO_MATCH_RESPONSE"]
        P5["HttpState
CREATED_EXPECTATION, CLEARED, etc."]
    end

    RB["LMAX Disruptor
Ring Buffer
Pre-allocated LogEntry slots"]

    P1 --> RB
    P2 --> RB
    P3 --> RB
    P4 --> RB
    P5 --> RB

    subgraph "Single Consumer Thread"
        PROC[processLogEntry]
        PROC -->|"1 disk write first"| NDJSON["RecordedRequestsFileSystemPersistence
NDJSON file (full bodies)"]
        PROC -->|"2 optional truncation"| TRUNC["truncateBodiesForLog
x-mockserver-body-truncated header"]
        TRUNC --> STORE["CircularConcurrentLinkedDeque
Count bound: maxLogEntries
Byte bound: maxEventLogSizeInBytes"]
        PROC --> NOTIFY[notifyListeners]
        PROC --> SLF4J[SLF4J / Console output]
    end

    RB --> PROC

    subgraph "Listeners"
        DASH["DashboardWebSocketHandler
Real-time UI push"]
        PERSIST["ExpectationFileSystemPersistence
File persistence"]
    end

    NOTIFY --> DASH
    NOTIFY --> PERSIST

    subgraph "Read Operations (via RUNNABLE entries)"
        VERIFY[Verification]
        RETRIEVE[Retrieve requests/logs]
        CLEAR[Clear/Reset]
    end

    VERIFY --> RB
    RETRIEVE --> RB
    CLEAR --> RB
```

## LMAX Disruptor Integration

### Why Disruptor?

The Disruptor provides:
- **Lock-free publishing**: Multiple Netty I/O threads can publish events without contention
- **Single-writer principle**: One consumer thread processes all events, eliminating data races
- **Pre-allocated objects**: Ring buffer slots are pre-allocated `LogEntry` instances, reducing GC pressure
- **Backpressure**: `tryPublishEvent()` is non-blocking; if the ring buffer is full, low-priority events are dropped

### Ring Buffer Mechanics

```mermaid
sequenceDiagram
    participant IO as Netty I/O Thread
    participant RB as Ring Buffer
    participant CT as Consumer Thread
    participant EL as Event Log

    IO->>IO: Create LogEntry with event data
    IO->>RB: tryPublishEvent(logEntry)
    Note over RB: logEntry.translateTo(slot, seq). Copies fields into pre-allocated slot. Clears the original logEntry
    RB->>CT: Event available
    CT->>CT: processLogEntry(slot)
    CT->>CT: clone = slot.cloneAndClear()
    CT->>EL: eventLog.add(clone)
    CT->>CT: notifyListeners()
    CT->>CT: writeToSystemOut()
```

### LogEntry as EventTranslator

`LogEntry` implements LMAX Disruptor's `EventTranslator<LogEntry>` interface. Its `translateTo()` method copies all fields from the source entry into the pre-allocated ring buffer slot, then clears the source. This avoids object allocation in the hot path.

### Serialized Read Operations

All read operations (verification, retrieval, clear, reset) are submitted as `RUNNABLE`-type `LogEntry` objects through the same ring buffer. This ensures that:

1. Reads see a consistent snapshot (no concurrent writes during iteration)
2. No locks are needed on the event log data structure
3. Operations are processed in FIFO order

```java
// Example: verify() publishes a RUNNABLE that runs on the consumer thread
disruptor.getRingBuffer().tryPublishEvent(
    new LogEntry()
        .setType(RUNNABLE)
        .setConsumer(() -> {
            // This runs on the single consumer thread
            List<LogEntry> matching = filterLog(predicate);
            future.complete(checkVerification(matching));
        })
);
```

## LogEntry

Each event is represented by a `LogEntry` with 25 possible types, organized into `LogMessageTypeCategory` groups for per-category log level overrides:

| Category Group | Types |
|----------------|-------|
| `MATCHING` | `EXPECTATION_MATCHED`, `EXPECTATION_NOT_MATCHED`, `NO_MATCH_RESPONSE` |
| `REQUEST_LIFECYCLE` | `RECEIVED_REQUEST`, `FORWARDED_REQUEST`, `EXPECTATION_RESPONSE`, `TEMPLATE_GENERATED` |
| `EXPECTATION_MANAGEMENT` | `CREATED_EXPECTATION`, `UPDATED_EXPECTATION`, `REMOVED_EXPECTATION`, `CLEARED` |
| `VERIFICATION` | `VERIFICATION`, `VERIFICATION_FAILED`, `VERIFICATION_PASSED`, `RETRIEVED` |
| `SERVER` | `SERVER_CONFIGURATION`, `AUTHENTICATION_FAILED`, `OPENAPI_RESPONSE_VALIDATION_FAILED` |
| `GENERAL` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `EXCEPTION` |
| (Internal) | `RUNNABLE` (used to serialize read operations through the ring buffer; excluded from categories) |

Users can override the log level per category or per individual type via the `logLevelOverrides` configuration property (a JSON map). Resolution order: individual type override > category group override > global `logLevel`. Overrides affect stdout/SLF4J output and the dashboard UI only; the event log stores entries based on the global `logLevel` threshold to preserve verification functionality. Note: overrides can only further suppress events that are already generated at the global `logLevel` — they cannot increase verbosity beyond the global threshold because events below the global level are never created or stored.

The `compactLogFormat` configuration property (default `false`) controls log output verbosity for stdout/SLF4J. When enabled, log messages use a compact single-line format showing summary information (e.g., `POST /path`, `200`, expectation ID) instead of full pretty-printed JSON. This only affects console output — the dashboard UI, verification, and REST API log retrieval continue to use the full structured format. The compact formatter is implemented in `StringFormatter.formatCompactLogMessage()` and called via `LogEntry.getCompactMessage()`, which is independent of the cached `getMessage()` used by the REST API.

### Key Fields

| Field | Type | Purpose |
|-------|------|---------|
| `id` | String | UUID (lazy-generated) |
| `correlationId` | String | Groups related entries (e.g., request + response) |
| `type` | LogMessageType | Event type (see above) |
| `httpRequests` | RequestDefinition[] | Associated requests |
| `httpResponse` | HttpResponse | Associated response |
| `expectation` | Expectation | Associated expectation |
| `expectationId` | String | ID of matched expectation |
| `epochTime` | long | Timestamp |
| `messageFormat` | String | Format string with `{}` placeholders |
| `arguments` | Object[] | Arguments for formatting |
| `deleted` | boolean | Soft-delete flag |

### Streamed Response Capture in FORWARDED_REQUEST

When MockServer proxies a streaming response (Server-Sent Events with `Content-Type: text/event-stream`) and `streamingResponsesEnabled` is `true`, the `FORWARDED_REQUEST` log entry is written **after the stream completes** rather than synchronously after `CompletableFuture.get()`. The entry is written from the stream-completion callback in `HttpActionHandler` once `LastHttpContent` arrives.

The `httpResponse` body in the log entry contains the bytes captured by `StreamingBody` (bounded to `maxStreamingCaptureBytes`, default 256 KB). Two additional headers may appear on the logged `httpResponse`:

| Header | Meaning |
|--------|---------|
| `x-mockserver-streamed: true` | Response was relayed incrementally (not buffered) |
| `x-mockserver-stream-truncated: true` | Captured body was truncated at `maxStreamingCaptureBytes`; the client received the full stream |

These headers are present only in the log entry — they are not sent to the client. The full stream always reaches the client regardless of the capture limit.

If the upstream connection closes mid-stream (`channelInactive`), the relay handler still emits a `FORWARDED_REQUEST` entry with the bytes captured so far, flagged with `x-mockserver-stream-truncated: true`.

## Event Log Storage

`CircularConcurrentLinkedDeque<LogEntry>` is a bounded, thread-safe deque. When either bound is reached, the oldest entries are evicted and their `clear()` method is called (releasing references for GC):

- **Count bound** — `maxLogEntries` (default: heap-based formula, up to 100,000).
- **Byte-budget bound** — `maxEventLogSizeInBytes` (default: 0 = disabled). When set, the deque also tracks a running total of body bytes (`LogEntry.estimatedHeapSize()`) and evicts oldest-first when an incoming entry would push the total over the budget. See [memory-management.md](memory-management.md) for the full byte-budget eviction design.

### Filtering Predicates

Static predicates filter log entries for different retrieval operations:

| Predicate | Passes Types |
|-----------|-------------|
| `requestLogPredicate` | `RECEIVED_REQUEST` |
| `requestResponseLogPredicate` | `EXPECTATION_RESPONSE`, `NO_MATCH_RESPONSE`, `FORWARDED_REQUEST` |
| `recordedExpectationLogPredicate` | `FORWARDED_REQUEST` |
| `expectationLogPredicate` | `EXPECTATION_RESPONSE`, `FORWARDED_REQUEST` |
| `notDeletedPredicate` | Any non-deleted entry |

**Filter ordering matters for CPU (issue #2359).** When a retrieve also applies an `HttpRequestMatcher`, the cheap type/not-deleted predicate is applied **before** the matcher. The matcher clones the request and runs full field-by-field matching, so running it first would evaluate it against deleted tombstones and wrong-type entries that are then discarded — making each `/retrieve` cost grow with total log size as the log fills toward `maxLogEntries` (and `clear` at `INFO` only tombstones entries, leaving them in the deque). Keep the predicate filter first when adding or changing a retrieve path. For the same reason, `clear` skips entries already marked deleted rather than re-matching them on every clear.

### Unmatched Request Retrieval

`MockServerEventLog.retrieveUnmatchedRequests(limit, Consumer<List<LogEntry>>)` retrieves the most recent `NO_MATCH_RESPONSE` log entries (requests that matched no expectation). It drains the disruptor first to ensure all pending events are processed, then iterates the event log in reverse order (most recent first) to return up to `limit` entries (capped at 100). This is used by `HttpState.explainUnmatched()` and the MCP `explain_unmatched_requests` tool to provide post-hoc mismatch diagnostics without requiring users to reconstruct the failing request.

## Verification

### Request Count Verification

```mermaid
sequenceDiagram
    participant C as Client
    participant HS as HttpState
    participant EL as MockServerEventLog
    participant RB as Ring Buffer

    C->>HS: PUT /mockserver/verify
    HS->>EL: verify(Verification)
    EL->>RB: Publish RUNNABLE
    RB->>RB: Consumer thread runs verification logic
    
    Note over RB: 1. Filter log by request matcher 2. Count matching entries 3. Check VerificationTimes.matches(count)
    
    alt Count matches
        RB->>EL: Log VERIFICATION_PASSED
        EL-->>C: 202 Accepted (empty body)
    else Count mismatch
        RB->>RB: Retrieve all requests for error message
        RB->>EL: Log VERIFICATION_FAILED
        EL-->>C: 406 Not Acceptable (failure message)
    end
```

#### Server-Side Eventual Verification (`timeout`) — #1713

Both `Verification` and `VerificationSequence` carry an optional `timeout` (milliseconds). When it is
`null`, absent, or `0`, verification is **single-shot**: `MockServerEventLog.verify(...)` evaluates the
event log once and immediately accepts (`202`) or rejects (`406`) — byte-identical to the original
behaviour (no listener, no scheduling). When `timeout > 0`, verification becomes **eventual**: the
server re-evaluates as the log changes until the verification passes or the deadline elapses.

```mermaid
flowchart TD
    A["verify(timeout > 0)"] --> B["first evaluation\n(logging suppressed)"]
    B -->|"passes"| P["final logging-on eval\nlog PASSED, complete 202"]
    B -->|"fails"| C["register transient MockServerLogListener\narm deadline on scheduler executor"]
    C --> D{"updated() notification\n(coalesced ~250ms)"}
    D -->|"re-eval passes\n(suppressed)"| Q["completeOnce"]
    D -->|"re-eval fails\n(suppressed)"| E["keep waiting"]
    C --> F{"deadline fires"}
    F --> Q
    Q --> G["cleanup, then ONE final logging-on eval\nlog PASSED/FAILED, complete 202/406"]
```

The harness lives in `MockServerEventLog.eventuallyVerify(...)` / `armEventualVerification(...)` and is
shared by request, response, and sequence verification (each supplies a `SingleVerificationEvaluation`
lambda that calls the existing `verifyRequest` / `verifyResponse` / `verifySequenceOnce`, threading a
`logResult` flag).

- **Exactly one logged outcome.** Intermediate re-evaluations during the wait run with
  `logResult = false`, so a failing-and-waiting verify does **not** append a `VERIFICATION_FAILED` entry
  per retry (which would pollute the bounded ring buffer — up to ~`timeout / 250ms` entries — and could
  evict real traffic). Only the winning completion (first pass or the deadline) runs one final
  `logResult = true` evaluation that emits the single `VERIFICATION_PASSED`/`VERIFICATION_FAILED` entry.
  Single-shot (`timeout` null/0) is unchanged: its one evaluation always logs.
- **Single-completion guard.** An `AtomicBoolean` ensures the result consumer is invoked exactly once,
  whether the first passing re-evaluation or the deadline wins the race; the winner re-derives the real
  result (so a request arriving right at the deadline is honoured) in its final logging-on evaluation.
- **No leak.** On completion the transient listener is always unregistered and the deadline
  `ScheduledFuture` is always cancelled (via the same single-completion guard) — before the final
  evaluation runs, so it cannot itself re-trigger the listener.
- **No I/O-thread blocking.** Re-evaluation runs on the coalesced notification path (scheduler
  executor) and the deadline runs on the scheduler — completion is delivered through the existing async
  result consumer, never a blocking sleep on the request thread.
- **Coalesced-notification safe.** Because async listener notifications are debounced (~250 ms), the
  harness also re-runs the evaluation once right after registering the listener, so a passing event that
  arrived between the first evaluation and registration is not missed.
- **Bounded.** The accepted timeout is hard-capped at `MockServerEventLog.MAX_VERIFY_TIMEOUT_MILLIS`
  (60 s) so a client cannot tie up server resources indefinitely. When no scheduled executor is available
  (synchronous `Scheduler`, e.g. WAR/servlet) the eventual path cannot be armed and verification
  degrades gracefully to single-shot.

This is the **server-side** complement to the Java client's existing client-side timeout-aware
`verify(request, times, Duration)` poll (see "Verification in Parallel Testing" below); a non-Java
client can now get eventual semantics by setting `timeout` on the verification JSON instead of polling.

`VerificationTimes` supports:
- `never()` — must not have been received
- `once()` — exactly 1
- `exactly(n)` — exactly n
- `atLeast(n)` — n or more
- `atMost(n)` — n or fewer
- `between(min, max)` — within range

#### Verify by Disposition

`Verification.withDisposition(Disposition)` narrows a request-count verification to only those requests handled with a particular disposition:

| Disposition | Counts log entries of type | Meaning |
|-------------|----------------------------|---------|
| `FORWARDED` | `FORWARDED_REQUEST` | request was forwarded/proxied to an upstream server |
| `MOCKED` | `EXPECTATION_RESPONSE` | request matched an expectation and got a mocked response |
| (unset) | `RECEIVED_REQUEST` | every received request (original behaviour) |

When a disposition is set, `MockServerEventLog.retrieveRequests(Verification, ...)` swaps `requestLogPredicate` for `forwardedRequestLogPredicate` or `mockedRequestLogPredicate` (both exclude `NO_MATCH_RESPONSE`, MockServer's own auto-404). The disposition is serialized as the `disposition` field on the verification JSON (enum `MOCKED`/`FORWARDED`). It applies to the request-count path only — it is ignored for response verification (`httpResponse` set) and expectation-id verification.

#### Soft / Collecting Verify (`verifyAll`)

`MockServerClient.verifyAll(Verification...)` runs every supplied verification client-side and, instead of throwing on the first failure like `verify(...)`, collects all failure messages and throws a single `AssertionError` listing every mismatch. This is purely a client convenience — each verification is still sent through the standard `PUT /mockserver/verify` path; no server change is involved.

### Response Verification

When `Verification.httpResponse` is non-null, the verification switches from the request-only path to a response-aware path that counts matching **request-response pairs** rather than received requests.

```mermaid
sequenceDiagram
    participant C as Client
    participant HS as HttpState
    participant EL as MockServerEventLog
    participant RB as Ring Buffer

    C->>HS: PUT /mockserver/verify (with httpResponse)
    HS->>EL: verify(Verification)
    EL->>RB: Publish RUNNABLE
    RB->>RB: Consumer thread runs verifyResponse logic

    Note over RB: 1. retrieveRequestResponses() using responseVerificationLogPredicate (excludes NO_MATCH_RESPONSE)
    Note over RB: 2. Map LogEntry → LogEventRequestAndResponse (request + response pair)
    Note over RB: 3. If httpRequest set, filter pairs by HttpRequestMatcher
    Note over RB: 4. Build HttpResponseMatcher from httpResponse template
    Note over RB: 5. Filter pairs by responseMatcher.matches(pair.getHttpResponse())
    Note over RB: 6. Check VerificationTimes.matches(matchingPairs.size())

    alt Count matches
        RB->>EL: Log VERIFICATION_PASSED
        EL-->>C: 202 Accepted (empty body)
    else Count mismatch
        RB->>RB: Serialize actual responses for error message
        RB->>EL: Log VERIFICATION_FAILED
        EL-->>C: 406 Not Acceptable ("Response not found ..." message)
    end
```

**Dispatch logic** in `MockServerEventLog.verify(Verification, Consumer<String>)`:

```java
if (verification.getHttpResponse() != null) {
    verifyResponse(verification, logCorrelationId, resultConsumer);
} else {
    verifyRequest(verification, logCorrelationId, resultConsumer);
}
```

The `verifyResponse` path uses `responseVerificationLogPredicate` — an alias for `expectationLogPredicate` — which passes only `EXPECTATION_RESPONSE` and `FORWARDED_REQUEST` entries. It deliberately **excludes** `NO_MATCH_RESPONSE` (MockServer's own auto-generated 404 for unmatched requests), so a template such as `response().withStatusCode(404)` does not accidentally count MockServer's own no-match responses. The `requestResponseLogPredicate` used by `/retrieve` is intentionally broader (includes `NO_MATCH_RESPONSE`); the verification predicate is a separate alias so future changes to one do not silently affect the other.

#### Response Matching Semantics

`HttpResponseMatcher` (`mockserver-core/src/main/java/org/mockserver/matchers/HttpResponseMatcher.java`) is a self-contained matcher built from the `HttpResponse` template in a `Verification` or `VerificationSequence`. Every field is optional: an unset field imposes no constraint, so a null template matches any response.

| Field | Matching strategy | Notes |
|-------|-------------------|-------|
| `statusCode` | Exact integer equality | Not used when `statusCodeRange` is set |
| `statusCodeRange` | `StatusCodeMatcher` — class range or numeric operator | See below |
| `reasonPhrase` | `RegexStringMatcher` — string or regex | Respects `matchExactCase` (see below) |
| `headers` | `MultiValueMapMatcher` — subset match, extra response headers allowed | Notted key/value strings supported |
| `cookies` | `HashMapMatcher` — subset match, extra cookies allowed | Same semantics as request cookie matching; notted values supported |
| `body` | `BodyMatching` dispatch — full parity with request body matching | See below |

**Status-code range / operator matching (`statusCodeRange`)** — `StatusCodeMatcher` supports three forms:

- **Exact** (default): when `statusCodeRange` is absent/blank, exact `Integer` equality is used.
- **Class range**: a single digit followed by `XX` (case-insensitive), e.g. `"2XX"` or `"5xx"`, matches the range `[N00, N99]`.
- **Numeric operator**: a leading comparison operator followed by a number, e.g. `">= 400"`, `"> 200"`, `"< 300"`, `"<= 204"`, `"== 201"`. Delegated to `NumericComparisonMatcher`.

When both `statusCode` and `statusCodeRange` are set on the template, `statusCodeRange` takes priority (the matcher is built from it). An unparseable `statusCodeRange` expression is a clean non-match (logged at DEBUG; never throws).

**`matchExactCase` scope** — the `reasonPhrase` matcher honours the `matchExactCase` configuration flag: when `true`, the reason-phrase comparison is case-sensitive. This mirrors the request-side behaviour for method, path, and string-body. Header names/values, cookie names/values, and query parameters are always matched case-insensitively regardless of this flag. The flag has no effect on `statusCode`/`statusCodeRange` (numeric) or on control-plane operations (clear/retrieve).

**Body matching** — response body matching shares `BodyMatching` (`mockserver-core/src/main/java/org/mockserver/matchers/BodyMatching.java`) with request matching. This means:

- All body matcher types are supported: string, regex, sub-string, JSON, JSON Schema, JSONPath, XML, XML Schema, GraphQL, JSON-RPC, binary, multipart.
- `optional: true` body template matches a response with no body.
- XML and form actual bodies are converted to JSON before JSON-family matching.
- Binary matchers try the decompressed bytes; for response bodies (no compressed-original representation) only one byte array is tried.
- An absent actual body is a clean non-match for JSON/XML matchers (no internal NPE).

**`detailedVerificationFailures` now covers response verification** — when `detailedVerificationFailures` is `true` and a response verification fails, MockServer appends a field-level closest-response diff to the error message. It scores recorded responses by how many fields differ from the template, picks the closest one, and lists the differing fields with expected-vs-found values. This is diagnostic only and never changes the pass/fail result.

**Intentional asymmetries vs. request matching** — features present on the request side that are absent from response matching:

- There is no top-level `not(...)` on a response template. The `HttpResponse` model has no `isNot()` method. Per-field negation (notted header/cookie strings and body `not`) still works.
- `connectionOptions` and HTTP trailers are not matched; they are action-configuration fields, not observable response properties.
- Control-plane operations (clear, retrieve) do not apply `matchExactCase`.

### Sequence Verification

Sequence verification checks that requests were received in a specific order:

```mermaid
sequenceDiagram
    participant C as Client
    participant EL as MockServerEventLog

    C->>EL: verify(VerificationSequence)
    Note over EL: Consumer thread: 1. Retrieve all received requests 2. Walk forward through list 3. For each expected request, find next match after last position 4. If any expected request not found in order, report failure
```

Verification can be done by request matcher or by expectation ID.

**Field-level closest-match diff on sequence failure** — when `detailedVerificationFailures` is enabled (on by default) and a request-matcher sequence step fails to find a match, the failure message appends a `closest match diff:` block for that specific step (via the same `buildClosestMatchDiff` used by single-request verify), naming the differing fields (method/path/headers/body/...) for the closest recorded request. Response-aware sequences append the analogous `buildClosestResponseMatchDiff` for the failing step's response template. The expectation-ID path appends no diff (steps match by recorded expectation id, not request fields). The diff is diagnostic only; when `detailedVerificationFailures` is disabled the legacy message format is unchanged.

**Request matcher count verification** filters `RECEIVED_REQUEST` entries. **Expectation ID verification** retrieves entries matching `expectationLogPredicate` (includes `EXPECTATION_RESPONSE`, `FORWARDED_REQUEST`). **Sequence verification** scans recorded requests in order rather than counting.

#### Response-Aware Sequence Verification

When `VerificationSequence.httpResponses` is non-empty, sequence verification switches to a response-aware path that checks both requests and responses at each step:

1. Validates inputs: an entirely-empty sequence (no expectation IDs, no requests, no responses) is rejected; when both `httpRequests` and `httpResponses` are non-empty they must be the same length, otherwise the sequence is rejected (a mismatched-length sequence previously padded with null and silently passed the unspecified steps — this is no longer allowed).
2. Retrieves all recorded request-response pairs via `retrieveRequestResponses()` using `responseVerificationLogPredicate` (excludes `NO_MATCH_RESPONSE`).
3. Iterates `stepCount = httpResponses.size()` steps; `httpRequests` may be empty (response-only sequence).
4. At each step, creates an `HttpRequestMatcher` from `httpRequests[i]` (if present) and an `HttpResponseMatcher` from `httpResponses[i]`; a null matcher acts as a wildcard for that side.
5. Uses a forward-scanning pointer (`pairLogCounter`) that only advances — order is preserved.
6. A step passes when both matchers match the same `LogEventRequestAndResponse` entry: `requestMatches && responseMatches`.
7. If any step fails to find a match after the previous step's position, the sequence verification fails; the failure message serializes the response side (not the request side) to make the failure actionable.

### Verification in Parallel Testing

**Common issue (#1713):** When running tests in parallel, verification may intermittently fail even though requests were sent successfully.

**Root causes:**

1. **Async application under test** — If your application sends requests asynchronously (e.g., fire-and-forget, background workers), calling `verify()` before the application has actually sent the request will fail. Verification operations are serialized through the same ring buffer as request recording (FIFO order), so once a request has reached MockServer and been published to the ring buffer, subsequent verification calls will see it.
2. **Log eviction** — The event log is bounded by `maxLogEntries` (default: `min(free heap KB / 8, 100000)`). In high-throughput parallel testing, old entries may be evicted before verification runs.
3. **Cross-test interference** — If multiple tests share the same MockServer instance, requests from other tests may inflate the count or interfere with sequence verification.

**Solutions:**

- **Increase `maxLogEntries`** if running many parallel tests that generate thousands of requests:
  ```java
  ConfigurationProperties.maxLogEntries(200_000);
  ```
- **Use separate MockServer instances per test** (different ports or separate containers) to isolate event logs
- **Use unique test identifiers** if sharing an instance:
  - Unique paths per test: `/test/{testId}/...`
  - Unique headers or query parameters in matchers
  - Avoid broad matchers like only `path("/api")` in parallel tests
- **Be careful with `clear()` / `reset()`** — they affect all tests sharing the instance
- **Retry verification with backoff** if testing asynchronous systems where you need to wait for the application to send requests. The Java client has this built in via timeout-aware overloads (no external retry helper needed):
  ```java
  // Eventual verification: poll until the request arrives or the timeout expires
  mockServerClient.verify(request, VerificationTimes.once(), Duration.ofSeconds(5));

  // Negative-within-timeout: assert no matching request arrives during the window
  mockServerClient.verifyNever(request, Duration.ofSeconds(2));
  ```
  These poll the standard `PUT /mockserver/verify` endpoint client-side with a 100 ms backoff (`MockServerClient.verify(Verification, Duration)` / `verifyNever(Verification, Duration)`); there is no server-side wait. The equivalent with an external library is:
  ```java
  // Wait for application under test to send request, not for MockServer to process it
  Awaitility.await()
      .atMost(Duration.ofSeconds(5))
      .pollInterval(Duration.ofMillis(100))
      .untilAsserted(() -> mockServerClient.verify(request, VerificationTimes.once()));
  ```
- **Debug by retrieving recorded requests** — if verification fails, check what was actually recorded:
  ```java
  // Retrieves recorded requests (not the full event log)
  HttpRequest[] recorded = mockServerClient.retrieveRecordedRequests(null);
  System.out.println("Recorded requests: " + Arrays.toString(recorded));
  ```

See consumer documentation at [/mock_server/verification.html#how_verification_works](https://www.mock-server.com/mock_server/verification.html#how_verification_works) for user-facing guidance.

## Retrieve Formats (Expectation Code Generation)

`PUT /mockserver/retrieve?type=<scope>&format=<format>` converts recorded or active state into a
chosen representation. The `format` query parameter maps to the `Format` enum
(`mockserver-core/.../model/Format.java`) via `Format.valueOf(param.toUpperCase())`, defaulting to
`JSON`. `HttpState.retrieve()` dispatches on `(scope, format)` in four `switch(format)` blocks —
one per scope: `REQUESTS`, `REQUEST_RESPONSES`, `RECORDED_EXPECTATIONS`, `ACTIVE_EXPECTATIONS`.

| Format | Scopes producing code/output | Content-Type | Generator |
|--------|------------------------------|--------------|-----------|
| `JAVA` | recorded + active expectations | `application/java` | `ExpectationToJavaSerializer` (typed builder DSL) |
| `JAVASCRIPT` | recorded + active expectations | `application/javascript` | `ExpectationToJavaScriptSerializer` |
| `PYTHON` | recorded + active expectations | `text/x-python` | `ExpectationToPythonSerializer` |
| `GO` | recorded + active expectations | `text/x-go` | `ExpectationToGoSerializer` |
| `CSHARP` | recorded + active expectations | `text/x-csharp` | `ExpectationToCSharpSerializer` |
| `RUBY` | recorded + active expectations | `text/x-ruby` | `ExpectationToRubySerializer` |
| `RUST` | recorded + active expectations | `text/x-rust` | `ExpectationToRustSerializer` |
| `PHP` | recorded + active expectations | `application/x-httpd-php` | `ExpectationToPhpSerializer` |
| `JSON` | all | `application/json` | `ExpectationSerializer` / `RequestDefinitionSerializer` |

**Why the non-Java languages are cheap.** Unlike the Java client (which needs the typed builder DSL,
hence the ~20-class `*ToJavaSerializer` family), every other official client accepts an expectation as
a JSON object. So each `ExpectationTo<Lang>Serializer` (all in `org.mockserver.serialization.code`)
reuses the existing JSON serialization (the same `ExpectationSerializer` used for `format=json`) and
wraps each expectation in the language's real upsert call plus an import/instantiation preamble — one
call per expectation. The embedded JSON is byte-identical to `format=json`, so the generated code
round-trips through the real clients.

- **JavaScript**: `const { mockServerClient } = require('mockserver-client');` then one
  `mockServerClient("localhost", 1080).mockAnyResponse(<expectation JSON>);` per expectation.
- **Python**: `import json` / `from mockserver import MockServerClient, Expectation` then one
  `client.upsert(Expectation.from_dict(json.loads("""<expectation JSON>""")));` per expectation.
- **Go**: `mockserver.New("localhost", 1080)` then `json.Unmarshal([]byte(`​`<JSON>`​`), &e); client.Upsert(e)`.
  A Go raw-string (backtick) literal carries the JSON; it falls back to a double-quoted interpreted
  string if the JSON contains a backtick.
- **C#**: `new MockServerClient("localhost", 1080)` then
  `client.Upsert(JsonSerializer.Deserialize<Expectation>(@"<JSON>", jsonOptions));`. The JSON sits in a
  C# verbatim string (`@"..."`, double-quotes doubled).
- **Ruby**: `require 'mockserver-client'` then
  `client.upsert(MockServer::Expectation.from_hash(JSON.parse(<<JSON)));` with the JSON in a heredoc.
- **Rust**: `ClientBuilder::new("localhost", 1080).build()?` then
  `client.upsert(&[serde_json::from_str::<Expectation>(r#"<JSON>"#)?])?;`. The hash count of the raw
  string is bumped if the JSON contains a quote-followed-by-hashes terminator.
- **PHP**: `new MockServerClient('localhost', 1080)` then
  `$client->upsertExpectation(Expectation::fromArray(json_decode(<<<'JSON' ... JSON, true)));`. The JSON
  sits in a nowdoc (no interpolation). The PHP client's `Expectation::fromArray()` factory stores the
  decoded array verbatim and replays it from `toArray()`, so every field round-trips without a typed
  field-by-field inverse.

Each generator escapes the embedded JSON for its language's string literal, so hostile values (quotes,
backslashes, newlines, and the language's own raw-string/heredoc terminator) copy-paste cleanly. These
are all expectation-scope formats; for `REQUESTS`/`REQUEST_RESPONSES` they return a clear "not
supported" message, exactly as `JAVA` does for `REQUEST_RESPONSES`. The dashboard surfaces these via
Library → Export (format dropdown + "Copy as code" button). The same Export tab also offers
**verification code** for the recorded-requests scope in Java, JavaScript, Python, Go, C#, Ruby and
Rust — that code is generated client-side in the dashboard (by `verificationCodegen.ts`) from the
retrieved request JSON, one `verify(...)` per request, rather than by a server-side serializer.

## Record &rarr; Mock (Consolidation, Promotion, HAR Import)

Raw recorded expectations are a verbatim 1:1 dump: `MockServerEventLog.retrieveRecordedExpectations()`
maps every `FORWARDED_REQUEST` log entry (via `LogEntry::getExpectation`) to an exact-match
`Times.once()` expectation. Recording 50 hits to `GET /users/123` therefore yields 50 identical,
brittle expectations. `RecordedExpectationPostProcessor` turns that dump into reusable mocks.

```mermaid
flowchart LR
    LOG["FORWARDED_REQUEST\nlog entries"] --> RET["retrieveRecordedExpectations()\n(verbatim, Times.once())"]
    RET -->|"?consolidate=true"| CONS["RecordedExpectationPostProcessor.consolidate()"]
    CONS --> OUT["reusable mocks:\nTimes.unlimited(),\n{id} path params,\nSEQUENTIAL responses,\nvolatile headers stripped"]
    RET -->|"default (no option)"| VERBATIM["unchanged output"]
```

Two engines live in `RecordedExpectationPostProcessor` (both pure functions):

| Method | Behaviour | Trigger |
|--------|-----------|---------|
| `deduplicateAndTemplatize(list, templatizeValues)` | Conservative dedup: preserves recorded `Times`, emits **one expectation per distinct response** (differing responses are *not* merged). | config flags `deduplicateRecordedExpectations` / `templatizeRecordedValues` |
| `consolidate(list, parameterizeValues)` | Record&rarr;mock: **one expectation per request shape**, `Times.unlimited()`, differing responses **sequenced** into a `SEQUENTIAL` list, volatile request headers stripped (reusing `HarImporter.volatileRequestHeaders()`). | `?consolidate=true` retrieve/import query param, or the promote endpoint |

`consolidate` groups eligible exchanges (concrete `HttpResponse` only) by structural signature
(method + templatized path shape + body shape). Where a group spans several concrete ids the varying
`/users/{id}` segments become declared path parameters; a single id keeps its concrete path. Distinct
responses are collected in first-seen order and de-duplicated — identical hits collapse to one
response, differing responses become a `ResponseMode.SEQUENTIAL` multi-response list on a single
expectation. With `parameterizeValues` (`?parameterize=true`) volatile query-parameter, header and
JSON-body leaf values are additionally generalised to regex matchers (same heuristics as
`deduplicateAndTemplatize`).

`HttpState.postProcessRecordedExpectations(list, request)` wires the query parameters into every
`RECORDED_EXPECTATIONS` retrieve format: `?consolidate=true` (and/or `?parameterize=true`) takes
precedence over the config-flag path; with neither present and the flag off, output is byte-for-byte
identical to historical behaviour (non-breaking).

**Promotion (`PUT /mockserver/recordings/promote`).** The server-side equivalent of the MCP
`create_expectations_from_recorded_traffic` tool. It retrieves recorded expectations matching an
optional request-matcher filter (JSON body; empty body = all), **redacts secrets first** (via
`ImportRedaction`, on by default — redacting the raw single-response recordings before consolidation
so responses differing only in a secret collapse and the single-response redactor never flattens a
sequenced list), consolidates/parameterizes them (`?consolidate` / `?parameterize`, both on by
default; `?consolidate=false` promotes verbatim but still upgraded to `Times.unlimited()`), then
**activates** the result via `HttpState.add(...)` and returns it as `201 Created`.

**HAR import.** `PUT /mockserver/import?format=har` already turns a HAR capture into expectations via
`HarImporter` (HAR was previously export-only for the recorded-request path; import has since been
added for HAR/Postman/Pact). `?consolidate=true` / `?parameterize=true` now run the same consolidation
engine over the imported expectations before they are upserted, so a HAR that captured one endpoint
many times collapses into a compact reusable mock set.

**Redaction preserves multi-response lists.** `FixtureRedactor.redactExpectation` now redacts and
preserves a `SEQUENTIAL`/`WEIGHTED`/`SWITCH` `httpResponses` list (with its `responseMode`,
`responseWeights` and `switchAfter`) instead of silently dropping all but the single
`getHttpResponse()` — required because consolidation can emit sequenced responses that later pass
through the config-driven `redactSecretsInRecordedExpectations` step on the retrieve path.

## Persistence System

### Disk Capture for Recorded Requests (NDJSON)

When `persistRecordedRequestsToDisk` is `true`, every recorded exchange — both `FORWARDED_REQUEST` (proxied) and `EXPECTATION_RESPONSE` (mocked) log entries — is appended to an NDJSON file (one compact JSON object per line) by `RecordedRequestsFileSystemPersistence`, wired in as a per-entry hook on the Disruptor consumer thread.

```mermaid
sequenceDiagram
    participant CT as Consumer Thread
    participant FP as RecordedRequestsFileSystemPersistence
    participant FS as NDJSON file
    participant EL as Event Log (in-memory)

    CT->>CT: processLogEntry(logEntry)
    Note over CT: 1. disk capture runs FIRST (full bodies)
    CT->>FP: recordedRequestConsumer.accept(logEntry)
    FP->>FP: serialize to compact NDJSON line
    FP->>FS: writer.write(line + "\n")
    FP->>FS: writer.flush()
    Note over CT: 2. optional in-memory truncation
    CT->>CT: truncateBodiesForLog(logEntry) [if maxLoggedBodyBytes > 0]
    Note over CT: 3. add to bounded in-memory log
    CT->>EL: eventLog.add(logEntry)
```

**Key design points:**

- **Disk-before-truncation ordering.** The disk write runs in `processLogEntry` before `truncateBodiesForLog()`, so the NDJSON archive always receives full-fidelity bodies even when `maxLoggedBodyBytes` clips the in-memory copy.
- **Append-only, flush-per-line.** The file is opened with `StandardOpenOption.CREATE | APPEND`. Each line is flushed immediately after writing, so a crash or OOM-kill loses at most the in-flight entry, not the whole session — and re-import backs that guarantee up: because the write is `line + "\n" + flush`, a hard kill can leave a **truncated mid-JSON final line**, and `RecordedTrafficImporter` **skips (and counts) that malformed line rather than aborting**, so `?format=recording&source=disk` recovers every intact exchange.
- **Write path is a side-channel; reload it explicitly.** Live reads (retrieve, verify, dashboard) query the in-memory `CircularConcurrentLinkedDeque` only, so entries evicted from memory under the byte budget are on disk but not visible until the archive is reloaded. **Re-import** the archive with `PUT /mockserver/import?format=recording` — supply the NDJSON in the request body, or add `?source=disk` (or send an empty body) to read the configured `persistedRecordedRequestsPath`. `RecordedTrafficImporter` parses each line and `MockServerEventLog.importRecordedRequestResponse(...)` re-injects each pair as a `FORWARDED_REQUEST` entry, so it becomes retrievable exactly like an in-memory recording. (A reloaded originally-mocked exchange therefore counts as forwarded under verify-by-disposition — an accepted v1 boundary.)
- **Malformed/truncated lines are skipped, not fatal.** A single unparseable line (the classic crash-truncated last line, or any corruption) is skipped and counted; the skipped count is returned in the `x-mockserver-recorded-requests-skipped` response header and logged at WARN. The importer only fails (`400`) when the body has non-blank lines but **none** parse (i.e. it is not a recorded-traffic archive at all). An empty/whitespace archive imports **0** exchanges (`201`), not an error.
- **Re-import is idempotent.** Re-injected entries carry `LogEntry.skipRecordedRequestPersistence = true`, so `processLogEntry` does not hand them back to the disk consumer — reloading an archive never appends the reloaded exchanges back to the (possibly same) file.
- **Inert when disabled.** When `persistRecordedRequestsToDisk` is `false`, the `RecordedRequestsFileSystemPersistence` instance has all fields `null` and `append()` / `stop()` are no-ops. The hook (`recordedRequestConsumer`) is not set on `MockServerEventLog`. (Re-import via `PUT /mockserver/import?format=recording` still works with an archive supplied in the request body.)
- **Both mocked and forwarded entries.** The hook is guarded by `logEntry.getType() == FORWARDED_REQUEST || logEntry.getType() == EXPECTATION_RESPONSE` in `processLogEntry`, so the archive is a complete record of served traffic (proxied and mocked). Other event types (RECEIVED_REQUEST, NO_MATCH_RESPONSE, etc.) are not written.

**Format.** Each line is a serialized `HttpRequestAndHttpResponse` (via `HttpRequestAndHttpResponseSerializer`) with formatting whitespace collapsed so the entire object is one line. The format is identical to what `PUT /mockserver/retrieve?type=REQUEST_RESPONSES` returns for a single entry, and is exactly what `RecordedTrafficImporter` reads back on re-import.

**Redaction.** The persisted archive honours `mockserver.redactSecretsInLog` exactly like the in-memory retrieval/export path — `append()` uses the redaction-aware `LogEntry.getRedactedHttpRequest()` / `getRedactedHttpResponse()` accessors, so secrets are masked on disk by default when redaction is on. Re-import re-masks via `ImportRedaction` (on by default) as a defence-in-depth step, consistent with HAR/Postman import.

**Recommended combo.** Pair disk capture with `maxEventLogSizeInBytes` to get bounded memory and complete session history on disk:

```
persistRecordedRequestsToDisk=true      # full bodies to disk
maxEventLogSizeInBytes=268435456        # 256 MB in-memory byte budget
maxLoggedBodyBytes=0                    # keep in-memory bodies untruncated (budget evicts instead)
```

The launcher `mockserver-ui/scripts/launch-with-llm-capture.sh` uses exactly this combination by default.

**Throughput trade-off.** With `persistRecordedRequestsToDisk` enabled, every recorded exchange — now including each mocked `EXPECTATION_RESPONSE`, of which a single streaming LLM/SSE/gRPC request can emit several — triggers a synchronous serialize + write + `flush()` on the single Disruptor consumer thread. This is opt-in and off by default, but under sustained high-volume mocking it can become the consumer-thread bottleneck. If that matters, a buffered/periodic-flush writer (trading a slightly larger crash-loss window for throughput) is possible future work; today the design favours the per-line durability guarantee above.

### File Persistence for Expectations

When `configuration.persistExpectations()` is true, `ExpectationFileSystemPersistence` implements `MockServerMatcherListener` and writes all active expectations to a JSON file whenever they change.

```mermaid
sequenceDiagram
    participant AH as HttpActionHandler
    participant RM as RequestMatchers
    participant FP as ExpectationFileSystemPersistence
    participant FS as File System

    AH->>RM: add/remove expectation
    RM->>FP: updated(matchers, cause)
    Note over FP: Skip if cause is FILE_INITIALISER and path matches persistence path
    FP->>FP: Acquire ReentrantLock + FileLock
    FP->>FS: Write JSON array of active expectations
    FP->>FP: Release locks
```

### File Watcher

When `configuration.watchInitializationJson()` is true, `ExpectationFileWatcher` monitors the initialization JSON and OpenAPI files for changes:

- Uses `FileWatcher` which polls every 5 seconds using a `ScheduledExecutorService`
- Detects changes by comparing file content hashes (`Arrays.hashCode(Files.readAllBytes(path))`)
- On change, reloads expectations via `ExpectationInitializerLoader`

## Observer Pattern

Two observer interfaces drive real-time updates:

```mermaid
classDiagram
    class MockServerLogListener {
        <<interface>>
        +updated(MockServerEventLog)
    }

    class MockServerMatcherListener {
        <<interface>>
        +updated(RequestMatchers, Cause)
    }

    class DashboardWebSocketHandler {
        +updated(MockServerEventLog)
        +updated(RequestMatchers, Cause)
    }

    class ExpectationFileSystemPersistence {
        +updated(RequestMatchers, Cause)
    }

    MockServerLogListener <|.. DashboardWebSocketHandler
    MockServerMatcherListener <|.. DashboardWebSocketHandler
    MockServerMatcherListener <|.. ExpectationFileSystemPersistence
```

### Notification Flow

- `MockServerEventLogNotifier` (base of `MockServerEventLog`): Notifies `MockServerLogListener` instances when log entries are added
- `MockServerMatcherNotifier` (base of `RequestMatchers`): Notifies `MockServerMatcherListener` instances when expectations change

Notifications are dispatched asynchronously via the `Scheduler` to avoid blocking the Disruptor consumer thread.

#### Coalesced (debounced) asynchronous notifications

`MockServerEventLogNotifier.notifyListeners(notifier, synchronous)` is called on **every** log add
(`processLogEntry`) as well as on stop, reset, and clear. Firing a listener `updated(...)` per add is
expensive: each of the three listeners — `DashboardWebSocketHandler` (WebSocket push),
`MemoryMonitoring` (CSV), and `RecordedExpectationFileSystemPersistence` — does a full retrieve and
re-serialize of state, and the listeners only ever need the *latest* snapshot.

So the **asynchronous** path (`synchronous=false`, the per-add case) is **coalesced**: each call sets a
`dirty` flag and a single scheduled task fires at most one `updated(...)` per **250 ms** debounce window.
A rapid burst of adds therefore collapses into one retrieve+serialize per window instead of one per add.
The task re-arms itself only if more adds arrived while it was running.

```mermaid
flowchart LR
    A["processLogEntry (per add)"] --> B["notifyListeners(false)"]
    B --> C{"dirty flag set\ntask already scheduled?"}
    C -->|"no task"| D["schedule one task\n(+250ms)"]
    C -->|"task pending"| E["just set dirty,\ncoalesce"]
    D --> F["fire ONE updated()\nper window, re-arm if dirty"]
```

The **synchronous** path (`synchronous=true`, used by stop/clear/reset where ordering and a final flush
matter) stays **immediate** and is never debounced. The debounce uses the `Scheduler`'s
`ScheduledExecutorService` (`scheduler.getExecutorService()`); when that is `null` — the synchronous
`Scheduler` used by WAR/servlet deployments — the notifier falls back to firing immediately so
notifications are never lost.

**Correctness:** debouncing the listener path cannot affect verification or retrieval. Those operations
drain the disruptor (`drainDisruptor()`) and then query the event log directly via a `RUNNABLE` on the
consumer thread; they never wait on `notifyListeners`. On `stop()`, the log fires a final synchronous
notification (flushing latest state) and then calls `stopNotifications()`, which cancels any pending
coalesced task so it cannot leak past shutdown.

## Scheduler

The `Scheduler` manages async task execution with a `ScheduledThreadPoolExecutor`:

| Method | Purpose |
|--------|---------|
| `schedule(Runnable, Delay...)` | Execute after delay |
| `submit(Runnable)` | Execute immediately |
| `submit(HttpForwardActionResult, Runnable)` | Execute when forward result completes |
| `submit(CompletableFuture<BinaryMessage>, Runnable)` | Execute when binary result completes |

Thread names follow the pattern `MockServer-<name><N>`. The pool uses `CallerRunsPolicy` as a backpressure mechanism when saturated.

## Memory Monitoring

`MemoryMonitoring` implements both `MockServerLogListener` and `MockServerMatcherListener` to track JVM memory usage. When `outputMemoryUsageCsv` is enabled, it writes memory statistics to a CSV file every 50 updates. See [Metrics & Monitoring](metrics.md) for full details.

## Control-Plane Audit Log

**TL;DR:** an off-by-default, append-only, bounded, in-memory log of control-plane *mutations* (who/what/when/where/outcome), so MockServer can run as shared infrastructure with accountability. It is **not** data-plane traffic logging and stores **no request headers or bodies** — only redacted, structural metadata.

```mermaid
flowchart LR
    A["Control-plane request\n(PUT /expectation, /clear, ...)"] --> B["controlPlaneRequestAuthenticated()\n(single post-auth choke point)"]
    B -->|authorised| C["recordAudit()\nfail-soft, off by default"]
    C --> D["AuditStore\nbounded ring (newest-first)"]
    D --> E["GET /mockserver/audit\n?limit=<n> (default 200, cap 1000)"]
```

- **Why a separate store.** The audit log is a security/accountability record of *who changed mock state*, with a different lifetime, redaction policy, and retrieval surface from the data-plane event log. It deliberately reuses the proven `DriftStore` shape (a `java.util.concurrent`-locked `ArrayDeque` ring) rather than the Disruptor event log.
- **Fire point.** `HttpState.controlPlaneRequestAuthenticated` is the single choke point every control-plane operation passes through after authentication. It now calls `handler.authenticate(request)` and passes the resulting `AuthenticationResult` into `recordAudit(request, result)` in the success branch, *before* the handler executes (when auth is disabled it synthesises an authenticated-anonymous result). It is wrapped in `try/catch` and swallows all errors (TRACE-logged) so it can never throw into the request path.
- **Off by default.** When `controlPlaneAuditEnabled` is false, `recordAudit` returns immediately and the operation behaves byte-for-byte identically. Reads (GET requests and known read PUTs such as `/retrieve`, `/verify`, `/diff`) are skipped unless `controlPlaneAuditReads` is enabled — by default only mutations (and `reset`) are recorded.
- **Entry schema** (`AuditEntry`, immutable): `epochTimeMs`, `method`, `path` (control-plane path with the **query string dropped**), `operation` (logical name from the path suffix, e.g. `expectation`/`clear`/`reset`/`chaosExperiment`/`loadScenario`), `sourceAddress` (`request.getRemoteAddress()`, `"unknown"` if null), `principal`, `principalSource` (`verified-oidc`/`verified-mtls`/`verified-jwt` when an enriched handler supplied a verified principal, else the best-effort `jwt`/`mtls`/`none`), `outcome` (`AUTHORIZED` for a permitted operation, or `FORBIDDEN` when control-plane authorization — `controlPlaneAuthorizationEnabled` — denied an authenticated principal), `summary` (reserved; always `null` in v1 — never a header, query value, or body).
- **Verified principal preferred; best-effort fallback.** When the configured `AuthenticationHandler` returns an `AuthenticationResult` carrying a verified principal (e.g. `OidcAuthenticationHandler` → `principalSource=verified-oidc`, principal = the signature-verified `sub`), that principal/source is recorded. Otherwise — auth disabled, or a legacy boolean-only handler — audit falls back to the **UNVERIFIED** best-effort extraction: from `Authorization: Bearer <jwt>` the payload segment is base64url-decoded and `sub` is read with **no signature verification** (`principalSource=jwt`); else the mTLS client-certificate subject CN (`principalSource=mtls`); else `anonymous`/`none`. The raw token is never stored, and any parse failure yields `anonymous`/`none`.
- **Redaction (by omission).** Entries carry no headers and no body, and the path has its query string stripped — so there is no credential-bearing free text to scrub. The `summary` field is unused (always `null`) in v1, so safety is by omission rather than active redaction. If a non-null `summary` derived from a header or query value is ever added, scrub it through `FixtureRedactor.defaultSensitiveHeaders()` + `REDACTED_PLACEHOLDER` at that point.
- **Reset clears the audit log, including its own `reset` entry.** A `PUT /mockserver/reset` records its `reset` audit entry and then `reset()` clears the store, so the wipe leaves no durable trace — intentional for an off-by-default, best-effort, in-memory log (not a tamper-evident compliance log).
- **Capacity.** The `AuditStore` singleton reads `controlPlaneAuditMaxEntries` (default 1000) **once at construction** — a fixed-capacity ring, like `DriftStore`. `HttpState.reset()` clears it alongside `DriftStore`.
- **Durable NDJSON file sink (optional).** Because the in-memory ring is bounded and is wiped by the very `reset` it records, an optional durable sink can persist the trail. Set `auditLogFile` to a path and every recorded `AuditEntry` is *also* appended — as one compact JSON object per line (newline-delimited JSON) — by `AuditFileSink`, a **separate writer** that only *observes* the same entry `recordAudit` hands the ring. The ring is unchanged: `AuditFileSink` never reads or mutates it, honouring the `AuditEntry` contract that the in-memory store must never "become a sink". The path is resolved once, on the first entry written (fixed thereafter, like the ring's capacity), and missing parent directories are created; the file is opened append-only (survives restart and `reset`). It flushes per line and is thread-safe. All open/write failures are fail-soft: a single WARN is logged and the sink self-disables — request handling and the in-memory ring are never affected. Rotation is out of scope (append-only growth); use external log rotation. Empty default = off, behaviour unchanged.

**Deferred (not in v1):** tamper-evidence; process-signal control of auditing. (Verified-principal / external-IdP integration shipped in Tier 1.5-A via `OidcAuthenticationHandler` — an OIDC-verified `sub` is recorded with `principalSource=verified-oidc`. Coarse control-plane authorization — Tier 1.5-A Wave 2 — now populates `outcome=FORBIDDEN` for denied operations; FORBIDDEN denials are always recorded when auditing is enabled, even for reads, whereas AUTHORIZED reads honour `controlPlaneAuditReads`.)

## Class Reference

| Class | File | Role |
|-------|------|------|
| `MockServerEventLog` | `mockserver-core/.../log/MockServerEventLog.java` | Central event log with Disruptor ring buffer |
| `AuditStore` | `mockserver-core/.../mock/audit/AuditStore.java` | Bounded, append-only ring of control-plane audit entries (singleton; off by default) |
| `AuditEntry` | `mockserver-core/.../mock/audit/AuditEntry.java` | Immutable, redacted control-plane mutation record (no headers/bodies) |
| `LogEntry` | `mockserver-core/.../log/model/LogEntry.java` | Event data object, implements `EventTranslator` |
| `MockServerLogger` | `mockserver-core/.../logging/MockServerLogger.java` | Logging facade, routes to event log |
| `Scheduler` | `mockserver-core/.../scheduler/Scheduler.java` | Async task execution |
| `CircularConcurrentLinkedDeque` | `mockserver-core/.../collections/CircularConcurrentLinkedDeque.java` | Bounded event store |
| `CircularPriorityQueue` | `mockserver-core/.../collections/CircularPriorityQueue.java` | Priority-sorted expectation store |
| `Verification` | `mockserver-core/.../verify/Verification.java` | Request count verification |
| `VerificationSequence` | `mockserver-core/.../verify/VerificationSequence.java` | Ordered sequence verification |
| `VerificationTimes` | `mockserver-core/.../verify/VerificationTimes.java` | Expected count constraints |
| `HttpResponseMatcher` | `mockserver-core/.../matchers/HttpResponseMatcher.java` | Response matcher for response verification (status, headers, body) |
| `BodyMatcherBuilder` | `mockserver-core/.../matchers/BodyMatcherBuilder.java` | Factory for body matchers, shared by request and response matching |
| `ExpectationFileSystemPersistence` | `mockserver-core/.../persistence/ExpectationFileSystemPersistence.java` | Write expectations to disk |
| `RecordedRequestsFileSystemPersistence` | `mockserver-core/.../persistence/RecordedRequestsFileSystemPersistence.java` | Append-only NDJSON disk capture for recorded exchanges (forwarded + mocked) |
| `RecordedTrafficImporter` | `mockserver-core/.../imports/RecordedTrafficImporter.java` | Parse a persisted NDJSON archive back into `HttpRequestAndHttpResponse` pairs for re-import (`PUT /mockserver/import?format=recording`) |
| `ExpectationFileWatcher` | `mockserver-core/.../persistence/ExpectationFileWatcher.java` | Monitor initialization files |
| `FileWatcher` | `mockserver-core/.../persistence/FileWatcher.java` | Low-level file polling |
| `MockServerEventLogNotifier` | `mockserver-core/.../mock/listeners/MockServerEventLogNotifier.java` | Observer pattern base for log |
| `MockServerMatcherNotifier` | `mockserver-core/.../mock/listeners/MockServerMatcherNotifier.java` | Observer pattern base for matchers |

## LLM Action Types and Event Logging

LLM action types (`LLM_RESPONSE`) participate in the standard expectation matching and event logging pipeline. When an `httpLlmResponse` expectation matches, the handler produces the response and the event is logged as `EXPECTATION_RESPONSE` through the Disruptor ring buffer, exactly like any other response action.

The streaming path for LLM responses delegates to `HttpSseResponseActionHandler`, which emits events through the existing SSE handler infrastructure. SSE events are logged and streamed to the dashboard via the WebSocket observer, enabling real-time visibility of LLM mock responses.

Conversation-aware matchers (`LlmConversationMatcher`) evaluate during the normal matching pipeline in `HttpRequestPropertiesMatcher`. Parse failures on the request body are fail-closed (no match) and logged at DEBUG level. Oversize bodies exceeding `maxLlmConversationBodySize` are also fail-closed and logged at INFO level.

See [LLM Mocking](llm-mocking.md) for the complete architecture.

## Custom Log Event Listener

A programmatic callback can be registered to receive every log event processed by MockServer. This is useful for integrating MockServer logging into custom monitoring, alerting, or debugging systems.

The listener is set via `Configuration.logEventListener(Consumer<LogEntry> listener)` or the convenience method `ClientAndServer.setLogEventListener(Consumer<LogEntry> listener)`.

Implementation details:
- The listener reference is stored as a `volatile Consumer<LogEntry>` on `MockServerLogger`
- It is invoked synchronously on the Disruptor consumer thread (the same thread that processes all log events)
- A slow listener will slow down all log event processing — keep the callback fast
- The listener receives the full `LogEntry` object including type, timestamp, message, and associated HTTP objects
- Setting the listener to `null` removes it
- The listener is wired in `LifeCycle` constructor, which passes the `Configuration.logEventListener()` to `MockServerLogger`

## Traffic Diff

The traffic diff feature provides field-by-field comparison of two `HttpRequest` objects, enabling regression testing by comparing recorded HTTP sessions.

### Components

- **`FieldDiff`** (`org.mockserver.mock.diff.FieldDiff`) -- a data class representing a single field-level difference. Each diff has a `field` name, optional `expectedValue` and `actualValue`, and a `DiffType` (`ADDED`, `REMOVED`, `CHANGED`, `EQUAL`). Extends `ObjectWithReflectiveEqualsHashCodeToString` for standard equals/hashCode/toString support.

- **`TrafficDiffEngine`** (`org.mockserver.mock.diff.TrafficDiffEngine`) -- compares two `HttpRequest` objects and returns `List<FieldDiff>`. Diffed fields include:
  - `method` -- HTTP method comparison
  - `path` -- request path comparison
  - `body` -- body string comparison
  - `header.<key>` -- per-header comparison (case-insensitive keys, multi-value joined with commas)
  - `queryParam.<key>` -- per-query-parameter comparison (case-insensitive keys)
  - `cookie.<key>` -- per-cookie comparison (case-insensitive keys)

### API Endpoint

`PUT /mockserver/diff` accepts a JSON body with `expected` and `actual` fields, each containing a serialized `HttpRequest`. Returns a JSON response with `diffCount`, `identical` (boolean), and a `diffs` array of `FieldDiff` objects.

Example request:
```json
{
  "expected": { "method": "GET", "path": "/api/users" },
  "actual": { "method": "POST", "path": "/api/users" }
}
```

Example response:
```json
{
  "diffCount": 1,
  "identical": false,
  "diffs": [
    { "field": "method", "expectedValue": "GET", "actualValue": "POST", "diffType": "CHANGED" }
  ]
}
```
