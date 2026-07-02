# AI & RPC Protocol Mocking (SSE, WebSocket, JSON-RPC, MCP, A2A, gRPC)

## Overview

MockServer supports mocking AI protocol servers including MCP (Model Context Protocol) and A2A (Agent-to-Agent Protocol). This is distinct from MockServer's own MCP control plane (`/mockserver/mcp`) — these features enable mocking *other people's* MCP and A2A servers for testing.

**MockServer's own MCP control plane** (`/mockserver/mcp`) enforces both authentication and per-tool authorization when `controlPlaneAuthorizationEnabled=true`. `McpToolRegistry` classifies every tool as read or mutate (fail-closed — unclassified tools default to MUTATE), and `McpRequestProcessor` calls `HttpState.controlPlaneToolAuthorized` before executing any `tools/call`. A principal with only the READ role is `403`'d on mutating tools (`create_expectation`, `clear_expectations`, `reset`, etc.). See [tls-and-security.md](tls-and-security.md#control-plane-authentication) for the full authorization model.

## Architecture

### Core Building Blocks

Two primitive building blocks enable all AI protocol mocking:

1. **SSE Streaming Responses** (`HttpSseResponse`) — an `Action` type that streams Server-Sent Events to the client
2. **JSON-RPC Body Matching** (`JsonRpcBody`) — a `Body` matcher that validates JSON-RPC 2.0 method names and optionally validates `params` against a JSON Schema

### Higher-Level Builders

Built on top of the primitives:

- **`McpMockBuilder`** — generates a complete set of `Expectation[]` objects for a mock MCP server
- **`A2aMockBuilder`** — generates a complete set of `Expectation[]` objects for a mock A2A agent

### Layer Architecture

```mermaid
flowchart TB
    subgraph "Client API Layer"
        MCPBuilder["McpMockBuilder"]
        A2ABuilder["A2aMockBuilder"]
    end

    subgraph "Expectation Generation"
        JsonRpcBody["JsonRpcBody matcher"]
        JsonPathBody["JsonPathBody matcher"]
        VelocityTemplate["Velocity Templates"]
        SseResponse["HttpSseResponse action"]
    end

    subgraph "Core MockServer"
        Expectation["Expectation"]
        Matcher["HttpRequestPropertiesMatcher"]
        ActionHandler["HttpActionHandler"]
        SseHandler["HttpSseResponseActionHandler"]
        TemplateHandler["HttpResponseTemplateActionHandler"]
    end

    MCPBuilder --> JsonRpcBody
    MCPBuilder --> JsonPathBody
    MCPBuilder --> VelocityTemplate
    A2ABuilder --> JsonRpcBody
    A2ABuilder --> JsonPathBody
    A2ABuilder --> VelocityTemplate

    JsonRpcBody --> Matcher
    JsonPathBody --> Matcher
    VelocityTemplate --> TemplateHandler
    SseResponse --> SseHandler

    Matcher --> Expectation
    SseHandler --> ActionHandler
    TemplateHandler --> ActionHandler
```

## SSE Streaming Responses

### Model

- **`SseEvent`** (`mockserver-core/src/main/java/org/mockserver/model/SseEvent.java`) — a single SSE event with fields: `event`, `data`, `id`, `retry`, `delay`
- **`HttpSseResponse`** (`mockserver-core/src/main/java/org/mockserver/model/HttpSseResponse.java`) — action type extending `Action<HttpSseResponse>` with `statusCode`, `headers`, a list of `SseEvent` objects, and a `closeConnection` flag

### Action Type

`Action.Type.SSE_RESPONSE` was added to the `Action.Type` enum. `HttpActionHandler` routes requests matching an `HttpSseResponse` action to `HttpSseResponseActionHandler`.

### Handler

`HttpSseResponseActionHandler` (`mockserver-core/src/main/java/org/mockserver/mock/action/http/HttpSseResponseActionHandler.java`) writes the SSE stream directly via Netty's `ChannelHandlerContext`. It:

1. Writes HTTP response headers (`Content-Type: text/event-stream`, `Transfer-Encoding: chunked`, `Cache-Control: no-cache`, `Connection: keep-alive`) plus any custom headers from the action
2. Recursively schedules each event via `Scheduler`, using the per-event `Delay` if present or executing immediately if not
3. Formats each event per the SSE specification — multi-line `data` values are split into multiple `data:` lines; `id`, `event`, and `retry` fields are written when non-null
4. Writes `LastHttpContent.EMPTY_LAST_CONTENT` to terminate the chunked stream, then closes the channel if `closeConnection` is `true` (or null, which defaults to closing)

Since T1.2, streaming payloads can be **templated**: setting an optional `templateType` (`VELOCITY`/`MUSTACHE`/`JAVASCRIPT`) on the `httpSseResponse` (or `httpWebSocketResponse`, or per-message on `grpcStreamResponse`) renders each event's `data` (each WebSocket text frame / each gRPC message `json`) as a response template against the triggering request via the shared `StreamTemplateRenderer` — same request/template context as `httpResponseTemplate` (`$!request.body`, `$jsonPath(...)`, built-in helpers, `faker`, `scenario`). Rendering is per event/message and opt-in; with no `templateType` payloads are emitted byte-for-byte unchanged. See *Templated streaming payloads* in [request-processing.md](request-processing.md).

```mermaid
sequenceDiagram
    participant Client
    participant MockServer
    participant Handler as HttpSseResponseActionHandler
    participant Scheduler

    Client->>MockServer: GET /events
    MockServer->>Handler: SSE_RESPONSE action matched
    Handler->>Client: HTTP 200 headers (text/event-stream, chunked)
    loop For each SseEvent
        Handler->>Scheduler: schedule with per-event Delay
        Scheduler->>Handler: execute after delay
        Handler->>Client: SSE event chunk (id, event, retry, data lines)
    end
    Handler->>Client: LastHttpContent (terminates chunked transfer)
    Handler->>Client: close connection (if closeConnection true or null)
```

### Serialization

- **`SseEventDTO`** (`mockserver-core/.../serialization/model/SseEventDTO.java`) and **`HttpSseResponseDTO`** (`mockserver-core/.../serialization/model/HttpSseResponseDTO.java`) handle REST API serialization and deserialization
- **`ExpectationDTO`** includes an `httpSseResponse` field mapped to `HttpSseResponseDTO`
- `BodyDTODeserializer` and `StrictBodyDTODeserializer` handle the `JSON_RPC` body type
- `BodyDTO.createDTO()` maps `JsonRpcBody` to `JsonRpcBodyDTO`

## JSON-RPC Body Matching

### Model

**`JsonRpcBody`** (`mockserver-core/src/main/java/org/mockserver/model/JsonRpcBody.java`) extends `Body<String>` with `Body.Type.JSON_RPC`. It has two fields:

| Field | Required | Purpose |
|-------|----------|---------|
| `method` | Yes | Method name to match (exact string or Java regex) |
| `paramsSchema` | No | JSON Schema string; when present, `params` is validated against it |

### Matcher

**`JsonRpcMatcher`** (`mockserver-core/src/main/java/org/mockserver/matchers/JsonRpcMatcher.java`) validates:

1. `jsonrpc` field equals `"2.0"`
2. `method` field matches — first by exact equality, then by `String.matches()` (regex)
3. If `paramsSchema` is set, `params` is validated using `JsonSchemaValidator`; a missing `params` field fails validation
4. Batch requests (JSON arrays) — matches if **any** element in the array satisfies all the above conditions

### Integration Points

- `HttpRequestPropertiesMatcher.buildBodyMatcher()` — added the `JSON_RPC` case to route to `JsonRpcMatcher`
- `BodyDTODeserializer` and `StrictBodyDTODeserializer` — support two JSON representations:
  - Typed: `{"type": "JSON_RPC", "method": "tools/list"}`
  - Wrapped: `{"jsonRpc": {"method": "tools/list"}}`

### Template Object Enhancement

`HttpRequestTemplateObject` (`mockserver-core/.../templates/engine/model/HttpRequestTemplateObject.java`) was extended with three fields extracted from JSON-RPC request bodies:

| Field | Velocity variable | Value |
|-------|-------------------|-------|
| `jsonRpcId` | `$!{request.jsonRpcId}` | String representation of the ID (text nodes use text value; numeric/null use `toString()`) |
| `jsonRpcRawId` | `$!{request.jsonRpcRawId}` | Raw JSON representation — preserves `1` for numbers and `"abc"` for strings; used for embedding directly in JSON response bodies |
| `jsonRpcMethod` | `$!{request.jsonRpcMethod}` | The `method` field value |

Extraction is best-effort: any parse error is silently swallowed, leaving all three fields null.

## WebSocket Mocking

### Model

- **`WebSocketMessage`** (`mockserver-core/.../model/WebSocketMessage.java`) — Single WebSocket message with `text`, `binary`, and `delay` fields
- **`HttpWebSocketResponse`** (`mockserver-core/.../model/HttpWebSocketResponse.java`) — Action type extending `Action<HttpWebSocketResponse>` with `subprotocol`, `messages` list, and `closeConnection` flag

### Action Type

`Action.Type.WEBSOCKET_RESPONSE` was added to the enum. This triggers the `HttpWebSocketResponseActionHandler`.

### Handler

`HttpWebSocketResponseActionHandler` performs the WebSocket handshake using Netty's `WebSocketServerHandshakerFactory`, then sends configured messages as `TextWebSocketFrame` or `BinaryWebSocketFrame`. It:

1. Reconstructs a Netty `FullHttpRequest` from the MockServer `HttpRequest` (preserving headers including `Sec-WebSocket-Key`)
2. Performs the WebSocket handshake
3. Removes HTTP codecs from the pipeline
4. Sends each message with optional per-message delays
5. Optionally sends `CloseWebSocketFrame` and closes the connection

### Usage

```java
mockServerClient.when(
    request().withMethod("GET").withPath("/ws")
).respondWithWebSocket(
    HttpWebSocketResponse.webSocketResponse()
        .withMessage(WebSocketMessage.webSocketMessage("hello"))
        .withMessage(WebSocketMessage.webSocketMessage("world"))
        .withCloseConnection(true)
);
```

## Realtime Voice API Mocking (OpenAI Realtime, Gemini Live)

### Outcome

MockServer mocks the two dominant realtime (voice) LLM protocols — the **OpenAI Realtime API** (GA 2025 event
protocol) and the **Google Gemini Live API** (`BidiGenerateContent`) — so an agent/app that speaks them can be
tested fully offline, with no real API and no audio hardware. It is a thin layer over the existing WebSocket mock
primitive: **no new `Action.Type`, DTO, or JSON schema**. A pure event codec generates the provider-correct event
JSON; the Java client `RealtimeMockBuilder` wires that into a single `httpWebSocketResponse` expectation (initial
pushed frame + per-incoming-frame matchers), exactly as A2A streaming reuses `httpSseResponse`.

```mermaid
flowchart LR
    Builder["RealtimeMockBuilder\n(client-java)"] --> Codec["OpenAiRealtimeCodec /\nGeminiLiveCodec\n(core, pure)"]
    Codec --> WS["HttpWebSocketResponse\nmessages + matchers"]
    WS --> Handler["HttpWebSocketResponseActionHandler\n+ BidirectionalWebSocketFrameHandler"]
    Handler --> Client["Realtime SDK client"]
```

### Components

| Class | Module | Package | Purpose |
|-------|--------|---------|---------|
| `RealtimeProvider` | core | `org.mockserver.llm.realtime` | `OPENAI_REALTIME` / `GEMINI_LIVE` (separate from the HTTP `Provider` enum) |
| `RealtimeModality` | core | `org.mockserver.llm.realtime` | `AUDIO` (transcript + audio deltas) or `TEXT` |
| `RealtimeTurn` | core | `org.mockserver.llm.realtime` | Provider-neutral scripted assistant turn (text, audio transcript, audio bytes, usage) — the realtime analogue of `Completion` |
| `RealtimeStreamingPhysics` | core | `org.mockserver.llm.realtime` | Deterministic `tokensPerSecond` + time-to-first-token timing (jitter-free WS analogue of `StreamingPhysics`) |
| `RealtimeEvent` | core | `org.mockserver.llm.realtime` | One rendered event frame `{json, delayMillis}` |
| `OpenAiRealtimeCodec` | core | `org.mockserver.llm.realtime` | Pure OpenAI Realtime event codec |
| `GeminiLiveCodec` | core | `org.mockserver.llm.realtime` | Pure Gemini Live event codec |
| `RealtimeMockBuilder` | client-java | `org.mockserver.client` | Builds the `httpWebSocketResponse` expectation |

### How the flow maps onto the WebSocket primitive

The realtime session is inherently request-driven, which is exactly what the WebSocket mock's `matchers` model
(incoming frame → scripted `responses`) provides, plus one connect-time push (`messages`):

- **OpenAI** — `session.created` is pushed on connect (`messages`); matchers answer `session.update` →
  `session.updated`, `conversation.item.create` → `conversation.item.created`, and `response.create` → the full
  scripted response event sequence.
- **Gemini** — nothing is pushed on connect; matchers answer `setup` → `setupComplete` and `clientContent` → the
  scripted `serverContent` chunk stream. Matchers use a DOTALL regex on the distinctive top-level key (`"setup"`,
  `"clientContent"`) / OpenAI `"type"` value.

Because the bidirectional matcher schedules a match's response frames **concurrently** (each with a delay relative
to the match instant), the builder converts the codec's per-event gaps into **monotonically-increasing cumulative
absolute** delays (`RealtimeMockBuilder.toMessages`, `MIN_STEP_MILLIS` floor) so the event stream stays strictly
ordered. The same script answers every matching frame, so a client that repeats `response.create` /
`clientContent` receives the scripted turn each time. `closeConnection` is `false` — the client owns disconnect.

