/**
 * Python client-code generator — dedicated test suite.
 *
 * The Python emitter (src/lib/codegen/python.ts) was rewritten to build TYPED
 * client objects — Expectation(http_request=HttpRequest(...), ...) — instead of
 * embedding one opaque `Expectation.from_dict({ ...JSON... })` blob. Because its
 * output no longer matches the shared byte-identity golden the other emitters use
 * (emitterGolden.ts), Python is excluded from that harness and proven here
 * instead, three ways:
 *
 *  1. BYTE-IDENTITY — each combo reproduces the committed pythonGolden fixture.
 *  2. STRUCTURE / COVERAGE — every top-level wire key the composer can emit maps
 *     to a typed keyword argument (no raw-dict passthrough remains; no from_dict).
 *  3. EXECUTION EQUIVALENCE (gated on a local python3 + the in-repo client) — the
 *     generated snippet, executed against `mockserver-client-python`, reconstructs
 *     via `.to_dict()` the SAME expectation the JSON tab (buildExpectationJson)
 *     produces. This is the strongest proof: it runs the real client model.
 */
// Node built-ins used by the execution proof are ambiently declared in the
// co-located node-builtins.d.ts (the UI package ships no @types/node by design).
import { describe, it, expect } from 'vitest';
import { execFileSync } from 'child_process';
import { mkdtempSync, writeFileSync, existsSync } from 'fs';
import { tmpdir } from 'os';
import { join, resolve } from 'path';
import { standardToPython, buildExpectationJson } from '../lib/standardCodegen';
import { combos } from '../lib/codegen/extractParityCases';
import { pythonGolden } from '../lib/codegen/__fixtures__/pythonGolden';

/**
 * The one-to-one mapping from every top-level `buildExpectationJson` wire key to
 * the Python Expectation keyword argument it is emitted as. If the emitter ever
 * produces a wire key absent from this table the coverage test fails — that is
 * the guard that keeps the "no raw-dict passthrough / manifest empty" invariant.
 */
const KWARG_BY_WIRE: Record<string, string> = {
  httpRequest: 'http_request',
  httpResponse: 'http_response',
  httpForward: 'http_forward',
  httpOverrideForwardedRequest: 'http_override_forwarded_request',
  httpResponseClassCallback: 'http_response_class_callback',
  httpResponseTemplate: 'http_response_template',
  httpError: 'http_error',
  httpForwardWithFallback: 'http_forward_with_fallback',
  httpWebSocketResponse: 'http_websocket_response',
  httpSseResponse: 'http_sse_response',
  binaryResponse: 'binary_response',
  dnsResponse: 'dns_response',
  httpForwardTemplate: 'http_forward_template',
  httpForwardClassCallback: 'http_forward_class_callback',
  grpcStreamResponse: 'grpc_stream_response',
  chaos: 'chaos',
  beforeActions: 'before_actions',
  afterActions: 'after_actions',
  steps: 'steps',
  capture: 'capture',
  scenarioName: 'scenario_name',
  scenarioState: 'scenario_state',
  newScenarioState: 'new_scenario_state',
  id: 'id',
  priority: 'priority',
  times: 'times',
  timeToLive: 'time_to_live',
  namespace: 'namespace',
  // Edit-preserved actions/siblings the standard form cannot model but an edit
  // overlay carries through — each mapped to its typed Expectation kwarg.
  httpLlmResponse: 'http_llm_response',
  httpResponses: 'http_responses',
  responseMode: 'response_mode',
  responseWeights: 'response_weights',
  switchAfter: 'switch_after',
  httpResponseObjectCallback: 'http_response_object_callback',
  httpForwardObjectCallback: 'http_forward_object_callback',
  httpForwardValidateAction: 'http_forward_validate_action',
  grpcBidiResponse: 'grpc_bidi_response',
  rateLimit: 'rate_limit',
  crossProtocolScenarios: 'cross_protocol_scenarios',
  percentage: 'percentage',
  timestamp: 'timestamp',
};

describe('standardToPython — byte-identity golden', () => {
  for (const combo of combos) {
    it(`${combo.name}`, () => {
      const actual = standardToPython(combo.matcher, combo.action, combo.baseUrl);
      expect(actual).toBe(pythonGolden[combo.name]);
    });
  }
});

