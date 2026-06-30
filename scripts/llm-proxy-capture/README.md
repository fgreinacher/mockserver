# Capturing coding-assistant LLM traffic with MockServer

Point a coding-assistant CLI at MockServer running as an HTTPS proxy and MockServer
**records and classifies** its LLM calls, so they appear in the dashboard's **Traffic**,
**LLM Traces**, and **LLM Optimise** views.

## TL;DR

```bash
# 1. start MockServer as a proxy (any port; 1080 used here).
#    forwardProxyHttp2Upgrade=true forwards the CLI's TLS traffic to the upstream over HTTP/2 so a
#    streaming SSE backend that streams its head over HTTP/2 sends it immediately. maxSocketTimeoutInMillis
#    raises the FIRST-byte wait from the 20s default to 120s: a reasoning model can take ~20s to emit its
#    first token on a big prompt, and the 20s default 502s those slow-but-healthy streaming heads.
#    The redirect + heap-dump flags make any process death diagnosable: a real Java OOM leaves an
#    OutOfMemoryError in mockserver.log AND a heap dump; if the process just vanishes with neither, it was
#    killed externally (another tool/session, or the OS OOM-killer — check `dmesg` on Linux).
java -Dmockserver.forwardProxyHttp2Upgrade=true \
     -Dmockserver.maxSocketTimeoutInMillis=120000 \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=mockserver-heap.hprof -XX:+ExitOnOutOfMemoryError \
     -jar mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar \
     -serverPort 1080 -logLevel INFO > mockserver.log 2>&1

# 2. run the smoke test — it drives whichever CLIs are installed + authed, then asserts capture
scripts/llm-proxy-capture/capture-smoke.sh

# 3. view it
open http://localhost:1080/mockserver/dashboard   # Traffic · LLM Traces · LLM Optimise
```

The script is **local-only**: it invokes real, interactively-authenticated CLIs and makes
small live calls to model providers / your org's gateway. It is **skipped on CI**. The
CI-safe equivalent is the fixture-driven `CodingCliLlmCaptureTest` (mockserver-core) and
`llmTraffic.test.ts` (mockserver-ui), which exercise the same detection with no network or
credentials.

## Interactive UX testing — `npm run capture`

The script above is an **automated assertion** (proxy → run → assert → exit). To instead
**watch traffic appear live in the dashboard while you use a tool by hand**, use the
companion launcher:

```bash
cd mockserver-ui
npm run capture -- opencode      # or: claude | tabnine   (omit a tool to just start the servers)
```

It starts MockServer (proxy) **and** the UI dev server, opens the dashboard, sets the proxy
env, and drops you straight into the chosen CLI — interactively — so every call it makes
streams into the Traffic / LLM Traces / LLM Optimise tabs in real time. The dashboard is
served from the dev server, so the latest UI (e.g. provider detection) is live without
rebuilding the jar. Exit the tool (or Ctrl+C) to tear the servers down. It is the
LLM-capture sibling of `npm run demo` (which loads synthetic data instead). See
`mockserver-ui/scripts/launch-with-llm-capture.sh --help`.

## Reverse-proxy mode (no `HTTPS_PROXY`)

By default the launcher runs MockServer as a **forward** proxy (the tool sets `HTTPS_PROXY` and
MockServer TLS-intercepts via CONNECT). Pass `--reverse-proxy HOST[:PORT]` to instead run it as a
**reverse** (port-forwarding) proxy to a single upstream, so the tool points its **base URL** at
MockServer rather than using `HTTPS_PROXY`:

```bash
npm run capture -- --reverse-proxy api.openai.com   # OPENAI_BASE_URL is set for you
```

This route goes through the same streaming + `forwardProxyHttp2Upgrade` path as the forward proxy
(not the CONNECT loopback), so it's also a clean way to isolate whether a streaming timeout is
CONNECT-specific. Note: `opencode`'s Codex backend (`chatgpt.com`) is pinned to its ChatGPT
subscription auth and is **not** base-URL-overridable, so reverse mode suits standard-API tools
(`OPENAI_BASE_URL`); use the default forward mode for `opencode`.

## Capturing stdio MCP servers — `mcp-stdio-capture.mjs`