### Protocol coverage matrix (event type → status)

**OpenAI Realtime (server events)**

| Event | Status |
|-------|--------|
| `session.created` (connect push) | ✅ mocked |
| `session.updated` (← `session.update`) | ✅ mocked |
| `conversation.item.created` (← `conversation.item.create`) | ✅ mocked |
| `response.created` | ✅ mocked |
| `response.output_item.added` | ✅ mocked |
| `response.content_part.added` | ✅ mocked |
| `response.output_audio_transcript.delta` / `.done` | ✅ mocked (AUDIO) |
| `response.output_audio.delta` / `.done` | ✅ mocked (AUDIO, silence placeholder bytes) |
| `response.output_text.delta` / `.done` | ✅ mocked (TEXT) |
| `response.content_part.done`, `response.output_item.done` | ✅ mocked |
| `response.done` (with `usage`) | ✅ mocked |
| `input_audio_buffer.*` / server VAD (`speech_started`/`stopped`) | ⛔ deferred |
| `conversation.item.input_audio_transcription.*` | ⛔ deferred |
| function-call output items, `rate_limits.updated`, `error` | ⛔ deferred |

**Gemini Live (server messages)**

| Message | Status |
|---------|--------|
| `setupComplete` (← `setup`) | ✅ mocked |
| `serverContent.modelTurn` text parts (← `clientContent`) | ✅ mocked (TEXT) |
| `serverContent.modelTurn` `inlineData` audio + `outputTranscription` | ✅ mocked (AUDIO, silence placeholder bytes) |
| `serverContent.generationComplete` / `turnComplete` | ✅ mocked |
| `usageMetadata` | ✅ mocked |
| `realtimeInput` / `realtimeInputAcknowledgement` | ⛔ deferred |
| `toolCall` / `toolCallCancellation` / `toolResponse` | ⛔ deferred |
| `goAway`, `sessionResumptionUpdate`, `interrupted` | ⛔ deferred |

Audio bytes are opaque silence placeholders — the fidelity target is the **event protocol**, not audio DSP.

### Usage

```java
import static org.mockserver.llm.realtime.RealtimeTurn.realtimeTurn;

// OpenAI Realtime — point the SDK at ws://localhost:1080/v1/realtime
RealtimeMockBuilder.openAiRealtime()
    .withModel("gpt-realtime")
    .respondingWith(realtimeTurn("The capital of France is Paris.")
        .withInputTokens(20).withOutputTokens(7))
    .applyTo(mockServerClient);

// Gemini Live
RealtimeMockBuilder.geminiLive()
    .respondingWith("Bonjour le monde")
    .applyTo(mockServerClient);
```

## MCP Mock Builder

### Purpose

`McpMockBuilder` generates a complete set of `Expectation[]` objects that make MockServer behave as a mock MCP server. This allows testing MCP clients against a predictable, configurable mock.

### Location

`mockserver-client-java/src/main/java/org/mockserver/client/McpMockBuilder.java`

### Defaults

| Property | Default |
|----------|---------|
| `path` | `/mcp` |
| `serverName` | `MockMCPServer` |
| `serverVersion` | `1.0.0` |
| `protocolVersion` | `2025-06-18` |

### Protocol Version Negotiation (server)

MockServer's own MCP server (`McpRequestProcessor`) advertises and negotiates the **2025-06-18** MCP spec revision, while remaining backward compatible with older clients:

- The client sends its preferred `protocolVersion` in `initialize`. When it is one the server supports (`2025-06-18`, `2025-03-26`, `2024-11-05`) the server **echoes it back**; otherwise (or when omitted) the server replies with its latest, `2025-06-18` (`negotiateProtocolVersion`).
- The negotiated version is stored on the `McpSession` and governs whether version-specific response fields are emitted for that session.
- `Mcp-Session-Id` is emitted on the `initialize` response and required (and echoed) on subsequent requests — already handled by `McpStreamableHttpHandler`.

