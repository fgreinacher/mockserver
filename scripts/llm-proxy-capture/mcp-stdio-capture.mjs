#!/usr/bin/env node
/*
 * mcp-stdio-capture.mjs — make a STDIO MCP server's traffic visible in MockServer.
 * ---------------------------------------------------------------------------------
 * A stdio MCP server (e.g. `chrome-devtools-mcp`) talks JSON-RPC over stdin/stdout with
 * the CLI — it never makes an outbound HTTP request, so an HTTP proxy (MockServer as
 * HTTPS_PROXY) cannot see it. This is a thin PASSTHROUGH bridge you register in place of
 * the real MCP command: it relays the child's stdio byte-for-byte (so the CLI behaves
 * exactly as before) while tee-ing each JSON-RPC exchange to MockServer over HTTP, so the
 * stdio MCP server shows up in the dashboard's MCP Health panel ALONGSIDE the LLM traffic.
 *
 * This is a PROTOTYPE / proof-of-concept of "proxying a stdio process", not a hardened tool.
 *
 * Usage (as opencode's MCP "command"):
 *   node scripts/llm-proxy-capture/mcp-stdio-capture.mjs \
 *        --server chrome-devtools-mcp --mockserver http://localhost:1080 \
 *        -- npx chrome-devtools-mcp@latest
 *   # i.e. everything after `--` is the REAL MCP command + args, spawned untouched.
 *
 * What shows up: under AI · MCP Health, one row per --server with call count, error rate
 * (JSON-RPC errors are tee'd as a 5xx so the panel flags them), and the methods called.
 *
 * Known limitation: the latency the panel shows is MockServer's own tiny processing time,
 * NOT the real MCP call latency — the real per-call latency is sent in the
 * `x-mcp-latency-ms` request header (so a small panel tweak could surface it later, the
 * same way the panel already falls back to `x-mockserver-response-time-ms`). MCP
 * notifications (no `id`) are tee'd fire-and-forget. stdio MCP framing here is the
 * newline-delimited JSON-RPC the MCP stdio transport uses.
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import https from 'node:https';

function parseArgs(argv) {
  const o = { server: 'stdio-mcp', mockserver: process.env.MOCKSERVER_URL || 'http://localhost:1080', cmd: [] };
  let i = 0;
  for (; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--') { o.cmd = argv.slice(i + 1); break; }
    else if (a === '--server') o.server = argv[++i];
    else if (a === '--mockserver') o.mockserver = argv[++i];
    else if (a === '--help' || a === '-h') {
      process.stderr.write('usage: mcp-stdio-capture.mjs --server <name> [--mockserver <url>] -- <command> [args...]\n');
      process.exit(0);
    }
  }
  o.mockserver = o.mockserver.replace(/\/+$/, '');
  return o;
}

const opts = parseArgs(process.argv.slice(2));
if (opts.cmd.length === 0) {
  process.stderr.write('mcp-stdio-capture: no command after `--` to spawn. See --help.\n');
  process.exit(2);
}

// A wildcard expectation so the tee POSTs are RECORDED (and so a JSON-RPC error becomes a 5xx
// the MCP Health panel flags). Best-effort: if MockServer is unreachable the bridge still relays.
async function registerExpectations() {
  const put = (body) => fetch(`${opts.mockserver}/mockserver/expectation`, {
    method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  }).then((r) => r.text()).catch(() => {});
  // error exchanges (x-mcp-error: true) -> 500 so the panel counts them as failures
  await put({
    httpRequest: { method: 'POST', path: '/mcp-stdio/.*', headers: { 'x-mcp-error': ['true'] } },
    httpResponse: { statusCode: 500, headers: { 'content-type': ['application/json'] }, body: JSON.stringify({ captured: true }) },
    priority: 10,
  });
  // ok exchanges -> 200
  await put({
    httpRequest: { method: 'POST', path: '/mcp-stdio/.*' },
    httpResponse: { statusCode: 200, headers: { 'content-type': ['application/json'] }, body: JSON.stringify({ captured: true }) },
  });
}

// Tee one captured JSON-RPC exchange to MockServer. Uses node:http (NOT fetch) so the Host header —
// which the MCP Health panel groups servers by — can be set; WHATWG fetch forbids overriding Host.
// Non-blocking w.r.t. the relay; tracked in `pendingTees` so they can be flushed before exit.
const teeBase = new URL(opts.mockserver);
const teeTransport = teeBase.protocol === 'https:' ? https : http;
const pendingTees = new Set();
function tee(jsonRpc, { isError, latencyMs, method }) {
  const slug = (method || 'message').replace(/[^a-zA-Z0-9_./-]/g, '_');
  const body = JSON.stringify(jsonRpc);
  const p = new Promise((resolve) => {
    const req = teeTransport.request({
      hostname: teeBase.hostname,
      port: teeBase.port || (teeBase.protocol === 'https:' ? 443 : 80),
      method: 'POST',
      path: `/mcp-stdio/${encodeURIComponent(opts.server)}/${slug}`,
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        Host: opts.server, // grouped on by the MCP Health panel
        'x-mcp-error': isError ? 'true' : 'false',
        'x-mcp-latency-ms': latencyMs != null ? String(latencyMs) : '',
      },
    }, (res) => { res.on('data', () => {}); res.on('end', resolve); res.on('error', resolve); });
    req.on('error', resolve);
    req.write(body);
    req.end();
  }).finally(() => pendingTees.delete(p));
  pendingTees.add(p);
}

const child = spawn(opts.cmd[0], opts.cmd.slice(1), { stdio: ['pipe', 'pipe', 'inherit'] });
child.on('error', (e) => { process.stderr.write(`mcp-stdio-capture: failed to spawn ${opts.cmd[0]}: ${e.message}\n`); process.exit(1); });
child.on('exit', async (code, signal) => {
  // Flush any in-flight tee POSTs (bounded) before exiting so the last exchanges are recorded.
  await Promise.race([Promise.allSettled([...pendingTees]), new Promise((r) => setTimeout(r, 1500))]);
  process.exit(code == null ? (signal ? 1 : 0) : code);
});

// Pending requests by JSON-RPC id, with the send time, so we can measure real latency + method.
const pending = new Map();

function onLine(line, direction) {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (!msg || msg.jsonrpc !== '2.0') return;
  if (direction === 'toServer') {
    if (msg.method != null && msg.id != null) pending.set(String(msg.id), { t: Date.now(), method: msg.method });
    else if (msg.method != null) tee(msg, { isError: false, latencyMs: null, method: msg.method }); // notification
  } else { // fromServer
    if (msg.id != null && ('result' in msg || 'error' in msg)) {
      const p = pending.get(String(msg.id));
      pending.delete(String(msg.id));
      tee(msg, { isError: msg.error != null, latencyMs: p ? Date.now() - p.t : null, method: p ? p.method : null });
    }
  }
}

// Byte-for-byte passthrough in BOTH directions, with a side-channel line scanner for the tee.
function bridge(src, dst, direction) {
  let buf = '';
  src.on('data', (chunk) => {
    dst.write(chunk); // relay untouched first — never delay the CLI/server
    buf += chunk.toString('utf8');
    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) { const line = buf.slice(0, nl).trim(); buf = buf.slice(nl + 1); if (line) onLine(line, direction); }
  });
  src.on('end', () => { try { dst.end(); } catch { /* noop */ } });
}

registerExpectations().finally(() => {
  bridge(process.stdin, child.stdin, 'toServer');
  bridge(child.stdout, process.stdout, 'fromServer');
});