A coding-assistant CLI's MCP servers are only captured if they speak HTTP/SSE through the proxy. A
**stdio** MCP server (e.g. `chrome-devtools-mcp`, which talks the Chrome DevTools Protocol over a
local pipe) never makes an outbound HTTP request, so the HTTP proxy can't see it. `mcp-stdio-capture.mjs`
is a thin **passthrough bridge** you register *in place of* the real MCP command: it relays the child's
stdio byte-for-byte (the CLI behaves exactly as before) while tee-ing each JSON-RPC exchange to MockServer
over HTTP, so the stdio MCP server appears in the **AI · MCP Health** panel alongside the LLM traffic —
with per-server call count, error rate (JSON-RPC errors are tee'd as a 5xx), the methods called, and the
**real per-call latency** (measured by the bridge and forwarded as `x-mcp-latency-ms`, which the panel
prefers over MockServer's own processing time).

```bash
# register this as opencode's MCP "command"; everything after `--` is the REAL command, spawned untouched:
node scripts/llm-proxy-capture/mcp-stdio-capture.mjs \
     --server chrome-devtools-mcp --mockserver http://localhost:1080 \
     -- npx chrome-devtools-mcp@latest
```

It's a prototype/proof-of-concept of "proxying a stdio process", not a hardened tool. See its header
comment for the framing assumptions and limitations.

## The prompt ladder

Each installed CLI is driven with a ladder of prompts (`CAPTURE_PROMPTS`, default all three)
so both fast and slow responses — and a mixed-model request — are exercised:

| Prompt | Kind | What it reproduces |
|--------|------|--------------------|
| `simple` | text | a one-word reply — the provider responds in seconds (fast baseline) |
| `reasoning` | text-heavy | a three-proof maths question — the provider "thinks" for a long time before sending response headers. This is what makes opencode's **10s header-timeout** fire and retry on complex prompts |
| `multimodal` | text + image | a "describe this image" question with a generated red/green/blue-stripe PNG attached (opencode via `-f`, claude/tabnine via a path reference) — captures a real mixed-model request |

Subset with `CAPTURE_PROMPTS="simple"` for a quick cheap check; each prompt is a real model
call against every installed tool, so the full ladder costs quota.

## What it checks, per (prompt, tool)

| Step | Meaning | Gates? |
|------|---------|--------|
| **CAPTURE** | the tool's LLM endpoint shows up in the recorded request log (proxy + TLS interception works) | yes |
| **CLASSIFY** | that call appears in the LLM optimisation report with the expected provider (it will render in LLM Traces / Optimise) | yes |
| **TIMING** | per-prompt wall-clock, the max upstream response time MockServer saw, whether it **streamed** (relayed incrementally), and any tool-reported **timeout/retry** | report-only by default; set `CAPTURE_FAIL_ON_TIMEOUT=1` to gate |

The output is a table — e.g. the `reasoning` row showing a high `UPSTREAM` time with `STREAM=yes`
confirms MockServer relayed the slow response incrementally (so the head reached the client
promptly) rather than buffering it; a `TO/RTY` value there is the provider being slow to
respond, not MockServer.

## Supported CLIs and the endpoints they use

| CLI | LLM endpoint | Wire format | Provider |
|-----|--------------|-------------|----------|
| **claude** (Claude Code) | `POST api.anthropic.com/v1/messages` | Anthropic Messages | `ANTHROPIC` |
| **opencode** (Codex) | `POST chatgpt.com/backend-api/codex/responses` | OpenAI Responses | `OPENAI_RESPONSES` |
| **tabnine** (Gemini-CLI fork) | `POST <gateway>/…/chat/completions` | OpenAI Chat Completions | `OPENAI` |

`opencode`'s OpenAI **Codex** backend serves the Responses API at a non-standard path
(`/backend-api/codex/responses`); MockServer recognises it the same as the hosted
`/v1/responses` endpoint. `tabnine` is matched by its `/chat/completions` path, so it works
regardless of which (possibly private) gateway host it is configured for.

## Why each CLI trusts the proxy

MockServer terminates TLS with its own CA, so each CLI must trust that CA and route through
the proxy. The script exports these for every tool it launches:

```bash
export HTTPS_PROXY=http://localhost:1080
export NODE_EXTRA_CA_CERTS=.../CertificateAuthorityCertificate.pem   # node CLIs (opencode, tabnine)
export SSL_CERT_FILE=.../CertificateAuthorityCertificate.pem          # curl/openssl clients
export REQUESTS_CA_BUNDLE=.../CertificateAuthorityCertificate.pem     # python clients
```

The default CA is MockServer's **public test CA** shipped in this repo
(`mockserver/mockserver-core/.../socket/CertificateAuthorityCertificate.pem`). For a
hardened setup, generate your own CA and point `MOCKSERVER_CA` at it.

## Configuration (env overrides)

| Variable | Default | Purpose |
|----------|---------|---------|
| `MOCKSERVER_URL` | `http://localhost:1080` | base URL of a **running** MockServer proxy |
| `MOCKSERVER_CA` | repo test CA | proxy CA certificate the CLIs must trust |
| `CAPTURE_PROMPTS` | `simple reasoning multimodal` | which ladder steps to run |
| `CAPTURE_PROMPT` | _unset_ | legacy: run only this one custom (simple/text) prompt |
| `CAPTURE_TIMEOUT` | `180` | per-tool, per-prompt timeout (seconds) |
| `CAPTURE_TOOLS` | `claude opencode tabnine` | subset to consider |
| `CAPTURE_FAIL_ON_TIMEOUT` | `0` | set `1` to fail the run if any prompt timed out / retried |
| `FORCE` | _unset_ | set `1` to run even when a CI env is detected |

### Reading the timing — `DUR` vs `UPSTREAM`

`UPSTREAM` is the only MockServer-attributable time (how long the provider took to respond,
as MockServer measured it). `DUR` is the tool's whole wall-clock, which is dominated by the
**tool's own** startup, MCP servers, and retries — not the proxy. The report prints a `NOTE`
when that gap is large, so a slow CLI is not mistaken for a slow proxy. (A real example found
during development: opencode took ~36s where MockServer's part was ~2s — the rest was an
unreachable `chrome-devtools` MCP server timing out for 32s.) The CLIs are run in the
invocation directory with their normal config — the script does **not** relocate them to a
clean dir or force-disable MCP, because some CLIs (opencode) need a real project context to
make their call. To speed a tool up, fix or disable its MCP servers in the tool's own config.

## No secrets

The script holds no API keys and names no private hosts. CLIs use their own stored
credentials; tabnine's gateway is matched by URL path, never hard-coded. Safe to commit.