### 2025-06-18 Capabilities

| Capability | Server (`McpRequestProcessor`) | Mock builder (`McpMockBuilder`) |
|---|---|---|
| Structured tool output (`structuredContent`) | `tools/call` results include `structuredContent` (the raw tool-result object) when the session negotiated 2025-06-18+ | `respondingWithStructured(text, structuredJson)` + `withOutputSchema(schema)` (advertised in `tools/list`) |
| Resource links (`type: resource_link`) | — | `respondingWithResourceLink(uri, name, description, mimeType)` on a `tools/call` result |
| `Mcp-Session-Id` | Emitted on `initialize`, required on subsequent requests | Session handling is the client's responsibility against the mock |

**Deferred (require a server→client push channel):** `elicitation/create` (server-initiated) and the GET SSE server-push stream are not mocked — MockServer's request/response expectation model has no channel to initiate requests to the client. `sampling/createMessage` remains a deterministic mocked completion on the server. JSON-RPC batching (removed in 2025-06-18) is still accepted for back-compat with older clients.

### Generated Expectations

| MCP Method | Request Matcher | Response Type |
|---|---|---|
| `initialize` | `POST {path}` + `JsonRpcBody("initialize")` | Velocity template — echoes `jsonRpcRawId`, returns server info and capabilities |
| `ping` | `POST {path}` + `JsonRpcBody("ping")` | Velocity template — echoes `jsonRpcRawId`, returns `{}` |
| `notifications/initialized` | `POST {path}` + `JsonRpcBody("notifications/initialized")` | Static `HttpResponse` 200 with empty JSON body |
| `tools/list` | `POST {path}` + `JsonRpcBody("tools/list")` | Velocity template — returns configured tools array |
| `tools/call` (per tool) | `POST {path}` + `JsonPathBody` matching `method == 'tools/call'` and `params.name == '{toolName}'` | Velocity template — returns text content and `isError` flag |
| `resources/list` | `POST {path}` + `JsonRpcBody("resources/list")` | Velocity template — returns configured resources array |
| `resources/read` (per resource) | `POST {path}` + `JsonPathBody` matching `method == 'resources/read'` and `params.uri == '{uri}'` | Velocity template — returns resource `text` and `mimeType` |
| `prompts/list` | `POST {path}` + `JsonRpcBody("prompts/list")` | Velocity template — returns configured prompts array |
| `prompts/get` (per prompt) | `POST {path}` + `JsonPathBody` matching `method == 'prompts/get'` and `params.name == '{promptName}'` | Velocity template — returns messages array |

The `tools/list`, `resources/list`, and `prompts/list` expectations are generated whenever tools, resources, or prompts are registered respectively, or when the corresponding capability flag (`withToolsCapability()`, etc.) is explicitly set.

### JSON-RPC ID Echoing

All Velocity templates embed `$!{request.jsonRpcRawId}` as the `id` field in the JSON-RPC response body. This preserves the original ID type (number or string) and ensures correct request-response correlation for MCP clients.

### Usage

```java
McpMockBuilder.mcpMock("/mcp")
    .withServerName("TestMCP")
    .withServerVersion("1.0.0")
    .withTool("get_weather")
        .withDescription("Get weather for a city")
        .respondingWith("72F and sunny")
        .and()
    .withResource("config://app")
        .withName("App Config")
        .withMimeType("application/json")
        .withContent("{\"debug\": true}")
        .and()
    .withPrompt("summarize")
        .withDescription("Summarize text")
        .withArgument("text", "Text to summarize", true)
        .respondingWith("assistant", "Here is your summary.")
        .and()
    .applyTo(mockServerClient);
```

`applyTo(MockServerClient)` calls `client.upsert(build())`. `build()` can also be called directly to obtain the `Expectation[]` array without applying it.

## A2A Mock Builder

### Purpose

`A2aMockBuilder` generates expectations for a mock A2A (Agent-to-Agent Protocol) agent. The A2A protocol uses JSON-RPC 2.0 over HTTP with an Agent Card discovery mechanism (`GET /.well-known/agent.json`).

### Location

`mockserver-client-java/src/main/java/org/mockserver/client/A2aMockBuilder.java`

### Defaults

| Property | Default |
|----------|---------|
| `path` | `/a2a` |
| `agentCardPath` | `/.well-known/agent.json` |
| `agentName` | `MockAgent` |
| `agentDescription` | `A mock A2A agent` |
| `agentVersion` | `1.0.0` |
| `agentUrl` | `http://localhost{path}` (derived) |
| `defaultTaskResponse` | `Task completed successfully` |

### Generated Expectations

| Endpoint | Request Matcher | Response Type |
|---|---|---|
| Agent Card | `GET {agentCardPath}` | Static `HttpResponse` — JSON agent card with name, description, version, url, capabilities, and skills |
| `tasks/send` | `POST {path}` + `JsonRpcBody("tasks/send")` | Velocity template — completed task with default response text |
| `tasks/get` | `POST {path}` + `JsonRpcBody("tasks/get")` | Velocity template — completed task with default response text |
| `tasks/cancel` | `POST {path}` + `JsonRpcBody("tasks/cancel")` | Velocity template — canceled task with `status.state: "canceled"` |
| Custom task handlers (per handler) | `POST {path}` + `JsonPathBody` matching `method == 'tasks/send'` and `params.message.parts[0].text =~ /{pattern}/` | Velocity template — completed or failed task with custom response text |

Custom task handlers are evaluated in registration order. Because MockServer matches expectations in priority/registration order, more specific handlers should be registered before the generic `tasks/send` catch-all.

### Streaming and Push Notifications

Both A2A capabilities are opt-in and additive — by default the agent card still advertises `streaming: false` and `pushNotifications: false`, and `build()` produces the same expectations as before.

| Builder method | Effect |
|---|---|
| `withStreaming()` / `withStreamingMethod(String)` | Agent card advertises `capabilities.streaming: true`. Adds an `httpSseResponse` expectation matching `POST {path}` + `JsonRpcBody({streamingMethod})` (default `message/stream`, legacy `tasks/sendSubscribe`). The SSE stream emits three events, each a JSON-RPC 2.0 response envelope: a `TaskStatusUpdateEvent` with `status.state: working` (`final: false`), a `TaskArtifactUpdateEvent` carrying the default task response text, and a final `TaskStatusUpdateEvent` with `status.state: completed` (`final: true`). The expectation reuses the existing `HttpSseResponse` / `HttpSseResponseActionHandler` SSE infrastructure. |
| `withPushNotifications(webhookUrl)` | Agent card advertises `capabilities.pushNotifications: true`. Adds a `tasks/pushNotificationConfig/set` expectation that echoes the registered config (`{"url": "..."}`), and replaces the generic `tasks/send` expectation with an `HttpOverrideForwardedRequest` that POSTs the completed task (the push payload) to the parsed webhook host/port/scheme/path. The caller's JSON-RPC response is produced by a Velocity *response template* so the request's id is echoed (`$!{request.jsonRpcRawId}`), matching the non-push `tasks/send` contract. |

Because the streaming and push-delivery expectations match `message/stream` / `tasks/send` respectively and are registered before the generic `tasks/send` catch-all (which is omitted when push is configured), they take precedence in registration order.

Notes and limitations:

- **Escaping**: the caller response is Velocity-templated (response text is Velocity-escaped so `$`/`#` render literally), whereas the webhook POST body is a literal request override (JSON-escaped only — no Velocity escaping, which would corrupt `$`/`#`).
- **Custom handlers + push**: push delivery fires only for the generic `tasks/send` catch-all. Custom `onTaskSend(...)` handlers still return a task response to the caller but do not POST to the webhook.
- **Delivery failures are non-fatal-but-visible**: the caller response template renders only when the webhook returns a response; if the webhook is unreachable the caller receives the forward error rather than a synthesised 200.
- **Streaming JSON-RPC id**: the A2A builder's SSE event envelopes use a fixed placeholder id (`"1"`); streaming clients correlate by the stream itself. Since T1.2, `HttpSseResponse` (and `HttpWebSocketResponse` / gRPC `grpcStreamResponse`) support an optional `templateType`, so a hand-authored streaming expectation *can* now template each event/message payload — e.g. echo the request's JSON-RPC id via `$jsonPath.find("$.id")` — against the triggering request. The A2A builder itself still emits static envelopes.

### Usage

```java
A2aMockBuilder.a2aMock("/agent")
    .withAgentName("TranslationAgent")
    .withAgentDescription("Translates text between languages")
    .withSkill("translate")
        .withName("Translation")
        .withDescription("Translates text")
        .withTag("nlp")
        .and()
    .onTaskSend()
        .matchingMessage("translate.*")
        .respondingWith("Bonjour")
        .and()
    .applyTo(mockServerClient);
```

