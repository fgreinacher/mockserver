# MockServer Go Client

An idiomatic Go client for the [MockServer](https://www.mock-server.com) control-plane REST API.

Zero third-party dependencies — uses only the Go standard library (`net/http` + `encoding/json`).

## Installation

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7
```

## Quick Start

```go
package main

import (
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7"
)

func main() {
    client := mockserver.New("localhost", 1080)

    // Create an expectation
    _, err := client.When(
        mockserver.Request().Method("GET").Path("/hello"),
    ).Respond(
        mockserver.Response().StatusCode(200).Body("world"),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Verify the request was received at least once
    err = client.Verify(
        mockserver.Request().Path("/hello"),
        mockserver.AtLeast(1),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Reset all expectations and logs
    if err := client.Reset(); err != nil {
        log.Fatal(err)
    }
}
```

## API

### Client Construction

```go
// By host and port
client := mockserver.New("localhost", 1080)

// From a full URL
client := mockserver.NewFromURL("http://mockserver.internal:1080")

// With options
client := mockserver.New("localhost", 1080,
    mockserver.WithContextPath("/myapp"),
    mockserver.WithTimeout(10 * time.Second),
)
```

### Creating Expectations

```go
// Simple response
client.When(
    mockserver.Request().Method("GET").Path("/api/users"),
).Respond(
    mockserver.Response().StatusCode(200).JSONBody(`[{"id":1}]`),
)

// With times and TTL
client.When(
    mockserver.Request().Method("POST").Path("/api/orders"),
    mockserver.WithTimes(mockserver.Once()),
    mockserver.WithTimeToLive(mockserver.TTL("SECONDS", 60)),
).Respond(
    mockserver.Response().StatusCode(201).WithDelay("MILLISECONDS", 100),
)

// Forward to another host
client.When(
    mockserver.Request().Path("/proxy/.*"),
).Forward(
    mockserver.Forward().Host("backend.local").Port(8080).Scheme("HTTP"),
)

// With expectation ID for deduplication
client.When(
    mockserver.Request().Path("/stable"),
).WithID("my-stable-exp").Respond(
    mockserver.Response().StatusCode(200),
)
```

### Request Matchers

#### JWT matcher

Match a decoded bearer-token JWT by its claims, issuer, audience, and algorithm.
Claim and string values follow MockServer's string-matcher semantics: an exact
value, a regular expression, or a `!`-prefixed negated match.

```go
client.When(
    mockserver.Request().Method("GET").Path("/secure").
        Jwt(mockserver.NewJwt().
            Claim("role", "^admin.*$").      // regex claim
            Claim("scope", "!internal").     // negated claim
            WithIssuer("https://issuer.example.com").
            WithAudience("api").
            WithAlgorithm("RS256")),
).Respond(
    mockserver.Response().StatusCode(200),
)
```

By default the token is read from the `Authorization` header with the `Bearer`
scheme; override with `WithHeader(...)` / `WithScheme(...)`.

#### allOf body matcher

Require every nested body matcher to match with `AllOfBody(...)` (or the standalone
`AllOf(...)` constructor). Elements may be any body matcher — `JSONPathBody`,
`RegexBody`, a `*TypedBody`, or a plain string.

```go
client.When(
    mockserver.Request().Method("POST").Path("/orders").
        AllOfBody(
            mockserver.JSONPathBody("$.items[?(@.qty > 0)]"),
            mockserver.RegexBody(`.*"currency":"GBP".*`),
        ),
).Respond(
    mockserver.Response().StatusCode(201),
)
```

### Verification

```go
// At least N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtLeast(1))

// At most N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtMost(5))

// Exactly N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.ExactlyTimes(3))

// Between min and max
client.Verify(mockserver.Request().Path("/hello"), mockserver.Between(1, 5))

// Sequence verification
client.VerifySequence(
    mockserver.Request().Path("/login"),
    mockserver.Request().Path("/dashboard"),
)
```

### Retrieval

```go
// Active expectations
expectations, _ := client.RetrieveActiveExpectations(nil)

// Recorded requests (optionally filtered)
requests, _ := client.RetrieveRecordedRequests(mockserver.Request().Path("/api/.*"))

// Recorded expectations
recorded, _ := client.RetrieveRecordedExpectations(nil)

// Active expectations as generated MockServer SDK setup code
// (format: FormatJava, FormatJavaScript, FormatPython, FormatGo,
//  FormatCSharp, FormatRuby, FormatRust or FormatPHP)
code, _ := client.RetrieveExpectationsAsCode(nil, mockserver.FormatJava)

// Recorded request/response pairs as generated SDK setup code
recordedCode, _ := client.RetrieveRecordedExpectationsAsCode(nil, mockserver.FormatGo)

// Log messages
logs, _ := client.RetrieveLogMessages(nil)
```

### Control

```go
// Clear specific expectations
client.Clear(mockserver.Request().Path("/old"), mockserver.ClearExpectations)

// Clear by expectation ID
client.ClearByID("exp-123", mockserver.ClearAll)

// Reset everything
client.Reset()

// Check server status
status, _ := client.Status()
fmt.Println(status.Ports)

// Check if running
if client.IsRunning() { ... }

// Bind additional ports
ports, _ := client.Bind(1081, 1082)
```

### Interactive Breakpoints

Register breakpoint matchers to pause forwarded/proxied traffic at REQUEST, RESPONSE, RESPONSE_STREAM, or INBOUND_STREAM phases. A callback WebSocket connection is opened automatically to receive paused items.

```go
// REQUEST-only breakpoint
id, _ := client.AddRequestBreakpoint(
    mockserver.Request().Path("/api/.*"),
    func(req map[string]interface{}) interface{} {
        // Inspect/modify the request, or return a response to abort
        return req // continue with original
    },
)

// REQUEST + RESPONSE breakpoint
id, _ := client.AddRequestResponseBreakpoint(
    mockserver.Request().Path("/api/.*"),
    func(req map[string]interface{}) interface{} { return req },
    func(req, resp map[string]interface{}) map[string]interface{} { return resp },
)

// Streaming breakpoint (RESPONSE_STREAM / INBOUND_STREAM)
id, _ := client.AddStreamBreakpoint(
    mockserver.Request().Path("/stream/.*"),
    []mockserver.BreakpointPhase{mockserver.PhaseResponseStream},
    func(frame *mockserver.PausedStreamFrame) *mockserver.StreamFrameDecision {
        d := mockserver.ContinueFrame(frame.CorrelationID)
        return &d
    },
)

// List, remove, clear matchers
list, _ := client.ListBreakpointMatchers()
client.RemoveBreakpointMatcher(id)
client.ClearBreakpointMatchers()
client.CloseBreakpointWebSocket()
```

**Stream frame decisions:** `ContinueFrame`, `ModifyFrame`, `DropFrame`, `InjectFrame`, `CloseFrame`.

### LLM Mocking

Mock LLM provider APIs (OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI, Ollama) with fluent builders. The action is carried in the `httpLlmResponse` field of an expectation; MockServer re-encodes the provider-agnostic `Completion` into the wire shape of the configured provider. Use the `Provider*` and `Role*` constants.

```go
// Single completion mock
_, _ = mockserver.LlmMock("/v1/chat/completions").
    WithProvider(mockserver.ProviderOpenAI).
    WithModel("gpt-4o").
    RespondingWithCompletion(
        mockserver.NewCompletion().
            WithText("Hello!").
            WithStopReason("stop").
            WithUsage(mockserver.NewUsage().WithInputTokens(10).WithOutputTokens(5)),
    ).
    ApplyTo(client)

// Embedding mock
_, _ = mockserver.LlmMock("/v1/embeddings").
    WithProvider(mockserver.ProviderOpenAI).
    RespondingWithEmbedding(
        mockserver.NewEmbedding().WithDimensions(1536).WithDeterministicFromInput(true),
    ).
    ApplyTo(client)

// Multi-turn conversation (scenario state advancement, optional per-session isolation)
_, _ = mockserver.Conversation().
    WithPath("/v1/chat/completions").
    WithProvider(mockserver.ProviderAnthropic).
    WithModel("claude-3-5-sonnet").
    IsolateBy(mockserver.IsolateByHeader("x-session-id")).
    Turn().
        WhenLatestMessageContains("hello").
        RespondingWith(mockserver.NewCompletion().WithText("hi there")).
    Turn().
        WhenTurnIndex(1).
        RespondingWith(mockserver.NewCompletion().WithText("bye")).
    ApplyTo(client)

// Provider failover: N failures, then success. Consecutive identical failures
// are coalesced; omitted error bodies default per status code.
_, _ = mockserver.LlmFailover().
    WithPath("/v1/chat/completions").
    WithProvider(mockserver.ProviderOpenAI).
    WithModel("gpt-4o").
    FailWithCount(429, 2). // two rate-limit attempts (default 429 body)
    FailWith(503).         // one service-unavailable attempt
    ThenRespondWith(mockserver.NewCompletion().WithText("recovered")).
    ApplyTo(client)
```

`FailWithBody(statusCode, body)` supplies a custom error body. Each builder also has a `Build()` method that returns `[]Expectation` without registering them.

### MCP Mocking

Mock a Model Context Protocol (MCP) server speaking JSON-RPC 2.0 over the Streamable HTTP transport. `McpMock` generates the full set of expectations (`initialize`, `ping`, `notifications/initialized`, plus `tools`, `resources`, and `prompts` list/call handlers). Tool/resource/prompt definitions are configured via closures.

```go
_, _ = mockserver.McpMock("/mcp").
    WithServerName("WeatherServer").
    WithTool("get_weather", func(t *mockserver.McpToolBuilder) {
        t.WithDescription("Get the weather for a city").
            WithInputSchema(`{"type":"object","properties":{"city":{"type":"string"}}}`).
            RespondingWith("72F and sunny", false)
    }).
    WithResource("file:///config.json", func(r *mockserver.McpResourceBuilder) {
        r.WithName("config").WithMimeType("application/json").WithContent(`{"debug":true}`)
    }).
    WithPrompt("greeting", func(p *mockserver.McpPromptBuilder) {
        p.WithDescription("A greeting").
            WithArgument("name", "the name", true).
            RespondingWith(mockserver.RoleUser, "Hello {{name}}")
    }).
    ApplyTo(client)
```

Responses use a Velocity template that echoes the inbound JSON-RPC id back via `$!{request.jsonRpcRawId}`. Tool input schemas are validated (invalid JSON panics) and compacted. `McpMock("")` defaults the path to `/mcp`.

### A2A Mocking

Mock an A2A (Agent2Agent) agent speaking JSON-RPC 2.0 over HTTP. `A2aMock` generates a GET agent-card discovery document plus the task methods (`tasks/send`, `tasks/get`, `tasks/cancel`), with optional SSE streaming and push-notification delivery. Skills are advertised on the agent card; custom `tasks/send` handlers match the inbound message text by regex.

```go
_, _ = mockserver.A2aMock("/a2a").
    WithAgentName("WeatherAgent").
    WithAgentDescription("Provides weather forecasts").
    WithSkill("forecast", func(s *mockserver.A2aSkillBuilder) {
        s.WithName("Forecast").
            WithDescription("Provides weather forecasts").
            WithTag("weather").
            WithExample("What is the weather in London?")
    }).
    OnTaskSend("(?i).*london.*", "Sunny in London", false).
    WithStreaming().
    WithPushNotifications("http://localhost:1234/a2a/callback").
    ApplyTo(client)
```

The agent card is served at `/.well-known/agent.json` (override via `WithAgentCardPath`) and reports `capabilities.streaming` / `capabilities.pushNotifications`. With `WithStreaming`, the streaming method (default `message/stream`, override via `WithStreamingMethod`) returns an SSE stream of status/artifact update events wrapped in JSON-RPC envelopes. With `WithPushNotifications`, each `tasks/send` both returns the JSON-RPC task response and POSTs the completed task to the webhook URL. Task responses use a Velocity template that echoes the inbound JSON-RPC id via `$!{request.jsonRpcRawId}`. `A2aMock("")` defaults the path to `/a2a`.

## Start / Launch MockServer

The Go client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```go
package main

import (
    "fmt"
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7"
)

func main() {
    handle, err := mockserver.StartServer(1080, "", nil)
    if err != nil {
        log.Fatal(err)
    }
    defer handle.Stop()

    fmt.Printf("MockServer running on port %d\n", handle.Port)
    // ... use MockServer ...
}
```

### Just ensure the binary is present

```go
launcherPath, err := mockserver.EnsureBinary(mockserver.Version, nil)
if err != nil {
    log.Fatal(err)
}
fmt.Println("Launcher at:", launcherPath)
```

### Specify a version

```go
handle, err := mockserver.StartServer(1080, "7.6.0", nil)
```

### API reference

| Function / Type | Description |
|---|---|
| `EnsureBinary(version string, opts *EnsureOptions) (string, error)` | Download, verify, cache, and return the launcher path. |
| `StartServer(port int, version string, opts *EnsureOptions) (*ServerHandle, error)` | Ensure the binary and start MockServer. Pass `""` for version to use the default. |
| `ServerHandle` | Handle to the running process. Fields: `Port`, `Launcher`. Methods: `Stop() error`, `Wait() error`. |
| `Version` | The default MockServer version this client targets (from embedded `VERSION` file). |
| `ResolvePlatform() (PlatformInfo, error)` | Detect the current OS and architecture. |
| `CacheDir() string` | Return the binary cache directory path. |

### Supported platforms

| OS | Architecture |
|---|---|
| Linux | x86_64, aarch64 |
| macOS (darwin) | x86_64, aarch64 |
| Windows | x86_64, aarch64 |

### Environment variables

| Variable | Purpose |
|---|---|
| `MOCKSERVER_BINARY_BASE_URL` | Mirror host for the release assets (corporate / air-gapped networks) |
| `MOCKSERVER_BINARY_CACHE` | Override the cache directory (default: `~/.cache/mockserver/binaries` on Unix) |
| `MOCKSERVER_SKIP_BINARY_DOWNLOAD` | Fail instead of downloading (use with a pre-seeded cache in CI) |

### Version

By default the launcher downloads the MockServer version embedded in the `VERSION` file at build time. The release pipeline updates this file automatically. Pass an explicit version string to `StartServer` or `EnsureBinary` to override.

## Using in tests

`MockServerT` registers a `t.Cleanup` that resets the server when the test (and
its subtests) finish, so recorded requests, expectations and logs do not leak
between tests:

```go
func TestSomething(t *testing.T) {
    client := mockserver.MockServerT(t, mockserver.New("localhost", 1080))
    // ... register expectations, make requests ...
    // client.Reset() runs automatically when the test ends.
}

// or construct + register in one call:
func TestAnother(t *testing.T) {
    client := mockserver.NewMockServerT(t, "localhost", 1080)
    // ...
}
```

## Build & Test

```bash
go test ./...
go vet ./...
```

Integration tests require `MOCKSERVER_URL` environment variable:

```bash
MOCKSERVER_URL=http://localhost:1080 go test ./... -v
```

## Requirements

- Go 1.21 or later
- `github.com/gorilla/websocket` (for breakpoint callback WebSocket)