describe('standardToPython — typed construction (no JSON blob)', () => {
  for (const combo of combos) {
    const code = standardToPython(combo.matcher, combo.action, combo.baseUrl);

    it(`${combo.name}: never embeds a from_dict / raw JSON payload`, () => {
      expect(code).not.toContain('from_dict');
      // The whole-payload dict form would open the Expectation call with a bare `{`.
      expect(code).not.toContain('Expectation({');
      expect(code).not.toContain('.upsert({');
    });

    it(`${combo.name}: registers via upsert with typed Expectation()`, () => {
      expect(code).toContain('.upsert(');
      expect(code).toContain('Expectation(');
      expect(code).toMatch(/^from mockserver import /m);
    });

    it(`${combo.name}: every top-level wire key maps to a typed kwarg`, () => {
      const wire = buildExpectationJson(combo.matcher, combo.action) as Record<string, unknown>;
      for (const key of Object.keys(wire)) {
        const kwarg = KWARG_BY_WIRE[key];
        // Guard: an unmapped key means a field escaped typed construction.
        expect(kwarg, `unmapped top-level wire key "${key}"`).toBeDefined();
        expect(code, `expected kwarg "${kwarg}=" for wire key "${key}"`).toContain(`${kwarg}=`);
      }
    });
  }

  it('exercises a representative spread of typed classes across the suite', () => {
    const all = combos.map((c) => standardToPython(c.matcher, c.action, c.baseUrl)).join('\n');
    for (const cls of [
      'HttpRequest(', 'HttpResponse(', 'Body(', 'Jwt(', 'KeyToMultiValue(', 'ConnectionOptions(',
      'Delay(', 'HttpForward(', 'HttpOverrideForwardedRequest(', 'HttpClassCallback(', 'HttpTemplate(',
      'HttpError(', 'HttpForwardWithFallback(', 'HttpWebSocketResponse(', 'WebSocketMessage(',
      'WebSocketFrameMatcher(', 'HttpSseResponse(', 'SseEvent(', 'BinaryResponse(', 'DnsRequestDefinition(',
      'DnsResponse(', 'DnsRecord(', 'GrpcStreamResponse(', 'GrpcStreamMessage(', 'AllOfBody(', 'RegexBody(',
      'XPathBody(', 'AfterAction(', 'ExpectationStep(', 'CaptureRule(', 'Times(', 'TimeToLive(',
    ]) {
      expect(all, `expected some combo to construct ${cls}`).toContain(cls);
    }
  });
});

// ---------------------------------------------------------------------------
// Execution-equivalence proof — gated on a local python3 + the in-repo client.
// Kept gated (not deleted) so the suite degrades gracefully where python3 is
// absent; where present it MUST run and pass, not merely skip.
// ---------------------------------------------------------------------------

function pythonAvailable(): boolean {
  try {
    execFileSync('python3', ['--version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

const REPO_ROOT = resolve(process.cwd(), '..');
const CLIENT_DIR = join(REPO_ROOT, 'mockserver-client-python');
const CAN_EXECUTE = pythonAvailable() && existsSync(join(CLIENT_DIR, 'mockserver', '__init__.py'));

// Executes every generated snippet against the real client, captures the upserted
// Expectation via a monkeypatched client, and compares .to_dict() against
// buildExpectationJson under a semantic normaliser (header/query object-map vs
// KeyToMultiValue list; requestOverride↔httpRequest JsonAlias). Includes a
// negative control so a false pass cannot hide.
const VERIFIER = String.raw`
import json, sys, copy, os
CLIENT_DIR, MANIFEST = sys.argv[1], sys.argv[2]
sys.path.insert(0, CLIENT_DIR)
import mockserver

def norm_kmv(v):
    pairs = []
    if isinstance(v, dict):
        for k, vals in v.items():
            pairs.append([k, list(vals) if isinstance(vals, list) else [vals]])
    elif isinstance(v, list):
        for item in v:
            if isinstance(item, dict) and 'name' in item:
                pairs.append([item['name'], list(item.get('values', []))])
            else:
                pairs.append(item)
    pairs.sort(key=lambda p: json.dumps(p, sort_keys=True))
    return pairs

def norm(x):
    if isinstance(x, dict):
        out = {}
        for k, v in x.items():
            if k == 'requestOverride':
                k = 'httpRequest'
            out[k] = norm_kmv(v) if k in ('headers', 'queryStringParameters', 'trailers') else norm(v)
        return out
    if isinstance(x, list):
        return [norm(i) for i in x]
    return x

def capture(code):
    captured = {}
    class Recorder:
        def __init__(self, *a, **k): pass
        def upsert(self, *exps):
            captured['dict'] = exps[0].to_dict()
            return list(exps)
    orig = mockserver.MockServerClient
    mockserver.MockServerClient = Recorder
    try:
        exec(compile(code, '<generated>', 'exec'), {})
    finally:
        mockserver.MockServerClient = orig
    return captured['dict']

entries = json.load(open(MANIFEST))
failed = []
for e in entries:
    if norm(capture(e['code'])) != norm(e['expected']):
        failed.append(e['name'])
neg = copy.deepcopy(entries[0])
neg['expected'].setdefault('httpResponse', {})['statusCode'] = 599
neg_detected = norm(capture(neg['code'])) != norm(neg['expected'])
print(json.dumps({'total': len(entries), 'failed': failed, 'negativeControlDetected': neg_detected}))
`;

describe('standardToPython — execution equivalence (real client round-trip)', () => {
  (CAN_EXECUTE ? it : it.skip)('reconstructs the same expectation as the JSON tab for every combo', () => {
    const manifest = combos.map((c) => ({
      name: c.name,
      code: standardToPython(c.matcher, c.action, c.baseUrl),
      expected: buildExpectationJson(c.matcher, c.action),
    }));
    const dir = mkdtempSync(join(tmpdir(), 'py-codegen-'));
    const manifestPath = join(dir, 'manifest.json');
    const verifierPath = join(dir, 'verify.py');
    writeFileSync(manifestPath, JSON.stringify(manifest));
    writeFileSync(verifierPath, VERIFIER);

    const out = execFileSync('python3', [verifierPath, CLIENT_DIR, manifestPath], { encoding: 'utf8' });
    const result = JSON.parse(out.trim().split('\n').pop() as string) as {
      total: number;
      failed: string[];
      negativeControlDetected: boolean;
    };

    expect(result.total).toBe(combos.length);
    expect(result.failed).toEqual([]);
    expect(result.negativeControlDetected).toBe(true);
  });
});