## gRPC Mocking

MockServer supports mocking gRPC services without requiring grpc-java as a dependency. Instead, it uses a pure Netty pipeline approach: gRPC requests are decoded from HTTP/2 + protobuf framing into JSON, routed through the existing matching engine as `POST /<service>/<method>`, and responses are re-encoded back to gRPC framing. This means all existing JSON/JSONPath/JSONSchema matchers work with gRPC automatically.

### Architecture

```mermaid
flowchart LR
    Client["gRPC Client"] -->|HTTP/2 + protobuf| GRH["GrpcToHttpRequestHandler\n(decode protobuf→JSON)"]
    GRH -->|POST /service/method\nJSON body| MH["HttpRequestHandler\n(standard matching)"]
    MH -->|JSON response| GWH["GrpcToHttpResponseHandler\n(encode JSON→protobuf)"]
    GWH -->|HTTP/2 + protobuf\n+ grpc-status trailers| Client
```

### Proto Descriptor Infrastructure

gRPC mocking requires proto descriptors so MockServer can convert between protobuf binary and JSON. Three loading mechanisms are supported:

| Mechanism | Config Property | Description |
|-----------|----------------|-------------|
| Descriptor files (`.dsc`/`.desc`) | `grpcDescriptorDirectory` | Directory of pre-compiled descriptor set files |
| Proto source files (`.proto`) | `grpcProtoDirectory` | Directory of `.proto` files compiled at startup via `protoc` |
| Runtime REST API upload | `PUT /mockserver/grpc/descriptors` | Upload descriptor bytes at runtime via client API |

Core classes:

| Class | Module | Purpose |
|-------|--------|---------|
| `GrpcProtoDescriptorStore` | core | Registry of loaded service/method descriptors, provides converters |
| `GrpcProtoFileCompiler` | core | Compiles `.proto` files to descriptors via `protoc` |
| `GrpcJsonMessageConverter` | core | Converts protobuf binary ↔ JSON using `com.google.protobuf.util.JsonFormat` |
| `GrpcFrameCodec` | core | Encodes/decodes the 5-byte gRPC length-prefixed framing |
| `GrpcStatusMapper` | core | Maps between gRPC status codes and names |

### Netty Pipeline Integration

gRPC handlers are conditionally inserted into both h2c (HTTP/2 cleartext) and TLS-negotiated HTTP/2 pipelines when the descriptor store has loaded services:

```mermaid
graph LR
    H2C["HTTP/2 Connection Handler"] --> CB[CallbackWebSocketServerHandler]
    CB --> DASH[DashboardWebSocketHandler]
    DASH --> CODEC[MockServerHttpServerCodec]
    CODEC --> GRPC_RESP["GrpcToHttpResponseHandler"]
    GRPC_RESP --> GRPC_REQ["GrpcToHttpRequestHandler"]
    GRPC_REQ --> HANDLER[HttpRequestHandler]
```

The handlers are placed after `MockServerHttpServerCodec` so they operate on MockServer model objects. `GrpcToHttpRequestHandler` intercepts inbound `HttpRequest` objects with `content-type: application/grpc`, extracts the service and method from the path, decodes the protobuf body to JSON, and forwards with `x-grpc-service`, `x-grpc-method` headers.

`GrpcToHttpResponseHandler` is an outbound encoder that intercepts `HttpResponse` objects with `x-grpc-service` header, encodes the JSON body back to protobuf binary with gRPC framing, and appends `grpc-status` / `grpc-message` trailers.

### gRPC-Web Support

gRPC-Web is a variant of gRPC designed for browser clients that cannot use HTTP/2 trailers. MockServer supports gRPC-Web as a translation layer in front of the existing gRPC pipeline:

**Content types:** `application/grpc-web`, `application/grpc-web+proto` (binary), `application/grpc-web-text`, `application/grpc-web-text+proto` (base64-encoded).

**Request path:**
1. `GrpcToHttpRequestHandler` detects gRPC-Web content types and calls `translateGrpcWebRequest()` before any gRPC processing
2. For the `-text` variant, the body is base64-decoded
3. The content-type is replaced with `application/grpc` and the original content-type is stored in `x-grpc-web-content-type` header
4. The translated request passes through the normal gRPC pipeline unchanged

**Response path:**
1. `GrpcToHttpResponseHandler.encode()` checks for the `x-grpc-web-content-type` header on outbound responses
2. If present, `convertToGrpcWebResponse()` re-frames the response: `grpc-status`/`grpc-message` headers are removed and embedded in a trailer frame (flag byte `0x80`) appended to the message body
3. For the `-text` variant, the entire body (message frames + trailer frame) is base64-encoded
4. The response content-type is set to the matching gRPC-Web type

**Pipeline placement:** gRPC handlers are added to the HTTP/1.1 pipeline (in `switchToHttp()`) in addition to the HTTP/2 pipelines, since gRPC-Web works over both HTTP/1.1 and HTTP/2.

**Core class:** `GrpcWebTranslator` (`mockserver-core`, `org.mockserver.grpc`) provides the encoding/decoding utilities (trailer frame construction, base64 handling, content-type detection). The handler modifications in `mockserver-netty` are localized to the existing `GrpcToHttpRequestHandler` and `GrpcToHttpResponseHandler`.

**Content-type discrimination:** `GrpcStatusMapper.isGrpcContentType()` explicitly excludes `application/grpc-*` prefixes (e.g. `application/grpc-web`) so that gRPC-Web requests are not misrouted through the standard gRPC path.

