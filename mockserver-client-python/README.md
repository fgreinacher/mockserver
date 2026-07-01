# MockServer Python Client

Python client for [MockServer](https://www.mock-server.com) with full WebSocket callback support.

## Features

- **Full REST API**: Create expectations, verify requests, clear/reset, retrieve recorded data
- **Response Callbacks**: Register Python functions that dynamically generate responses via WebSocket
- **Forward Callbacks**: Modify requests before they are forwarded to the real server
- **Forward+Response Callbacks**: Modify both the forwarded request and the response
- **Fluent API**: `client.when(request).respond(callback)` — mirrors the Java client
- **Async + Sync**: Native asyncio API with a synchronous wrapper for non-async code
- **Minimal Dependencies**: Only `websockets` (for callback support)

## Installation

```bash
pip install mockserver-client
```

## Quick Start

### Synchronous API

```python
from mockserver import MockServerClient, HttpRequest, HttpResponse

client = MockServerClient("localhost", 1080)

# Static expectation
client.when(
    HttpRequest.request("/api/users").with_method("GET")
).respond(
    HttpResponse.response('{"users": []}', status_code=200)
)

# Verify
client.verify(
    HttpRequest.request("/api/users").with_method("GET"),
    VerificationTimes.at_least(1)
)

# Clean up
client.reset()
client.close()
```

### Context Manager

```python
with MockServerClient("localhost", 1080) as client:
    client.when(
        HttpRequest.request("/hello")
    ).respond(
        HttpResponse.response("world")
    )
```

### Async API

```python
import asyncio
from mockserver import AsyncMockServerClient, HttpRequest, HttpResponse

async def main():
    async with AsyncMockServerClient("localhost", 1080) as client:
        await client.when(
            HttpRequest.request("/api/data")
        ).respond(
            HttpResponse.response('{"key": "value"}')
        )

asyncio.run(main())
```

## Response Callbacks

Register a Python function that generates responses dynamically when matching requests arrive:

```python
from mockserver import MockServerClient, HttpRequest, HttpResponse

def handle_request(request):
    if request.method == "POST":
        return HttpResponse.response("created", status_code=201)
    return HttpResponse.not_found_response()

client = MockServerClient("localhost", 1080)
client.mock_with_callback(
    HttpRequest.request("/api/callback"),
    handle_request
)
```

Or with the fluent API:

```python
client.when(
    HttpRequest.request("/api/callback")
).respond(handle_request)
```

## Forward Callbacks

Modify requests before they are forwarded to the real server:

```python
def modify_request(request):
    return request.with_header("X-Forwarded", "true").with_path("/modified" + request.path)

client.mock_with_forward_callback(
    HttpRequest.request("/proxy/.*"),
    modify_request
)
```

## Forward+Response Callbacks

Modify both the forwarded request and the response:

```python
def modify_request(request):
    return request.with_header("X-Proxied", "true")

def modify_response(request, response):
    return response.with_header("X-Modified", "true")

client.mock_with_forward_callback(
    HttpRequest.request("/proxy/.*"),
    modify_request,
    modify_response
)
```

## Verification

```python
from mockserver import VerificationTimes

# Verify a request was received at least once
client.verify(
    HttpRequest.request("/api/users").with_method("GET"),
    VerificationTimes.at_least(1)
)

# Verify exact count
client.verify(
    HttpRequest.request("/api/users"),
    VerificationTimes.exactly(3)
)

# Verify request sequence (order matters)
client.verify_sequence(
    HttpRequest.request("/first"),
    HttpRequest.request("/second"),
    HttpRequest.request("/third"),
)

# Verify no interactions
client.verify_zero_interactions()
```

## Retrieval

```python
# Get recorded requests
requests = client.retrieve_recorded_requests(
    HttpRequest.request("/api/.*")
)

# Get active expectations
expectations = client.retrieve_active_expectations()

# Get log messages
logs = client.retrieve_log_messages()
```

## Control

```python
# Clear specific expectations
client.clear(HttpRequest.request("/api/users"))

# Clear by type
client.clear(HttpRequest.request("/api/users"), clear_type="LOG")

# Reset everything
client.reset()

# Bind additional ports
client.bind(1081, 1082)

# Check if running
if client.has_started():
    print("MockServer is running")

# Stop
client.stop()
```

## AI Protocol Mocking

Declarative builders mock an MCP (Model Context Protocol) server or an A2A
(Agent-to-Agent) server with a single fluent chain. Each builder produces a set of
HTTP expectations that speak JSON-RPC 2.0 and echo the incoming request id.

```python
from mockserver import mcp_mock, a2a_mock

# Mock an MCP server (Streamable HTTP, JSON-RPC 2.0) on /mcp
mcp_mock() \
    .with_tool("get_weather") \
        .with_description("Get weather for a city") \
        .with_input_schema('{"type": "object", "properties": {"city": {"type": "string"}}}') \
        .responding_with("72F and sunny") \
        .and_() \
    .apply_to(client)

# Mock an A2A agent on /a2a (agent card + tasks/send|get|cancel)
a2a_mock() \
    .with_agent_name("TranslatorAgent") \
    .with_skill("translate") \
        .with_name("Translation") \
        .with_description("Translates text between languages") \
        .with_tag("i18n") \
        .with_example("Translate hello to Spanish") \
        .and_() \
    .on_task_send() \
        .matching_message("translate.*") \
        .responding_with("Hola") \
        .and_() \
    .apply_to(client)
```

The A2A builder also supports streaming (SSE) and push notifications:

```python
a2a_mock() \
    .with_streaming() \
    .with_push_notifications("http://localhost:1234/callback") \
    .apply_to(client)
```

`build()` returns the list of `Expectation` objects without registering them, so
you can inspect or persist them; `apply_to(client)` registers them via `upsert`.

## SRE / Resilience

Verify a service-level objective over recorded SLI samples, or run a scheduled
multi-stage chaos experiment. Both require the corresponding server feature to be
enabled (`sloTrackingEnabled`, chaos experiments).

```python
# Verify an SLO — a FAIL verdict (HTTP 406) raises MockServerVerificationError
verdict = client.verify_slo({
    "name": "checkout",
    "minimumSampleCount": 100,
    "objectives": [
        {"sli": "errorRate", "comparator": "LESS_THAN", "threshold": 0.01},
        {"sli": "p99LatencyMs", "comparator": "LESS_THAN", "threshold": 250},
    ],
})
print(verdict["result"])  # PASS or INCONCLUSIVE

# Start a multi-stage chaos experiment (only one may be active at a time)
client.start_chaos_experiment({
    "name": "latency-injection",
    "loop": False,
    "stages": [
        {"durationMillis": 60000, "profiles": {"payments.svc": {"latencyMs": 500}}},
    ],
})
```

Both methods are available on the async client too (`await client.verify_slo(...)`,
`await client.start_chaos_experiment(...)`).

## TLS Support

```python
# Uses system trust store (default — verifies certificates)
client = MockServerClient("localhost", 1080, secure=True)

# Custom CA certificate
client = MockServerClient(
    "localhost", 1080,
    secure=True,
    ca_cert_path="/path/to/ca.pem"
)

# Disable certificate verification (testing only — NOT recommended for production)
client = MockServerClient(
    "localhost", 1080,
    secure=True,
    tls_verify=False
)
```

## Domain Model

All domain model classes support builder-style chaining:

```python
request = (
    HttpRequest.request("/api/users")
    .with_method("POST")
    .with_header("Content-Type", "application/json")
    .with_header("Authorization", "Bearer token")
    .with_body('{"name": "test"}')
    .with_query_param("page", "1")
    .with_secure(True)
)

response = (
    HttpResponse.response()
    .with_status_code(201)
    .with_header("Location", "/api/users/1")
    .with_body('{"id": 1, "name": "test"}')
    .with_delay(Delay(time_unit="SECONDS", value=1))
)
```

## Interactive Breakpoints

The client supports matcher-driven interactive breakpoints over the callback WebSocket. Register a breakpoint matcher to pause forwarded/proxied exchanges at specific phases and inspect/modify/continue them via callback handlers.

### Register a breakpoint (sync client)

```python
from mockserver import MockServerClient, HttpRequest, HttpResponse

client = MockServerClient("localhost", 1080)

# REQUEST phase only
bp_id = client.add_request_breakpoint(
    HttpRequest(path="/api/.*"),
    lambda request: request,  # continue unchanged (or return HttpResponse to abort)
)

# REQUEST + RESPONSE
bp_id = client.add_request_and_response_breakpoint(
    HttpRequest(path="/api/.*"),
    lambda request: request,                      # REQUEST handler
    lambda request, response: response,           # RESPONSE handler
)

# All phases with stream frame handler
bp_id = client.add_breakpoint(
    HttpRequest(path="/stream/.*"),
    ["REQUEST", "RESPONSE", "RESPONSE_STREAM", "INBOUND_STREAM"],
    request_handler=lambda request: request,
    response_handler=lambda request, response: response,
    stream_frame_handler=lambda frame: {"action": "CONTINUE"},
    # Other actions: MODIFY (with body), DROP, INJECT (with body), CLOSE
)
```

### Manage breakpoints

```python
# List all matchers
matchers = client.list_breakpoint_matchers()  # {"matchers": [...]}

# Remove a specific matcher
client.remove_breakpoint_matcher(bp_id)

# Clear all matchers
client.clear_breakpoint_matchers()
```

The async client (`AsyncMockServerClient`) exposes the same methods as coroutines.

## Start / Launch MockServer

The Python client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```python
from mockserver.launcher import start, MockServerProcess

# Download (first run) and start MockServer on port 1080
with start(port=1080) as server:
    print(f"MockServer running on port {server.port}, PID {server.pid}")
    # ... use MockServer ...
# Server is stopped automatically when the context manager exits
```

### Just ensure the binary is present

```python
from mockserver.launcher import ensure_binary

launcher_path = ensure_binary()  # returns Path to the launcher executable
```

### Specify a version

```python
from mockserver.launcher import start

server = start(port=1080, version="7.3.0")
# ...
server.stop()
```

### API reference

| Function / Class | Description |
|---|---|
| `ensure_binary(version=None, *, log=True)` | Download, verify, cache, and return the launcher `Path`. Defaults to the client's own version. |
| `start(port, version=None, *, extra_args=None, log=True)` | Ensure the binary and start MockServer. Returns a `MockServerProcess`. |
| `MockServerProcess` | Handle to the running process. Properties: `port`, `pid`, `launcher`, `returncode`. Methods: `stop(timeout=10.0)`. Supports `with` statement. |

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

By default the launcher downloads the MockServer version matching this client package (currently the version set in `pyproject.toml`). Pass an explicit `version` argument to override.

## Requirements

- Python 3.9+
- `websockets` >= 12.0 (for callback support)

## License

Apache 2.0

## AI Assistant Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that enables AI coding assistants to create expectations, verify requests, and debug HTTP traffic programmatically.

- **MCP Endpoint:** `http://localhost:1080/mockserver/mcp`
- **AI Documentation:** [llms.txt](https://www.mock-server.com/llms.txt)
- **Setup Guide:** [AI Integration](https://www.mock-server.com/mock_server/ai_mcp_setup.html)
