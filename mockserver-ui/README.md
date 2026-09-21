# mockserver-ui 

> A dashboard to view the expectations, requests, and logs in [MockServer](https://mock-server.com/)

[![Build status](https://badge.buildkite.com/a1d7b386b768855f167d5104bc4e71cd6176e84af4faf09024.svg?style=square&theme=slack)](https://buildkite.com/mockserver/mockserver-ui)

# Community

* Roadmap:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/orgs/mock-server/projects/1"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Project"></a>
* Feature Requests:&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Issues / Bugs:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Discussions:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/discussions"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Discussions"></a>

## Getting Started

> **Node version:** build and develop with **Node 22** (CI and the Maven `build-ui` profile both use v22.14.0; see `.nvmrc` and `package.json` `engines`). With [nvm](https://github.com/nvm-sh/nvm) just run `nvm use` in this directory. Newer majors (e.g. Node 26 from Homebrew) have historically broken the Vite/rolldown build with a cryptic `@mui/x-charts` `MISSING_EXPORT` error — pin to Node 22 to avoid it.

This node module is built using [Vite](https://vitejs.dev/) and TypeScript. It is not intended to be used standalone (except for development) and is bundled into [MockServer](https://mock-server.com/) on path `/mockserver/dashboard`, for example:
```
https://localhost:1080/mockserver/dashboard
```

For development this node module can be run using `npm run dev` and can be pointed at a running version of [MockServer](https://mock-server.com/) using `host`, `port` and `context` query parameters as required, for example: 
```
http://localhost:3000/?host=localhost&port=1080&context=
```

To run locally:
```bash
# 1. run node 
npm run dev
# 2. navigate to UI
open http://localhost:3000/mockserver/dashboard/?port=1080
```

The dev server's base path is `/mockserver/dashboard/` (matching the bundled path), so that is
the canonical URL. The shorter `http://localhost:3000/?port=1080` form used above also works —
Vite redirects `/` to the base path and preserves the query string.

> **Dev server vs bundled dashboard:** `npm run dev` serves the UI live, so edits hot-reload.
> `http://localhost:1080/mockserver/dashboard` serves the copy baked into the MockServer JAR by
> the Maven `build-ui` profile at build time, so UI changes only appear there after rebuilding
> `mockserver-netty`.

## Demo data (manual UI testing)

To see every dashboard screen populated with a large, varied dataset, run:
```bash
npm run demo
```
This launches the MockServer backend, loads the demo dataset, starts the UI dev server, and
opens the dashboard at `http://localhost:3000/mockserver/dashboard/?port=1080`. Press Ctrl+C to
stop both servers.

It builds the MockServer JAR automatically when one is missing, when `--rebuild` is passed, or
when the JAR is **stale** — i.e. any `mockserver/**/src/main/*.java` or `pom.xml` is newer than
it. That last case is the common footgun: an older JAR silently lacks an endpoint the checked-out
seed script calls, so the demo 404s mid-run. The staleness check covers server-side sources only;
UI edits don't make the JAR stale because the demo serves the UI from the dev server.

### What gets populated

The dataset is organised around the dashboard's own navigation groups:

| Group | Tab | Seeded with |
|-------|-----|-------------|
| **Mock** | Mocks | HTTP expectations (varied verbs / status / bodies / headers / query / cookies / delay / times / priority), a forward, LLM response mocks for every provider (Anthropic, OpenAI, OpenAI Responses, Gemini, Ollama) with tool calls + streaming, DNS mocks (A / AAAA / CNAME / NXDOMAIN), a WASM-body-matched expectation, and expectations with before-actions (blocking + non-blocking) and after-actions (webhooks) |
| | Scenarios | Seeded scenario state machines, including one timed auto-transition and a cross-protocol trigger |
| | gRPC | Server-streaming, unary and error gRPC expectations |
| | Async | A loaded AsyncAPI spec + 5 channels (Recorded Messages only tick with `--with-broker`) |
| **Observe** | Dashboard | Active expectations across every kind, incl. the conversation expectation exercising every predicate pill |
| | Traffic | Recorded *and* proxied request/response pairs, some intentionally unmatched, with one classified lane per LLM provider carrying token/cost usage |
| | Trace | Four multi-turn agent loops (`agent-001`…`agent-004`, each asking about a different city so the call graphs stay distinct), sharing an isolation header |
| | Metrics | Request activity, throughput, MCP tool calls (6 tools), chaos faults |
| **Verify** | Drift | Status / schema-added / schema-removed / header drift records from proxied-vs-stub comparison |
| | SLO | Populated only with `--with-load-injection` (needs `sloTrackingEnabled` + live load samples) |
| **Resilience** | Chaos | HTTP service chaos (incl. GraphQL-semantic), gRPC health statuses, gRPC fault injection (streaming/trailer faults), TCP-layer chaos — several with auto-revert TTLs — plus a looping 3-stage Experiment |
| | Performance | Live load scenarios, with `--with-load-injection` |
| **AI** | LLM Optimise | A crafted 7-call support-agent run that fires all six cost signals (repeated system prompt, large static context resent, deterministic tool call, oversized tool result, output-token bloat, duplicate consecutive call) |
| | MCP Health | Per-server latency + error rate across 4 MCP servers (`chrome-devtools-mcp` flagged slow, `github-mcp` high error rate) |
| **Inspect** | Breakpoints | Loopback expectations — register a matcher on the Matchers tab, then trigger the loopback to pause Live Exchanges / Live Streams |
| | Library | WASM modules, a compiled gRPC `FileDescriptorSet` (`greeting.dsc` → 4 methods), and cassette fixtures from `scripts/demo-cassettes/` |
| | Audit | Fills naturally from the control-plane mutations the seeding itself performs |

Not seeded: **Contract** (needs an OpenAPI spec you supply) and **Cluster** (needs a real
multi-node cluster). The script prints a summary of everything it loaded, plus a "Try these
views" guide, when it finishes.

Two panels need extra infrastructure before they show live activity:

```bash
npm run demo -- --with-broker          # Docker Mosquitto broker -> AsyncAPI "Recorded Messages" ticks live
npm run demo -- --with-load-injection  # live load scenario -> Performance + SLO tabs
```

### Options

| Option | Effect |
|--------|--------|
| `--rebuild` | Force a JAR rebuild even if a current one exists |
| `--no-browser` | Don't auto-open the browser |
| `--with-broker` | Start a Mosquitto MQTT broker (needs Docker) so the AsyncAPI panel gets a live feed |
| `--with-load-injection` | Enable load generation + SLO tracking and start a long-running scenario against delayed self-target endpoints |
| `--load-generation` | Enable the load-generation control plane *without* auto-starting the heavy scenarios — for driving a light scenario yourself (e.g. a clean Performance screenshot) |
| `--port <n>` | MockServer port (default `1080`) |
| `--ui-port <n>` | UI dev server port (default `3000`) |
| `--mqtt-port <n>` | MQTT broker port (default `1883`; only with `--with-broker`) |

### Re-seeding without restarting

The seed script resets the server first, so it is safe to re-run against an already-running
MockServer:
```bash
npm run demo:data            # defaults to http://localhost:1080
npm run demo:data -- --url http://localhost:9090
```

The populate logic lives in [`scripts/populate-demo-data.mjs`](scripts/populate-demo-data.mjs)
and the launcher in [`scripts/launch-with-demo-data.sh`](scripts/launch-with-demo-data.sh).

## Contributing
In lieu of a formal styleguide, take care to maintain the existing coding style. Add unit tests for any new or changed functionality.