**Connect protocol (unary):** Supported as a convenience layer over plain HTTP — see [Connect Protocol (Unary)](#connect-protocol-unary) below. Connect streaming is not supported.

### Connect Protocol (Unary)

Connect (buf.build Connect) **unary** RPCs are, unlike gRPC, ordinary HTTP `POST` requests to `/package.Service/Method` carrying the request message **directly** (JSON or proto) with `Content-Type: application/json` (or `application/proto`) — there is no gRPC length-prefixed framing and no HTTP/2 trailer envelope. Because they are plain HTTP, **MockServer's normal expectation matching already handles them**: a user can mock a Connect unary call with a standard `httpRequest`/`httpResponse` expectation (body matchers, headers, delays, verification, the dashboard all work unchanged). The Connect support is therefore a thin **convenience + correctness** layer, not a new protocol pipeline — no new `Action.Type`, DTO, JSON schema, or Netty handler, and **real gRPC (`application/grpc`) traffic is completely unaffected** because nothing in the gRPC pipeline is touched.

| Class | Module | Package | Purpose |
|-------|--------|---------|---------|
| `ConnectError` | core | `org.mockserver.grpc.connect` | The Connect error model `{code, message, details}` plus the canonical Connect error-code ↔ HTTP-status mapping (`Code` enum) |
| `ConnectResponse` | core | `org.mockserver.grpc.connect` | Static factory returning a plain `HttpResponse`: `success(json)` (HTTP 200 + `application/json`) and `error(ConnectError)` (mapped non-200 + JSON error envelope) |
| `ConnectUnaryDetector` | core | `org.mockserver.grpc.connect` | Conservative detection of Connect unary requests (POST + `/pkg.Svc/Method` path + JSON/proto, never `application/grpc*`) and optional descriptor-aware request-body validation |

**Connect error code ↔ HTTP status** (Connect codes are the lower-case snake_case forms of the gRPC status names; mapping per the [Connect spec](https://connectrpc.com/docs/protocol#error-codes) / `connectrpc/connect-go` `codeToHTTP`):

| Connect code | HTTP status | Connect code | HTTP status |
|--------------|-------------|--------------|-------------|
| `canceled` | 499 | `aborted` | 409 |
| `unknown` | 500 | `out_of_range` | 400 |
| `invalid_argument` | 400 | `unimplemented` | 501 |
| `deadline_exceeded` | 504 | `internal` | 500 |
| `not_found` | 404 | `unavailable` | 503 |
| `already_exists` | 409 | `data_loss` | 500 |
| `permission_denied` | 403 | `unauthenticated` | 401 |
| `resource_exhausted` | 429 | `failed_precondition` | 400 |

There is no Connect code for gRPC `OK`; a successful unary response is an HTTP 200 with the message body, not an error envelope.

**Usage (Java client):**

```java
// success: HTTP 200, application/json, body is the response message directly
mockServerClient
    .when(request().withMethod("POST").withPath("/pkg.Svc/Method"))
    .respond(ConnectResponse.success("{\"greeting\":\"Hello World\"}"));

// error: HTTP 404, {"code":"not_found","message":"..."}
mockServerClient
    .when(request().withMethod("POST").withPath("/pkg.Svc/Method"))
    .respond(ConnectResponse.error(ConnectError.Code.NOT_FOUND, "greeting not found"));
```

**Deferred:** Connect server/bidi streaming (the `application/connect+json` framed stream), the GET-side unary variant, and request/response compression.

### h2c Detection

`PortUnificationHandler.decode()` includes `isH2cPreface()` which detects the HTTP/2 connection preface (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`) on cleartext connections. When detected, `switchToH2c()` assembles the HTTP/2 pipeline with gRPC handlers, enabling gRPC over plaintext HTTP/2.

### Streaming Support

`GrpcStreamResponse` is an action type for gRPC server streaming (and as a building block for other streaming patterns). It follows the same recursive scheduling pattern as `HttpSseResponse`:

| Class | Module | Purpose |
|-------|--------|---------|
| `GrpcStreamMessage` | core (model) | A single message in a stream: JSON body + optional per-message `Delay` |
| `GrpcStreamResponse` | core (model) | Action containing a list of `GrpcStreamMessage` objects and a `statusCode` |
| `GrpcStreamResponseActionHandler` | core (action) | Recursively schedules messages via `Scheduler`, encodes each to gRPC-framed protobuf, writes `grpc-status` trailers after last message |

```mermaid
sequenceDiagram
    participant Client
    participant MockServer
    participant Handler as GrpcStreamResponseActionHandler
    participant Scheduler

    Client->>MockServer: gRPC request (e.g., ListGreetings)
    MockServer->>Handler: GRPC_STREAM_RESPONSE action matched
    Handler->>Client: HTTP/2 200 headers (content-type: application/grpc)
    loop For each GrpcStreamMessage
        Handler->>Scheduler: schedule with per-message Delay
        Scheduler->>Handler: execute after delay
        Handler->>Client: gRPC-framed protobuf message
    end
    Handler->>Client: grpc-status trailers (OK)
```

### Serialization

- **`GrpcStreamMessageDTO`** and **`GrpcStreamResponseDTO`** handle REST API serialization
- **`ExpectationDTO`** includes a `grpcStreamResponse` field mapped to `GrpcStreamResponseDTO`
- **`grpcStreamResponse.json`** JSON schema is registered in `JsonSchemaExpectationValidator`

### gRPC Fault Injection

`GrpcChaosProfile` (`org.mockserver.model.GrpcChaosProfile`) is a declarative gRPC fault/chaos injection profile. It is stored in a `GrpcChaosRegistry` keyed by gRPC service name and applied by `GrpcToHttpRequestHandler` before normal request conversion. An empty-string key registers a default profile that covers all services without a more-specific override.

**Profile fields:**

| Field | Type | Description |
|-------|------|-------------|
| `errorStatusCode` | String | gRPC status code name (e.g. `"UNAVAILABLE"`, `"DEADLINE_EXCEEDED"`) — one of the 17 `GrpcStatusMapper.GrpcStatusCode` enum names |
| `errorMessage` | String | Optional `grpc-message` trailer value |
| `errorProbability` | Double | 0.0–1.0 probability of fault injection; null/0 = never, 1.0 = always |
| `seed` | Long | Optional seed to make fractional probability reproducible |
| `latencyMs` | Long | Milliseconds of artificial delay before the response; >= 0 |
| `succeedFirst` | Integer | First N calls per service are not eligible for chaos; >= 0 |
| `failRequestCount` | Integer | After `succeedFirst`, the next M calls are eligible; >= 1; null = unlimited |
| `quotaName` | String | Shared rate-limit counter key |
| `quotaLimit` | Integer | Max calls allowed per quota window; >= 1 |
| `quotaWindowMillis` | Long | Fixed-window length in ms; calls over the limit return `RESOURCE_EXHAUSTED`; >= 1 |
| `omitGrpcStatus` | Boolean | When true, the fault response contains no `grpc-status` trailer at all, simulating an incomplete or broken RPC stream. Takes precedence over `corruptGrpcStatus` when both are set. |
| `corruptGrpcStatus` | Boolean | When true (and `omitGrpcStatus` is false), the `grpc-status` trailer is set to the non-numeric value `"malformed"` — a genuine protocol violation (the gRPC spec requires `grpc-status` to be a decimal integer) that tests how clients cope with an unparseable status trailer. |
| `customTrailers` | Map&lt;String,String&gt; | Arbitrary trailer key/value pairs injected on the fault response in addition to (or instead of) the normal status trailers. Applied after `omitGrpcStatus`/`corruptGrpcStatus` — always added regardless of which status variant fires. |
| `abortAfterMessages` | Integer | For client-streaming requests: when the number of decoded gRPC messages in the request body is >= this threshold, inject an `ABORTED` status immediately. The message count is determined by decoding the 5-byte gRPC length-prefixed frames in the request body; >= 1. |

**Trailer-fault precedence in `buildFaultResponse`:** `omitGrpcStatus: true` → no `grpc-status` header is written at all; else `corruptGrpcStatus: true` → `grpc-status: malformed` is written (a non-numeric value that violates the gRPC wire spec); else the normal numeric status code is written. `customTrailers` are always appended after the status decision, for every fault response. Custom trailer keys and values are validated against CR/LF injection at the model layer and defensively skipped at the handler layer.

Serialization uses `GrpcChaosProfileDTO` (`org.mockserver.serialization.model.GrpcChaosProfileDTO`).

This feature is distinct from `GrpcHealthRegistry` — gRPC fault injection fires on application RPC methods; health-check chaos controls the `grpc.health.v1.Health/Check` serving-status response.

**REST endpoints:**

| Endpoint | Action |
|----------|--------|
| `PUT /mockserver/grpcChaos` | Register, remove, or clear gRPC chaos profiles; supports `ttlMillis` for auto-expiry |
| `GET /mockserver/grpcChaos` | Read all active profiles and TTL countdowns |
| `PATCH /mockserver/grpcChaos` | JSON Merge Patch a single service's profile (preserves TTL) |

See [Service-scoped chaos REST API](#service-scoped-chaos-rest-api) below for the full request/response shapes, which are identical across all three endpoints (substituting `service` for the key field and `GrpcChaosProfile` fields in the `chaos` object).

### Service-Scoped Chaos REST API

Three parallel REST APIs expose service-scoped chaos registration — one for each protocol layer. All three follow the same request/response structure; the differences are the endpoint path, the key field name (`host` vs `service`), and the profile type (`HttpChaosProfile` vs `TcpChaosProfile` vs `GrpcChaosProfile`).

| Protocol | Endpoints | Key field | Profile type |
|----------|-----------|-----------|--------------|
| HTTP | `PUT/GET/PATCH /mockserver/serviceChaos` | `host` | `HttpChaosProfile` (see profile fields in the consumer chaos docs) |
| TCP | `PUT/GET/PATCH /mockserver/tcpChaos` | `host` | `TcpChaosProfile` |
| gRPC | `PUT/GET/PATCH /mockserver/grpcChaos` | `service` | `GrpcChaosProfile` (fields documented above) |

#### PUT — register, remove, or clear

Request body shapes (all fields except `clear`/`host`/`service` are optional):

**Register or replace a profile** — sets or replaces the chaos profile for a single host/service:

```json
{
  "host": "payments.internal:8080",
  "chaos": { "errorStatus": 503, "errorProbability": 0.3 },
  "ttlMillis": 60000
}
```

- `ttlMillis` (optional, `>= 1`) — auto-reverts the registration after this many milliseconds. When the TTL expires the profile is removed and the host returns to normal behaviour.
- Omitting `ttlMillis` registers the profile indefinitely.

**Remove a single host** — omit `chaos` or supply `remove: true`:

```json
{ "host": "payments.internal:8080", "remove": true }
```

**Clear all registrations**:

```json
{ "clear": true }
```

`clear` and `host`/`service` are mutually exclusive.

**Responses** — all 200 with a `status` field:

| Scenario | Response body |
|----------|--------------|
| Registered | `{"status":"registered","host":"...","ttlMillis":60000}` (ttlMillis omitted when no TTL) |
| Removed | `{"status":"removed","host":"..."}` |
| Cleared | `{"status":"cleared"}` |
| Error | 400 `{"error":"<message>"}` |

#### GET — snapshot

Returns all currently registered profiles. For `serviceChaos` the top-level key is `services`; for `tcpChaos` it is `hosts`. A `ttlRemainingMillis` map is included only when at least one TTL-bearing registration exists.

`GET /mockserver/serviceChaos` example response:

```json
{
  "services": {
    "payments.internal:8080": { "errorStatus": 503, "errorProbability": 0.3 }
  },
  "ttlRemainingMillis": {
    "payments.internal:8080": 42310
  }
}
```

`GET /mockserver/tcpChaos` uses `hosts` as the outer key instead of `services`.

`GET /mockserver/grpcChaos` uses `services` and the keys are gRPC service names (e.g. `helloworld.Greeter`); an empty string key is the catch-all default profile.

#### PATCH — merge-patch a single profile

Only the fields present in the `chaos` object are updated; all other fields of the existing profile are preserved. The TTL on the existing registration is also preserved (the PATCH does not reset or remove it).

Request body:

```json
{
  "host": "payments.internal:8080",
  "chaos": { "errorProbability": 0.5 }
}
```

Both `host`/`service` and `chaos` are required. A missing key returns 400.

Response body on success:

```json
{
  "status": "patched",
  "host": "payments.internal:8080",
  "chaos": { "errorStatus": 503, "errorProbability": 0.5 }
}
```

The `chaos` field in the response reflects the merged profile as serialised by the corresponding `*ChaosProfileDTO`.

**Implementation references:** all nine handlers (`handleServiceChaosPut`, `handleServiceChaosPatch`, `handleServiceChaosGet`, `handleTcpChaosPut`, `handleTcpChaosPatch`, `handleTcpChaosGet`, `handleGrpcChaosPut`, `handleGrpcChaosPatch`, `handleGrpcChaosGet`) are in `mockserver/mockserver-core/src/main/java/org/mockserver/mock/HttpState.java` around lines 2070–2503.

### GraphQL-Semantic HTTP Chaos

`HttpChaosProfile` carries four fields for injecting GraphQL-semantic errors into HTTP responses. These fields are part of the broader `HttpChaosProfile` model (documented on the consumer-facing chaos page) but are relevant here because they are specifically designed for testing GraphQL clients.

**New fields (added alongside the existing body-corruption fields):**

| Field | Type | Description |
|-------|------|-------------|
| `graphqlErrors` | Boolean | When `true`, activates GraphQL error injection. The response is rewritten as an HTTP 200 GraphQL error envelope: `{"data":...,"errors":[{"message":...,"extensions":{"code":...}}]}`, with `Content-Type: application/json` and `Content-Length` stripped. |
| `graphqlErrorMessage` | String | The `errors[0].message` value. Defaults to `"simulated GraphQL error"` when `graphqlErrors` is true and this field is unset. |
| `graphqlErrorCode` | String | Optional value placed in `errors[0].extensions.code` (e.g. `"INTERNAL_SERVER_ERROR"`). The `extensions` object is omitted entirely when this field is null. |
| `graphqlNullifyData` | Boolean | When `true` (the default), `data` is set to `null`. When `false`, the handler attempts to parse the original response body as JSON and embed it as the `data` value, enabling partial-success simulation. Falls back to `data: null` if the original body is not valid JSON. |

**Precedence in `applyResponseChaos`:** `graphqlErrors` takes precedence over `truncateBodyAtFraction` and `malformedBody` — when `graphqlErrors` is true, body corruption is skipped because the envelope is the intended body. The slow-response dribble (`slowResponseChunkSize` + `slowResponseChunkDelay`) composes normally with GraphQL injection since it only affects delivery timing. The fault is metered as `fault_type="graphql"`.

**Scope:** GraphQL error injection works on both expectation-level chaos (attached to an `Expectation`) and service-scoped chaos (`ServiceChaosRegistry` / `PUT /mockserver/serviceChaos`). It respects the count window (`succeedFirst` / `failRequestCount`) in the same way as other body-corruption faults.

### gRPC Health Checking Protocol

MockServer auto-responds to `grpc.health.v1.Health/Check` without requiring a proto descriptor. The implementation uses manual protobuf encode/decode so health checks work even when the descriptor store is empty.

**Key classes:**

| Class | Package | Role |
|-------|---------|------|
| `GrpcHealthRegistry` | `org.mockserver.grpc` | Singleton map of service name → `ServingStatus`; falls back to a configurable default (`SERVING`) when no per-service entry exists |
| `GrpcHealthCheckHandler` | `org.mockserver.grpc` | Decodes the gRPC-framed `HealthCheckRequest` (5-byte header + protobuf field 1 varint), looks up `GrpcHealthRegistry`, encodes a gRPC-framed `HealthCheckResponse` |
| `ServingStatus` | `org.mockserver.grpc` | Enum: `UNKNOWN(0)`, `SERVING(1)`, `NOT_SERVING(2)`, `SERVICE_UNKNOWN(3)` |

**Interception point:** `GrpcToHttpRequestHandler` checks whether the request path equals `GrpcHealthCheckHandler.HEALTH_CHECK_PATH` (`/grpc.health.v1.Health/Check`) before performing any descriptor lookup. When matched, the response is written directly and the request never reaches the expectation matching engine.

**Configuration:** `grpcHealthCheckEnabled` (default `true`) controls whether health check interception is active.

**REST endpoints:**

| Endpoint | Action |
|----------|--------|
| `PUT /mockserver/grpc/health` | Set the `ServingStatus` for a named service (`service` + `status` fields) |
| `GET /mockserver/grpc/health` | Read all status overrides plus the global default |

All overrides are cleared on `HttpState.reset()`. An empty `service` string sets the global default. The `GET` response uses `_default` as the key for the global default entry.

### Control Plane REST API

| Endpoint | Action |
|----------|--------|
| `PUT /mockserver/grpc/descriptors` | Upload a compiled proto descriptor set (binary body) |
| `PUT /mockserver/grpc/services` | List all loaded gRPC services and their methods |
| `PUT /mockserver/grpc/clear` | Clear all loaded descriptors and reset the store |

### gRPC Forward Proxy + Record/Replay

MockServer can forward a gRPC call to a real upstream gRPC server and record the decoded exchange, bringing the record-then-mock workflow to gRPC. When a decoded gRPC request (produced by `GrpcToHttpRequestHandler`, i.e. carrying `x-grpc-service`/`x-grpc-method` + a JSON body + `application/grpc`) matches a `FORWARD`-class expectation, or arrives in proxy mode with no matching expectation, the forward path:

1. re-encodes the JSON request body back into gRPC length-prefixed protobuf frames, sets `content-type: application/grpc`, forces HTTP/2, adds `te: trailers`, and strips the internal `x-grpc-*` helper headers so they do not leak upstream;
2. decodes the upstream's gRPC-framed protobuf response back to JSON and re-stamps `x-grpc-service`/`x-grpc-method` (and `grpc-status-name`) onto the response so `GrpcToHttpResponseHandler` re-frames it for the calling client and the logged `FORWARDED_REQUEST` entry carries decoded JSON.

```mermaid
flowchart LR
    Client["gRPC client"] -->|"application/grpc\nprotobuf frame"| Proxy["MockServer proxy\n(descriptors loaded)"]
    Proxy -->|"decode → JSON,\nmatch FORWARD / proxy no-match"| Enc["GrpcForwardTranslator\nencodeRequestForUpstream"]
    Enc -->|"re-framed protobuf,\nHTTP/2"| Upstream["real gRPC server"]
    Upstream -->|"framed protobuf\nresponse"| Dec["GrpcForwardTranslator\ndecodeResponseFromUpstream"]
    Dec -->|"JSON + x-grpc-* stamped"| Log["FORWARDED_REQUEST\n(decoded, replayable)"]
    Dec -->|"re-frame"| Client
```

| Class | Module | Purpose |
|-------|--------|---------|
| `GrpcForwardTranslator` | core (`org.mockserver.grpc`) | `encodeRequestForUpstream` (JSON → gRPC-framed protobuf, force HTTP/2) and `decodeResponseFromUpstream` (framed protobuf → JSON + re-stamp headers). Both fail-safe: non-gRPC / unknown-method / error → original message returned unchanged. |

Wiring: the matched `FORWARD`-family path threads the descriptor store into `HttpForwardAction` (`setGrpcDescriptorStore`, set in the `HttpActionHandler` handler getters) and applies the transform once in `HttpForwardAction.sendRequest` (covers all `FORWARD*` action types); the response is decoded via a composed `overrideHttpResponse` so both the client write and the recorded log entry see the decoded JSON. The unmatched/anonymous proxy paths in `HttpActionHandler` apply the same helper (`grpcEncodeForForward` / `grpcDecodeOverride`) around their inline `httpClient.sendRequest` calls.

**Boundaries and requirements:**

- **Descriptors on the proxy are required to decode.** `GrpcToHttpRequestHandler` only converts inbound gRPC to JSON when the descriptor store `hasServices()`. Without descriptors on the proxy the raw `application/grpc` bytes are still forwarded verbatim over HTTP/2 and recorded, but undecoded (binary body, no method JSON).
- **Unary + client-streaming requests** (single JSON object / JSON array body) and **unary + server-streaming responses** (one or more frames) are handled. Full bidirectional streaming forward is out of scope — it is driven by the multiplex bidi pipeline, not the request/response forward path.
- **Transport downgrade.** The re-encoded request is marked HTTP/2; `NettyHttpClient` downgrades a non-secure HTTP/2 request to HTTP/1.1, so a cleartext (h2c) upstream receives the framed `application/grpc` body over HTTP/1.1 (which MockServer-to-MockServer handles, since the gRPC handlers are content-type driven and present on the HTTP/1.1 pipeline). Reach a strict HTTP/2-only gRPC server over TLS (h2 via ALPN).
- **Terminal status from upstream trailers.** Real gRPC servers send `grpc-status`/`grpc-message` in HTTP/2 (or chunked HTTP/1.1) **trailers**, not as headers. `FullHttpResponseToMockServerHttpResponse.setHeaders` folds the upstream `trailingHeaders()` into the response model (only for trailer names not already present as headers), so `decodeResponseFromUpstream` sees the real `grpc-status` and stamps `grpc-status-name` — otherwise a non-OK upstream RPC would be relayed and recorded as OK.
- **`FORWARD_REPLACE` (`httpOverrideForwardedRequest`) ordering with gRPC.** The gRPC response decode is composed *after* any user-supplied `overrideHttpResponse`, so a user override on a gRPC `FORWARD_REPLACE` sees the raw framed protobuf upstream response (not the decoded JSON); the decode-to-JSON + header re-stamp runs last. Overriding the *request* side of `FORWARD_REPLACE` is applied before the gRPC re-encode, so an override that rewrites the JSON body is honoured. Editing the framed bytes directly in an override is not supported.

### Streaming Limitations

- **True client streaming and bidirectional streaming** are supported via the `Http2MultiplexHandler` multiplex pipeline (per-stream child channels), opt-in behind `grpcBidiStreamingEnabled`; when disabled the default `InboundHttp2ToHttpAdapter` path aggregates full messages and bidi actions return 501
- **WAR deployment** returns 501 for `GRPC_STREAM_RESPONSE` actions (no `ChannelHandlerContext` available)
- **Proto reflection** is supported — a `GrpcServerReflectionHandler` (core, with a `GrpcBidiReflectionHandler` on the multiplex path) answers v1 and v1alpha `ServerReflection` requests without a generated stub; descriptors may still be provided via files or API upload

## Module Boundaries

| Component | Module | Package |
|---|---|---|
| `SseEvent`, `HttpSseResponse`, `JsonRpcBody` | `mockserver-core` | `org.mockserver.model` |
| `JsonRpcMatcher` | `mockserver-core` | `org.mockserver.matchers` |
| `HttpSseResponseActionHandler` | `mockserver-core` | `org.mockserver.mock.action.http` |
| `SseEventDTO`, `HttpSseResponseDTO`, `JsonRpcBodyDTO` | `mockserver-core` | `org.mockserver.serialization.model` |
| `HttpRequestTemplateObject` (jsonRpc fields) | `mockserver-core` | `org.mockserver.templates.engine.model` |
| `GrpcStreamMessage`, `GrpcStreamResponse` | `mockserver-core` | `org.mockserver.model` |
| `GrpcFrameCodec`, `GrpcJsonMessageConverter`, `GrpcProtoDescriptorStore`, `GrpcProtoFileCompiler`, `GrpcStatusMapper`, `GrpcWebTranslator`, `GrpcForwardTranslator`, `GrpcException` | `mockserver-core` | `org.mockserver.grpc` |
| `ConnectError`, `ConnectResponse`, `ConnectUnaryDetector` | `mockserver-core` | `org.mockserver.grpc.connect` |
| `GrpcHealthRegistry`, `GrpcHealthCheckHandler`, `ServingStatus` | `mockserver-core` | `org.mockserver.grpc` |
| `GrpcChaosProfile` | `mockserver-core` | `org.mockserver.model` |
| `GrpcChaosRegistry` | `mockserver-core` | `org.mockserver.mock.action.http` |
| `GrpcChaosProfileDTO` | `mockserver-core` | `org.mockserver.serialization.model` |
| `GrpcStreamResponseActionHandler` | `mockserver-core` | `org.mockserver.mock.action.http` |
| `GrpcStreamMessageDTO`, `GrpcStreamResponseDTO` | `mockserver-core` | `org.mockserver.serialization.model` |
| `GrpcToHttpRequestHandler`, `GrpcToHttpResponseHandler` | `mockserver-netty` | `org.mockserver.netty.grpc` |
| `McpMockBuilder`, `A2aMockBuilder`, `RealtimeMockBuilder` | `mockserver-client-java` | `org.mockserver.client` |
| `RealtimeProvider`, `RealtimeModality`, `RealtimeTurn`, `RealtimeStreamingPhysics`, `RealtimeEvent`, `OpenAiRealtimeCodec`, `GeminiLiveCodec` | `mockserver-core` | `org.mockserver.llm.realtime` |

## Test Coverage

| Test Class | Module | Tests | Type |
|---|---|---|---|
| `SseEventTest` | core | 19 | Unit |
| `HttpSseResponseTest` | core | 22 | Unit |
| `JsonRpcBodyTest` | core | 21 | Unit |
| `JsonRpcMatcherTest` | core | 12 | Unit |
| `HttpSseResponseDTOTest` | core | 5 | Unit |
| `JsonRpcBodyDTOTest` | core | 5 | Unit |
| `ExpectationWithSseAndJsonRpcSerializationTest` | core | 4 | Unit |
| `HttpRequestTemplateObjectJsonRpcTest` | core | 11 | Unit |
| `McpMockBuilderTest` | client-java | 12 | Unit |
| `A2aMockBuilderTest` | client-java | 25 | Unit |
| `SseStreamingIntegrationTest` | netty | 9 | Integration |
| `McpMockBuilderIntegrationTest` | netty | 12 | Integration |
| `A2aMockBuilderIntegrationTest` | netty | 13 | Integration |
| `WebSocketMessageTest` | core | 14 | Unit |
| `HttpWebSocketResponseTest` | core | 19 | Unit |
| `WebSocketMessageModelDTOTest` | core | 5 | Unit |
| `HttpWebSocketResponseDTOTest` | core | 5 | Unit |
| `ForwardChainExpectationTest` | client-java | 10 | Unit |
| `WebSocketMockingIntegrationTest` | netty | 6 | Integration |
| `OpenAiRealtimeCodecTest` | core | 11 | Unit |
| `GeminiLiveCodecTest` | core | 6 | Unit |
| `RealtimeMockBuilderTest` | client-java | 4 | Unit |
| `RealtimeMockingIntegrationTest` | netty | 3 | Integration |
| `GrpcFrameCodecTest` | core | 6 | Unit |
| `GrpcJsonMessageConverterTest` | core | 7 | Unit |
| `GrpcProtoDescriptorStoreTest` | core | 7 | Unit |
| `GrpcStatusMapperTest` | core | 9 | Unit |
| `GrpcWebTranslatorTest` | core | 20 | Unit |
| `GrpcForwardTranslatorTest` | core | 13 | Unit |
| `HttpForwardActionHandlerGrpcTest` | core | 3 | Unit |
| `GrpcStreamResponseDTOTest` | core | 3 | Unit |
| `GrpcIntegrationTest` | netty | 11 | Integration |
| `GrpcForwardProxyIntegrationTest` | netty | 3 | Integration |
| `GrpcWebHandlerTest` | netty | 12 | Handler |
| `ConnectErrorTest` | core | 6 | Unit |
| `ConnectResponseTest` | core | 9 | Unit |
| `ConnectUnaryDetectorTest` | core | 10 | Unit |
| `ConnectUnaryIntegrationTest` | netty | 5 | Integration |

## Client Library Support

All four client libraries support the new action types and body matchers:

| Feature | Java | Node.js | Python | Ruby |
|---|---|---|---|---|
| SSE Response (`httpSseResponse`) | `respondWithSse()` | `Expectation.httpSseResponse` | `respond_with_sse()` | `respond_with_sse` |
| WebSocket Response (`httpWebSocketResponse`) | `respondWithWebSocket()` | `Expectation.httpWebSocketResponse` | `respond_with_websocket()` | `respond_with_websocket` |
| JSON-RPC Body (`JSON_RPC`) | `jsonRpc("method")` | `{ type: 'JSON_RPC', method: '...' }` | `Body.json_rpc("method")` | `Body.json_rpc("method")` |
| MCP Mock Builder | `McpMockBuilder.mcpMock()` | N/A (use REST API) | N/A (use REST API) | N/A (use REST API) |
| A2A Mock Builder | `A2aMockBuilder.a2aMock()` | N/A (use REST API) | N/A (use REST API) | N/A (use REST API) |
| gRPC Stream Response (`grpcStreamResponse`) | `respondWithGrpcStream()` | N/A (use REST API) | N/A (use REST API) | N/A (use REST API) |
| gRPC Descriptor Upload | `uploadGrpcDescriptor()` | N/A (use REST API) | N/A (use REST API) | N/A (use REST API) |
| gRPC Services List | `retrieveGrpcServices()` | N/A (use REST API) | N/A (use REST API) | N/A (use REST API) |
| gRPC Descriptors Clear | `clearGrpcDescriptors()` | N/A (use REST API) | N/A (use REST API) | N/A (use REST API) |
| Callback Support | Full (WebSocket) | Full (WebSocket) | Full (WebSocket) | Full (WebSocket) |

## OpenAPI Contract Verification

In addition to mocking AI protocols, MockServer's MCP control plane provides OpenAPI contract verification tools:

### OpenApiTrafficValidator (`mockserver-core`)

Validates recorded request/response pairs against an OpenAPI spec. For each pair, it locates the matching spec operation, validates the request with `OpenAPIRequestValidator`, and validates the response with `OpenAPIResponseValidator`. Exposed via the `verify_traffic_against_openapi` MCP tool.

### OpenApiContractTest (`mockserver-core`)

Builds representative example requests for each operation in an OpenAPI spec (resolving path parameters, query parameters, headers, and request bodies from spec examples and `ExampleBuilder`-generated values), sends them via an injected `Function<HttpRequest, HttpResponse>`, and validates responses with `OpenAPIResponseValidator`. The class is HTTP-client-agnostic; the MCP tool layer wires in the real HTTP transport.

### OpenApiResiliencyTest (`mockserver-core`)

Reuses `OpenApiContractTest.buildExampleRequest()` to generate a valid base request for each operation, then produces a bounded mutation catalogue:

- **Omit required path/query parameter** -- only when the parameter is marked `required`
- **Omit required body field** -- only when the schema lists `required` fields
- **Type violation** -- sends a string where schema expects integer/boolean, or vice versa
- **Numeric boundary violation** -- `minimum-1` and `maximum+1` when schema defines bounds
- **String length violation** -- `minLength-1` and `maxLength+1` when schema defines length constraints
- **Oversized string** -- 10,000-character string for string fields without explicit `maxLength`
- **Malformed JSON body** -- unparseable JSON

Each mutated request is sent via the injected `Function<HttpRequest, HttpResponse>` and the response is classified as `HANDLED` (4xx) or `UNEXPECTED` (5xx, 2xx, connection error). The class is HTTP-client-agnostic like `OpenApiContractTest`. Exposed via the `run_resiliency_test` MCP tool with a 5-second timeout per request.

```mermaid
flowchart TB
    subgraph "MCP Tools"
        VT["verify_traffic_against_openapi"]
        CT["run_contract_test"]
        RT["run_resiliency_test"]
    end

    subgraph "Core Validators"
        OTV["OpenApiTrafficValidator"]
        OCT["OpenApiContractTest"]
        ORT["OpenApiResiliencyTest"]
        RV["OpenAPIRequestValidator"]
        RSV["OpenAPIResponseValidator"]
        EB["ExampleBuilder"]
    end

    VT --> OTV
    CT --> OCT
    RT --> ORT
    OTV --> RV
    OTV --> RSV
    OCT --> EB
    OCT --> RSV
    ORT --> OCT
```

## Deterministic LLM Record/Replay

### Overview

MockServer supports recording LLM API traffic (Anthropic Claude, OpenAI, MCP servers, etc.) through its forwarding proxy and replaying it deterministically from fixture files. This enables AI application tests that are offline, free (no metered API calls), and reproducible.

### Architecture

```mermaid
flowchart LR
    subgraph "Record Phase"
        App["AI Application"] -->|HTTP/SSE| Proxy["MockServer\n(forwarding proxy)"]
        Proxy -->|Forward| LLM["Real LLM API\n(Anthropic, OpenAI)"]
        Proxy -->|Log| EventLog["FORWARDED_REQUEST\nentries"]
    end

    subgraph "Snapshot Phase"
        EventLog -->|record_llm_fixtures| Conv["SseAwareExpectationConverter"]
        Conv --> Redact["FixtureRedactor"]
        Redact -->|Write JSON| Fixture["fixture.json\n(committable)"]
    end

    subgraph "Replay Phase"
        Fixture -->|load_expectations_from_file| Active["Active Expectations"]
        App2["AI Application"] -->|HTTP| MockReplay["MockServer\n(mock mode)"]
        MockReplay -->|SSE stream| App2
    end
```

### Components

| Class | Module | Purpose |
|-------|--------|---------|
| `FixtureRedactor` | core | Redacts sensitive headers (Authorization, api-key, Cookie, etc.) from expectations before writing to fixture files; operates on copies, never mutates live entries |
| `SseBodyParser` | core | Parses raw `text/event-stream` bytes into `SseEvent` objects; replays captured per-chunk delays when available, falling back to a fixed inter-event delay (50ms default) |
| `SseAwareExpectationConverter` | core | Detects SSE-streamed responses (via `x-mockserver-streamed` header or `text/event-stream` content type) and converts them to `HttpSseResponse` actions; falls back to static response with warning for truncated captures |

### MCP Tools

| Tool | Purpose |
|------|---------|
| `record_llm_fixtures` | Snapshots recorded proxy traffic into a fixture file: retrieves FORWARDED_REQUEST entries, converts SSE responses, redacts secrets, writes to the specified path |
| `load_expectations_from_file` | Loads a fixture file and adds its expectations as active mocks for replay |

### Expectation-authoring and record/replay control tools

So an AI coding agent (Claude Code, Cursor, …) can stand up and drive mocks from the IDE without leaving the MCP protocol, `McpToolRegistry` exposes authoring and control tools that each delegate to the corresponding `HttpState` control-plane operation — there is no separate matching/verification path. The `/mockserver/mode` and `/mockserver/recordings/promote` REST handlers and their MCP tools share the same `HttpState.setMode(...)` / `HttpState.promoteRecordings(...)` methods.

| Tool | Class | Delegates to | Purpose |
|------|-------|--------------|---------|
| `create_expectation` | MUTATE | `HttpState.add` | Create a mock from a simplified method/path/response DSL (plus chaos) |
| `raw_expectation` | MUTATE | `HttpState.add` | Create a mock from the full MockServer expectation JSON (`PUT /mockserver/expectation` schema) |
| `list_expectations` | READ | `HttpState.retrieve` (`ACTIVE_EXPECTATIONS`) | List the active expectations, optionally filtered by method/path, in full JSON incl. id |
| `clear_expectations` | MUTATE | `HttpState.clear` | Clear expectations by request matcher or expectation id |
| `verify_request` | READ | `HttpState.verify` | Verify a request pattern met its `VerificationTimes`; returns pass/fail + closest-match diff |
| `retrieve_recorded_requests` | READ | `HttpState.retrieve` (`REQUESTS`) | Return recorded requests, optionally filtered |
| `retrieve_request_responses` | READ | `HttpState.retrieve` (`REQUEST_RESPONSES`) | Return recorded request/response pairs |
| `set_operating_mode` | MUTATE | `HttpState.setMode` | Switch the high-level mode SIMULATE / SPY / CAPTURE (`PUT /mockserver/mode`) |
| `promote_recordings` | MUTATE | `HttpState.promoteRecordings` | Turn recorded (forwarded) traffic into active mocks with redaction + consolidation + parameterization (`PUT /mockserver/recordings/promote`) |

The read-vs-mutate class drives control-plane authorization: when `controlPlaneAuthorizationEnabled=true`, a MUTATE tool requires the MUTATE role and a READ tool the READ role (see the control-plane note at the top of this page). A typical "record then mock" agent flow is `set_operating_mode SPY` → drive the app so unmatched requests are forwarded and recorded → `promote_recordings` → `list_expectations` to confirm.

### SSE Timing

When the capture records per-chunk timing, the delays are carried on the `x-mockserver-chunk-delays-ms` header (a comma-separated list of millisecond gaps). `SseAwareExpectationConverter` parses that header and replays each SSE event with its captured delay, reproducing the original stream timing. When the header is absent, empty, or malformed, replay falls back to a fixed inter-event delay (50ms default).

### Secret Redaction

The `FixtureRedactor` replaces header values for a configurable set of header names with `***REDACTED***`. Default sensitive headers:
- `Authorization`
- `x-api-key` / `api-key`
- `Cookie` / `Set-Cookie`
- `Proxy-Authorization`

Custom header lists can be provided for application-specific secrets.

### Truncation Handling

When the captured SSE body exceeds `maxStreamingCaptureBytes`, the capture is truncated (`x-mockserver-stream-truncated` header). The converter falls back to a static response with an `x-mockserver-fixture-warning` header explaining the truncation. Increasing `maxStreamingCaptureBytes` ensures full capture.

## Related GitHub Issues

- #2143 — SSE Streaming Support
- #2168 — WebSocket Mocking
- #2115 — Streaming Response Support
- #1936 — gRPC Protocol Support (under #2173 Protocol Extensions)
