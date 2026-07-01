# MockServer Examples

A single, consistent home for **runnable** MockServer examples across every language and
interface. Whatever client you use, start here.

## How this is organised

Examples are grouped **by language/interface** at the top level, with two **cross-cutting
topic** folders (`chaos/`, `wasm/`) that apply regardless of client:

| Folder | What it covers |
|--------|----------------|
| [`java/`](java/) | Java client + proxy examples (a buildable, CI-tested Maven module) |
| [`node/`](node/) | Node.js / TypeScript client examples |
| [`python/`](python/) | Python client examples |
| [`ruby/`](ruby/) | Ruby client examples |
| [`go/`](go/) | Go client examples (including interactive breakpoints) |
| [`dotnet/`](dotnet/) | .NET / C# client examples (including interactive breakpoints) |
| [`rust/`](rust/) | Rust client examples (including interactive breakpoints) |
| [`php/`](php/) | PHP client examples (REST-only; static forward overrides) |
| [`curl/`](curl/) | Raw REST control-plane examples as runnable `curl` scripts (+ original reference markdown) |
| [`json/`](json/) | Expectation & initializer JSON payloads (+ original reference markdown) |
| [`docker-compose/`](docker-compose/) | Deployment scenarios: ports, env vars, mTLS, persistence, expectation initialiser |
| [`kubernetes/`](kubernetes/) | Kubernetes deployments on local k3s (k3d) — incl. load-injection metrics in Grafana over Prometheus + OpenTelemetry |
| [`wasm/`](wasm/) | Custom WASM body-matcher rules — [`rust/`](wasm/rust/) (prebuilt) and [`go/`](wasm/go/) |
| [`chaos/`](chaos/) | Fault injection across HTTP, TCP, gRPC and LLM layers — MockServer's flagship differentiator |

## Consistency rule

Every leaf example is **self-contained and identical in shape**:

- one runnable entrypoint (script / program), and
- a short `README.md` with exactly these sections: **What it demonstrates · Prerequisites · Run · Expected output**.

Each top-level folder has its own index `README.md` linking its examples. If you add an
example, follow this shape.

## Prerequisites

Most examples assume a **running MockServer**. Quickest start:

```bash
# Docker
docker run -d --rm -p 1080:1080 mockserver/mockserver

# or the standalone jar
java -jar mockserver-netty-<version>-shaded.jar -serverPort 1080
```

Scripts default to `http://localhost:1080`; the `curl/`, `json/`, and `chaos/` scripts honour
a `MOCKSERVER_URL` env var (e.g. `export MOCKSERVER_URL=http://localhost:1080`). Each
language track's README explains how to install that client (npm / pip / gem / Maven).

## Relationship to the documentation

- **Consumer docs:** https://www.mock-server.com — conceptual guides for every feature here.
- **Internal architecture:** [`docs/code/`](../docs/code/) — e.g. [`wasm-rules.md`](../docs/code/wasm-rules.md) for the WASM ABI, and the chaos/drift/async subsystem docs.

These examples are intended to stay in lock-step with behaviour: the `java/` module is built
in CI (so it can't silently rot), and the `docker-compose/` scenarios are reused by
`container_integration_tests/`.
