// Shared k6 configuration for the MockServer performance suite.
//
// All tunables are environment-driven (k6 `-e KEY=value` or real env vars via
// `__ENV`) so the same scripts run locally, in the docker-compose stack, and in
// CI without edits. Defaults reproduce the historical Locust target
// (MockServer on localhost:1080, 4 seeded expectations, the request matching
// the last expectation).
//
// Connection target resolution (first match wins):
//   1. BASE_URL                      e.g. https://mockserver:1080
//   2. MOCKSERVER_PROTOCOL + MOCKSERVER_HOST   e.g. http + localhost:1080
//
// MOCKSERVER_HOST mirrors the Locust harness variable (host[:port]); a bare
// host gets :1080 appended.

function env(name, fallback) {
  const value = __ENV[name];
  return value === undefined || value === '' ? fallback : value;
}

function num(name, fallback) {
  const value = env(name, undefined);
  if (value === undefined) {
    return fallback;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bool(name, fallback) {
  const value = env(name, undefined);
  if (value === undefined) {
    return fallback;
  }
  return ['1', 'true', 'yes', 'on'].includes(value.toLowerCase());
}

function resolveBaseUrl() {
  const explicit = env('BASE_URL', undefined);
  if (explicit) {
    return explicit.replace(/\/+$/, '');
  }
  const protocol = env('MOCKSERVER_PROTOCOL', 'http');
  let host = env('MOCKSERVER_HOST', 'localhost:1080');
  if (!host.includes(':')) {
    host = `${host}:1080`;
  }
  return `${protocol}://${host}`;
}

const baseUrl = resolveBaseUrl();

// A MockServer under test uses a self-signed CA, so HTTPS perf runs against a
// LOCAL/private instance legitimately need TLS verification skipped. Public
// targets should NOT silently skip verification — so the default is insecure
// only for loopback/private hosts, and explicit (INSECURE_SKIP_TLS_VERIFY) for
// anything else. This removes the "accidentally hit prod with verify off"
// footgun while keeping local HTTPS runs working out of the box.
function isLocalOrPrivateTarget(url) {
  const rawHost = url.replace(/^[a-z]+:\/\//i, '').split('/')[0];
  // Strip IPv6 bracket notation ([::1]:1080) before taking the host; otherwise
  // split(':')[0] would yield "[" and miss the loopback check.
  const host = (rawHost.startsWith('[') ? rawHost.slice(1, rawHost.indexOf(']')) : rawHost.split(':')[0]).toLowerCase();
  return (
    host === 'localhost' ||
    host === '127.0.0.1' ||
    host === '::1' ||
    host === 'host.docker.internal' ||
    host === 'mockserver' ||
    host.endsWith('.local') ||
    host.endsWith('.internal') ||
    /^10\./.test(host) ||
    /^192\.168\./.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host)
  );
}

const insecureSkipTLSVerify = bool('INSECURE_SKIP_TLS_VERIFY', isLocalOrPrivateTarget(baseUrl));

// Never let insecure verification be silently active against a non-local host.
if (insecureSkipTLSVerify && baseUrl.startsWith('https') && !isLocalOrPrivateTarget(baseUrl)) {
  console.warn(`[k6] WARNING: TLS verification is DISABLED for non-local target ${baseUrl} (INSECURE_SKIP_TLS_VERIFY).`);
}

export const CONFIG = {
  baseUrl,
  // MockServer control plane is reachable with or without the /mockserver
  // prefix; use the canonical prefixed form (matches the dashboard + metrics).
  controlPlane: `${baseUrl}/mockserver`,
  // Keep-Alive headers preserved for parity with the Locust harness. k6 reuses
  // connections per VU by default, so these are belt-and-braces.
  keepAliveHeaders: {
    Connection: 'Keep-Alive',
    'Keep-Alive': 'timeout=120, max=1000',
  },
  insecureSkipTLSVerify,
};

// Load-shape tunables (used by the scenario files). Defaults are deliberately
// conservative so an accidental local run does not saturate a workstation.
export const LOAD = {
  // ramping-arrival-rate (load.js): requests/sec per stage.
  startRate: num('K6_START_RATE', 50),
  peakRate: num('K6_PEAK_RATE', 500),
  rampUp: env('K6_RAMP_UP', '30s'),
  hold: env('K6_HOLD', '1m'),
  rampDown: env('K6_RAMP_DOWN', '15s'),
  preAllocatedVUs: num('K6_PRE_VUS', 50),
  maxVUs: num('K6_MAX_VUS', 600),
  // create-expectation churn rate run alongside matching (control-plane load).
  createRate: num('K6_CREATE_RATE', 10),
  // stress.js peak target.
  stressPeakRate: num('K6_STRESS_PEAK_RATE', 5000),
  // soak.js sustained rate + duration.
  soakRate: num('K6_SOAK_RATE', 200),
  soakDuration: env('K6_SOAK_DURATION', '30m'),
};

// Threshold values become CI pass/fail gates. Tunable so a slow CI agent can
// relax them via env without editing scripts. p95/p99 are in milliseconds.
export const LIMITS = {
  p95: num('K6_P95_MS', 25),
  p99: num('K6_P99_MS', 100),
  errorRate: num('K6_MAX_ERROR_RATE', 0.01),
  checkRate: num('K6_MIN_CHECK_RATE', 0.99),
};

// Standard thresholds shared by the load/stress/soak scenarios. Global
// http_req_duration/http_req_failed gates plus a check-pass-rate gate.
export function baseThresholds() {
  return {
    http_req_failed: [`rate<${LIMITS.errorRate}`],
    http_req_duration: [`p(95)<${LIMITS.p95}`, `p(99)<${LIMITS.p99}`],
    checks: [`rate>${LIMITS.checkRate}`],
  };
}

// Regression scenario tunables (regression.js). Unlike load.js, the regression
// run offers a FIXED rate per behaviour (constant-arrival-rate) so the recorded
// number is "latency under fixed load" — the only thing comparable across runs
// when the goal is detecting a change, not finding peak throughput. A warmup
// window runs first (tagged op:warmup) so JIT/GC reach steady state before the
// measured window; only the measured window feeds the result JSON.
export const REGRESSION = {
  rate: num('K6_REG_RATE', 200), // offered req/s PER behaviour
  duration: env('K6_REG_DURATION', '2m'), // measured window (per behaviour)
  warmup: env('K6_REG_WARMUP', '30s'), // pre-measurement server warmup window
  // preAllocatedVUs == maxVUs ON PURPOSE. The whole VU pool is created before the
  // run, and because there is NO headroom above it, the constant-arrival-rate
  // executor can NEVER allocate a VU mid-measurement. Mid-run allocation was the
  // Finding 3 feedback loop: a cold/contended request piles up iterations, k6
  // ramps preAllocatedVUs -> maxVUs, EACH new VU opens a fresh connection, the
  // connection storm slows the core-limited server further, which piles up more
  // iterations — a ~1 s (to multi-second) tail that MORE VUs only worsen.
  // Equalising the two forbids that ramp: the pool is fixed, so the connection
  // count is bounded and stable, and if a transient ever exceeds it k6 DROPS
  // iterations (counted, and surfaced by the delivery_ratio guard) instead of
  // manufacturing a storm. 200 rps at sub-ms latency needs ~1 VU by Little's law,
  // so 50 is almost entirely transient headroom for the JIT-cold first cohort.
  // It is a RAISE of the old preAllocatedVUs (20 -> 50) and, more importantly, a
  // LOWERING of maxVUs (200 -> 50) to remove the ramp — reproductions showed the
  // tail GROWS with pool size (a larger fixed pool = more concurrent connections
  // hammering the core-limited SUT), so the fix is a modest EQUAL pool, not a
  // large one. See docs/plans/performance-programme.md Finding 3. Keep
  // K6_REG_PRE_VUS and K6_REG_MAX_VUS equal if overriding.
  preAllocatedVUs: num('K6_REG_PRE_VUS', 50),
  maxVUs: num('K6_REG_MAX_VUS', 50),
  // The four measured scenarios start staggered by this gap (op index x stagger)
  // so their VU-allocation / connection-open transients do NOT superimpose into
  // one connection storm on the shared, core-limited SUT (Finding 3 root cause).
  stagger: env('K6_REG_STAGGER', '5s'),
  // Per-scenario settle window at the start of the measured window whose requests
  // are tagged op:<op>_settle and thus EXCLUDED from the measured latency
  // percentiles. Load still runs during it (the server is exercised and any
  // residual transient is genuinely traversed), so this discards only the known
  // client-side start artefact, not real steady-state latency. dropped_iterations
  // still counts the WHOLE scenario, so client starvation is never hidden.
  settle: env('K6_REG_SETTLE', '10s'),
  // Self-test knob (default 0 = off, production behaviour unchanged): when >0, a
  // fixed response delay (ms) is seeded onto the /simple (match) response so a
  // run can PROVE the measured percentiles still track a real server slowdown
  // (the medians and tail must move by ~this delay). Used by the sensitivity
  // check that guards against the settle exclusion silently hiding regressions.
  matchDelayMs: num('K6_REG_MATCH_DELAY_MS', 0),
  // --- item 15a: template-engine arms --------------------------------------
  // The `template` arm exercises VELOCITY (always on the classpath). A Mustache
  // arm (jmustache is a NON-optional core dep, so it runs on the stock image) is
  // always added. The JavaScript arm needs the GraalJS engine, which is an
  // OPTIONAL dependency present ONLY in the -graaljs image variant — so it is
  // OFF by default here (a bare `k6 run regression.js` against a stock server
  // must not manufacture a broken 100%-error arm). perf-test-run.sh turns it ON
  // and runs the SUT on the -graaljs image; regression.js then probes the arm in
  // setup() and FAILS LOUDLY if the engine is absent (never a silent zero arm).
  jsTemplate: bool('K6_REG_JS_TEMPLATE', false),
  // The Mustache + JavaScript arms run at a REDUCED rate, not the full 200 rps of
  // the velocity `template` arm. Adding two more full-rate template arms to the
  // core-limited (2-CPU) SUT is a large perturbation of the historical
  // match/forward/template/large arms for what is a cheap latency measurement;
  // 50 rps over the measured window is thousands of samples per percentile while
  // keeping the added CPU contention modest. Each arm still tracks its own
  // per-arm regression history — the cross-engine rate need not match velocity.
  templateArmRate: num('K6_REG_TEMPLATE_ARM_RATE', 50),
  // --- item 15d: body-size axis on `large` ---------------------------------
  // `large` stays the 4 KB point (unchanged, preserves baseline history). The
  // 1 MB / 10 MB arms POST a JSON body of the target size matched on a small
  // fixed marker (ONLY_MATCHING_FIELDS), so the DECODE/transfer cost scales with
  // size while match cost stays constant. Offered at reduced rates + small VU
  // pools so the byte-rate and run length stay bounded (the invariant
  // preAllocatedVUs == maxVUs from Finding 3 is preserved per arm).
  // NOTE the ceiling: MockServer rejects a REQUEST body larger than
  // MOCKSERVER_MAX_REQUEST_BODY_SIZE (default 10 * 1024 * 1024 = 10,485,760 B)
  // with 413. buildLargeJson overshoots its target by up to one token + wrapper,
  // so a 10 * 1024 * 1024 target lands just OVER the limit → a 100%-error arm.
  // Keep the 10 MB point in DECIMAL MB (10,000,000 B ≈ 9.54 MiB), comfortably
  // under the 10 MiB cap. Override K6_REG_LARGE_10MB_BYTES only alongside a
  // raised MOCKSERVER_MAX_REQUEST_BODY_SIZE on the SUT.
  large1mbBytes: num('K6_REG_LARGE_1MB_BYTES', 1000 * 1000),
  large10mbBytes: num('K6_REG_LARGE_10MB_BYTES', 10 * 1000 * 1000),
  // MEMORY CEILING (why these rates are LOW) — retained-heap arithmetic.
  // MockServer records every request (and its response) in an in-memory event-log
  // ring of `maxLogEntries` entries, holding the FULL body of each. On the 2 GB CI
  // SUT the heap is MaxRAMPercentage=75% ≈ 1.5 GB, so maxLogEntries =
  // min(heapKB/8, 100000) = 100000. The ring is COUNT-bounded, so an entry lives
  // for the last `maxLogEntries / total_offered_rps` seconds (the residence). At
  // the full regression mix total_offered_rps ≈ 901 — 4 full-rate arms × 200
  // (match/forward/template/large) + 2 template arms × 50 (mustache/javascript) +
  // the three sub-1-rps large arms (≈ 1.1) — so residence ≈ 100000/901 ≈ 111 s,
  // LONGER than a 2 m measured window minus settle, i.e. once the ring fills
  // nothing older than ~111 s survives. This residence is a CONSERVATIVE upper
  // bound: MockServer records ~2-3 log entries per request, so the ring actually
  // fills faster and residence (and thus retention) is SHORTER than the figures
  // below. The steady-state bytes retained by an arm at rate r with body B are
  // therefore at most r × 111 × B. The large arms dominate, so keep their product
  // small:
  //   large_10mb @ 0.1 rps → 0.1 × 111 × 10 MB ≈ 111 MB
  //   large_1mb  @ 0.5 rps → 0.5 × 111 ×  1 MB ≈  55 MB
  //   large_file @ 0.5 rps → 0.5 × 111 ×  1 MB ≈  55 MB   (its ~1 MB RESPONSE)
  //   large(4KB) @ 200 rps → 200 × 111 ×  4 KB ≈  89 MB
  //   -> ~310 MB of large bodies + ~40-80 MB of small entries ≈ 390 MB raw,
  //      ~0.6-0.8 GB live with MockServer's per-entry parse/overhead — comfortably
  //      under the 1.5 GB heap. (The earlier 0.4/2/2 rps retained ~888 MB raw,
  //      ~1.3-1.8 GB live, which risked OOM on the 2 GB SUT — hence these lower
  //      rates.) The warmup NEVER touches these arms (regression.js excludes them
  //      from warmupOp), so nothing accumulates before measurement either.
  // `growth` needs the default 100000-entry log, so the ring cannot be shrunk;
  // the large-body footprint MUST be bounded by rate instead. Raise these only
  // with a correspondingly larger SUT heap (PERF_SERVER_MEMORY).
  large1mbRate: num('K6_REG_LARGE_1MB_RATE', 1),
  large1mbTimeUnit: env('K6_REG_LARGE_1MB_TIME_UNIT', '2s'), // 0.5 rps
  large10mbRate: num('K6_REG_LARGE_10MB_RATE', 1),
  large10mbTimeUnit: env('K6_REG_LARGE_10MB_TIME_UNIT', '10s'), // 0.1 rps
  large1mbVUs: num('K6_REG_LARGE_1MB_VUS', 8),
  large10mbVUs: num('K6_REG_LARGE_10MB_VUS', 8),
  // File-backed RESPONSE body arm (FileBodyMaterialiser path). OFF unless a
  // server-side file path is given: the file must exist on the SUT filesystem,
  // which perf-test-run.sh provisions (generates + mounts read-only) and then
  // passes here. Empty => the arm is absent (an explicit, documented omission,
  // not a silent zero). When set, regression.js probes it in setup() and fails
  // loudly if the server cannot read the file (a FILE body 500s when unreadable).
  fileBodyPath: env('K6_REG_FILE_BODY_PATH', ''),
  // Low for the same event-log reason (see the arithmetic above): the ~1 MB file
  // RESPONSE is recorded per request. 0.5 rps × ~111 s × 1 MB ≈ 55 MB retained.
  fileBodyRate: num('K6_REG_FILE_BODY_RATE', 1),
  fileBodyTimeUnit: env('K6_REG_FILE_BODY_TIME_UNIT', '2s'), // 0.5 rps
  fileBodyVUs: num('K6_REG_FILE_BODY_VUS', 8),
  // Transport label recorded in the result key (<op>_<proto>). Defaults from the
  // BASE_URL scheme; HTTPS auto-negotiates HTTP/2 with MockServer via ALPN, so
  // the https run is labelled https_h2 unless K6_HTTP2=false.
  proto: env('PROTO', baseUrl.startsWith('https') ? (bool('K6_HTTP2', true) ? 'https_h2' : 'https') : 'http'),
  // handleSummary() writes the machine-readable result here (mapped to a file by
  // k6); the perf-test-run.sh step merges the http + https_h2 runs into one JSON.
  resultPath: env('K6_RESULT_PATH', 'regression-result.json'),
};

// Throughput-vs-latency sweep tunables (sweep.js). Offers load at an ascending
// LADDER of fixed arrival rates and records, per rate step, the ACHIEVED
// throughput, latency percentiles, and error rate — the data series plotted as a
// load-vs-latency "knee" curve. Unlike load.js (one ramp) the ladder is a set of
// discrete constant-arrival-rate steps, each staggered after the previous (plus a
// short gap so percentiles do not bleed across steps), and each request is tagged
// with its step's offered rate so per-step percentiles are computed in the
// summary. At the top of the ladder k6 may drop iterations (VU-starved) — that is
// expected and is part of showing the knee, so there are NO aborting thresholds.
export const SWEEP = {
  // Ascending ladder of offered arrival rates (req/s). Comma-separated.
  rates: env('K6_SWEEP_RATES', '500,1000,2000,4000,8000,16000,32000')
    .split(',')
    .map((r) => Number(r.trim()))
    .filter((r) => Number.isFinite(r) && r > 0),
  step: env('K6_SWEEP_STEP', '20s'), // duration each rate step holds
  gap: env('K6_SWEEP_GAP', '5s'), // quiet gap between steps (no requests)
  preAllocatedVUs: num('K6_SWEEP_PRE_VUS', 200),
  maxVUs: num('K6_SWEEP_MAX_VUS', 4000),
  // Transport label recorded in the result; HTTPS to MockServer negotiates HTTP/2
  // via ALPN, but the sweep is HTTP by default (the headline knee curve).
  proto: env('PROTO', baseUrl.startsWith('https') ? (bool('K6_HTTP2', true) ? 'https_h2' : 'https') : 'http'),
  resultPath: env('K6_SWEEP_RESULT_PATH', 'sweep-result.json'),
};

// Resource-growth scenario tunables (growth.js). A sustained constant-load run
// whose purpose is to surface "X increases over time" regressions (e.g. issue
// #2329: O(n) log eviction once the request log fills to maxLogEntries). The
// rate is high enough to fill the DEFAULT 100k log within the run; low-rate
// latency probes at the start and end measure the latency slope, paired with the
// CPU/heap trajectory sampled by perf-test-run.sh. Do NOT shrink maxLogEntries
// for this run — a smaller log would never fill and would hide the bug.
export const GROWTH = {
  rate: num('K6_GROWTH_RATE', 800), // fill load (req/s) — fills 100k log in ~50s
  // Keep K6_GROWTH_DURATION >= 3 × K6_GROWTH_PROBE so the first/last probe
  // windows have a clear gap between them and the ratio measures a real slope.
  duration: env('K6_GROWTH_DURATION', '6m'),
  probeWindow: env('K6_GROWTH_PROBE', '30s'), // first/last latency-probe window
  probeRate: num('K6_GROWTH_PROBE_RATE', 20),
  preAllocatedVUs: num('K6_GROWTH_PRE_VUS', 50),
  maxVUs: num('K6_GROWTH_MAX_VUS', 400),
  resultPath: env('K6_GROWTH_RESULT_PATH', 'growth-result.json'),
};

// Forward/proxy behaviour target. The forward action routes /forward to a
// DEDICATED upstream (a second MockServer) so the forward latency is not
// contaminated by the matching load on the instance under measurement. Set
// K6_FORWARD_SELF=true to keep the legacy self-forward (127.0.0.1:1080) for a
// quick single-container local smoke.
export const FORWARD = {
  upstreamHost: env('FORWARD_UPSTREAM_HOST', 'mockserver-upstream:1080'),
  forwardSelf: bool('K6_FORWARD_SELF', false),
};

// Forward-path load-shape tunables (forward.js). This scenario is a dedicated
// REGRESSION GUARD for the upstream connection-pool default
// (`mockserver.forwardConnectionPoolEnabled`, default true). It drives the
// FORWARD path (every request opens an outbound upstream connection unless they
// are pooled) at a sustained high rate — the level where the OLD per-request
// behaviour exhausted ephemeral ports and threw BindException, surfacing as a
// spike in `http_req_failed`. The error-rate threshold is the key gate: if
// pooling regresses, failures climb and the gate trips. Latency gates are
// secondary (forward adds a network hop, so the bounds are looser than the
// data-plane match path). Peak default is 1500 rps — the rate that broke the
// old default (21%/68% errors before the pool fix).
export const FORWARD_LOAD = {
  startRate: num('K6_FWD_START_RATE', 100),
  peakRate: num('K6_FWD_PEAK_RATE', 1500),
  rampUp: env('K6_FWD_RAMP_UP', '30s'),
  hold: env('K6_FWD_HOLD', '1m'),
  rampDown: env('K6_FWD_RAMP_DOWN', '15s'),
  preAllocatedVUs: num('K6_FWD_PRE_VUS', 200),
  maxVUs: num('K6_FWD_MAX_VUS', 2000),
  // Forward adds an upstream network hop, so the data-plane p95/p99 (25/100 ms)
  // are too tight; these forward-specific bounds are overridable. The error-rate
  // gate (the actual pool-regression signal) reuses K6_MAX_ERROR_RATE.
  p95: num('K6_FWD_P95_MS', 50),
  p99: num('K6_FWD_P99_MS', 200),
};

// Proxy-path + TLS-handshake scenario tunables (proxy.js — performance-programme
// items 9a and 14). ONE file, TWO modes selected by K6_PROXY_MODE because the two
// workloads need MUTUALLY EXCLUSIVE process-global settings and so cannot share a
// k6 process:
//   forward   — MockServer AS A FORWARD PROXY. k6 is pointed at the upstream and
//               routed THROUGH MockServer via the HTTP_PROXY / HTTPS_PROXY env the
//               run step sets on the k6 container: an http:// target becomes an
//               absolute-URI GET to the proxy (absolute-URI forwarding), an https://
//               target becomes a CONNECT tunnel carrying TLS through the SUT to the
//               upstream (MockServer may terminate that TLS itself with a generated
//               cert — see expectations.js getProxiedConnect). Connections are REUSED
//               (keep-alive), like a real proxy
//               client. Emits a .behaviours object (op_proto keys) so
//               perf-test-compare.sh picks the arms up with ZERO script changes
//               (its metric loop iterates .behaviours | to_entries[]).
//   handshake — MockServer's INBOUND TLS handshake cost (item 14). k6 hits the SUT
//               directly over HTTPS with noConnectionReuse:true so EVERY iteration
//               pays a fresh TCP+TLS handshake (the https_h2 regression run reuses
//               one connection per VU, so handshake cost is amortised to ~0 and
//               appears in no measured number). No proxy env here — a proxy env
//               would tunnel these and defeat the measurement. Emits a .tls_handshake
//               object; perf-test-run.sh augments each arm with server CPU + JVM
//               allocation per handshake sampled across the arm's window.
//
// Both modes clone regression.js's FIXED measured-window shape (Finding 3): a
// warmup scenario, STAGGERED scenario starts, preAllocatedVUs == maxVUs (no mid-run
// VU/connection ramp), a K6_PROXY_SETTLE exclusion window tagged op:<op>_settle, the
// MIN_TAIL_SAMPLES tail suppression, and the heavy/warm load-time contract. Copying
// a pre-fix regression.js revision would reintroduce the four-orders-of-magnitude
// tail bug, so these are cloned from the current (post-2026-09-16) shape.
export const PROXY = {
  mode: env('K6_PROXY_MODE', 'forward'),
  // --- forward mode ---------------------------------------------------------
  // Offered req/s per proxy arm. Retained-heap arithmetic (the same event-log ring
  // reasoning as REGRESSION's large_* arms): a PROXIED request is still recorded in
  // the SUT's count-bounded log ring, holding request + response bodies. Here the
  // bodies are TINY — the forward arms GET /simple, whose upstream response body is
  // ~8 bytes ("UPSTREAM") and request is header-only — so at r rps the retained
  // bytes are r × residence × ~200 B (entry + small body). With residence ≈
  // maxLogEntries/total_rps and total forward rps ≈ 400 (2 arms × 200), residence ≈
  // 100000/400 ≈ 250 s and retention ≈ 400 × 250 × 200 B ≈ 20 MB — negligible
  // against the 1.5 GB SUT heap, so no rate throttling / heavy flag is needed. (This
  // is why the forward arms relay a SMALL body deliberately: a proxy relaying MB
  // bodies would need the same rate-bounding the large_* arms use.)
  rate: num('K6_PROXY_RATE', 200),
  duration: env('K6_PROXY_DURATION', '2m'),
  warmup: env('K6_PROXY_WARMUP', '30s'),
  stagger: env('K6_PROXY_STAGGER', '5s'),
  settle: env('K6_PROXY_SETTLE', '10s'),
  // preAllocatedVUs == maxVUs — the Finding-3 no-mid-run-allocation invariant.
  preAllocatedVUs: num('K6_PROXY_PRE_VUS', 50),
  maxVUs: num('K6_PROXY_MAX_VUS', 50),
  // The upstream the proxy forwards/tunnels to (host:port), reachable on the run's
  // docker network. Shared with the FORWARD block so the run seeds one upstream.
  upstreamHost: env('FORWARD_UPSTREAM_HOST', 'mockserver-upstream:1080'),
  // Self-test knob (default 0 = off): a fixed server-side delay (ms) on the upstream
  // /simple response so a run can prove the proxy percentiles still track a real
  // slowdown. Applied by seeding the UPSTREAM, so it exercises the relay path.
  matchDelayMs: num('K6_PROXY_MATCH_DELAY_MS', 0),
  // --- handshake mode (item 14) ---------------------------------------------
  // Fresh-handshake rate per arm. Kept modest (each iteration opens a new TCP+TLS
  // connection, so this is also the connection-open rate) to avoid ephemeral-port
  // exhaustion on the client while still yielding thousands of handshakes over the
  // window. Bodies are header-only GET /simple, so log-ring retention is trivially
  // bounded exactly as the forward arms above.
  handshakeRate: num('K6_HS_RATE', 50),
  handshakeDuration: env('K6_HS_DURATION', '1m'),
  // Per-arm DIRECT-TLS targets (distinct SUT containers: server-only TLS 1.3, mTLS
  // required, and native-provider-absent). Empty => that arm is absent (an explicit,
  // documented omission, not a silent zero); proxy.js probes each enabled arm in
  // setup() and fails loud if it cannot handshake.
  tls13Url: env('K6_HS_TLS13_URL', ''),
  mtlsUrl: env('K6_HS_MTLS_URL', ''),
  jdkUrl: env('K6_HS_JDK_URL', ''),
  // Client cert + key (PEM paths, read at init) presented to the mTLS arm's SUT.
  // Required only when mtlsUrl is set; without them the mTLS handshake is rejected
  // by the server (which is exactly the negative control the run also exercises).
  clientCertPath: env('K6_HS_CLIENT_CERT', ''),
  clientKeyPath: env('K6_HS_CLIENT_KEY', ''),
  // --- shared ---------------------------------------------------------------
  // Transport label folded into the result key (<op>_<proto>). Fixed ('proxy')
  // rather than derived from a scheme: each proxy/handshake arm has ONE transport,
  // so unlike regression.js there is no http-vs-https axis to disambiguate.
  proto: env('PROTO', 'proxy'),
  resultPath: env('K6_PROXY_RESULT_PATH', 'proxy-result.json'),
};

export { env, num, bool };
