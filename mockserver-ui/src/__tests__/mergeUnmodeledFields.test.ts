import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  mergeUnmodeledFields,
  unmodeledFieldNames,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '',
    method: 'GET',
    path: '/api/test',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    bodyMatcherType: 'string',
    secure: false,
    priority: 0,
    times: 0,
    ...overrides,
  };
}

const staticAction = (
  extra?: Partial<StandardActionPayload>,
  status = 200,
): StandardActionPayload => ({
  type: 'static',
  static: { statusCode: status, body: '', contentType: '' },
  ...extra,
});

// ---------------------------------------------------------------------------
// mergeUnmodeledFields — the documented passthrough helper
// ---------------------------------------------------------------------------

describe('mergeUnmodeledFields — passthrough of fields the form does not model', () => {
  it('preserves scenario bindings while applying a status-code edit', () => {
    // A stateful mock registered by a client: only matches in the "PAID" state
    // of the "checkout" scenario, then advances it to "SHIPPED".
    const original = {
      httpRequest: { method: 'POST', path: '/checkout' },
      httpResponse: { statusCode: 200, body: 'ok' },
      scenarioName: 'checkout',
      scenarioState: 'PAID',
      newScenarioState: 'SHIPPED',
      namespace: 'orders',
      id: 'checkout-paid',
    };
    // Form re-emits the (modeled) response with a new status code.
    const formJson = buildExpectationJson(
      baseMatcher({ id: 'checkout-paid', method: 'POST', path: '/checkout' }),
      staticAction({ static: { statusCode: 201, body: 'ok', contentType: '' } }),
    );
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });

    expect((merged['httpResponse'] as Record<string, unknown>)['statusCode']).toBe(201);
    // Scenario binding + namespace survive.
    expect(merged['scenarioName']).toBe('checkout');
    expect(merged['scenarioState']).toBe('PAID');
    expect(merged['newScenarioState']).toBe('SHIPPED');
    expect(merged['namespace']).toBe('orders');
    expect(merged['id']).toBe('checkout-paid');
  });

  it('preserves a response sequence (httpResponses/responseMode/responseWeights/switchAfter) when the action is unmodeled and only a matcher field is edited', () => {
    const original = {
      httpRequest: { method: 'GET', path: '/poll' },
      httpResponses: [
        { statusCode: 202, body: 'pending' },
        { statusCode: 200, body: 'done' },
      ],
      responseMode: 'WEIGHTED',
      responseWeights: [3, 1],
      switchAfter: 5,
      id: 'poll-seq',
    };
    // The form cannot model httpResponses, so it emits its default static
    // response — but actionModeled=false means the original action is kept.
    const formJson = buildExpectationJson(
      baseMatcher({ id: 'poll-seq', method: 'GET', path: '/poll/v2' }),
      staticAction(),
    );
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: false });

    // Matcher edit applied.
    expect((merged['httpRequest'] as Record<string, unknown>)['path']).toBe('/poll/v2');
    // Whole response sequence preserved, and no spurious singular httpResponse.
    expect(merged['httpResponses']).toEqual(original.httpResponses);
    expect(merged['responseMode']).toBe('WEIGHTED');
    expect(merged['responseWeights']).toEqual([3, 1]);
    expect(merged['switchAfter']).toBe(5);
    expect(merged['httpResponse']).toBeUndefined();
  });

  it('preserves crossProtocolScenarios', () => {
    const original = {
      httpRequest: { path: '/api/users' },
      httpResponse: { statusCode: 200 },
      crossProtocolScenarios: [
        { trigger: 'DNS_QUERY', matchPattern: 'api.example.com', scenarioName: 'DnsFlow', targetState: 'DnsObserved' },
      ],
      id: 'cross',
    };
    const formJson = buildExpectationJson(
      baseMatcher({ id: 'cross', path: '/api/users' }),
      staticAction(undefined, 204),
    );
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });

    expect((merged['httpResponse'] as Record<string, unknown>)['statusCode']).toBe(204);
    expect(merged['crossProtocolScenarios']).toEqual(original.crossProtocolScenarios);
  });

  it('preserves unmodeled request-matcher fields (keepAlive / socketAddress / protocol) when a modeled matcher field is edited', () => {
    const original = {
      httpRequest: {
        method: 'GET',
        path: '/svc',
        keepAlive: true,
        socketAddress: { host: 'backend', port: 8443, scheme: 'HTTPS' },
        protocol: 'HTTP_2',
      },
      httpResponse: { statusCode: 200 },
      id: 'req-extras',
    };
    // Edit the path only.
    const formJson = buildExpectationJson(
      baseMatcher({ id: 'req-extras', method: 'GET', path: '/svc/v2' }),
      staticAction(),
    );
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });
    const req = merged['httpRequest'] as Record<string, unknown>;

    expect(req['path']).toBe('/svc/v2');
    expect(req['keepAlive']).toBe(true);
    expect(req['socketAddress']).toEqual({ host: 'backend', port: 8443, scheme: 'HTTPS' });
    expect(req['protocol']).toBe('HTTP_2');
  });

  it('preserves the grpcBidiResponse action when unmodeled', () => {
    const original = {
      httpRequest: { path: '/grpc' },
      grpcBidiResponse: { messages: [{ json: '{"a":1}' }] },
      id: 'bidi',
    };
    const formJson = buildExpectationJson(baseMatcher({ id: 'bidi', path: '/grpc/v2' }), staticAction());
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: false });

    expect(merged['grpcBidiResponse']).toEqual(original.grpcBidiResponse);
    expect(merged['httpResponse']).toBeUndefined();
    expect((merged['httpRequest'] as Record<string, unknown>)['path']).toBe('/grpc/v2');
  });

  it('replaces the action family when the form owns the action (switching static → forward drops the old response)', () => {
    const original = {
      httpRequest: { path: '/api' },
      httpResponse: { statusCode: 200, body: 'old' },
      scenarioName: 'flow',
      id: 'switch',
    };
    const formJson = buildExpectationJson(
      baseMatcher({ id: 'switch', path: '/api' }),
      { type: 'forward', forward: { scheme: 'HTTPS', host: 'upstream', port: 443 } },
    );
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });

    expect(merged['httpResponse']).toBeUndefined();
    expect(merged['httpForward']).toEqual({ scheme: 'HTTPS', host: 'upstream', port: 443 });
    // Non-action passthrough still survives an action switch.
    expect(merged['scenarioName']).toBe('flow');
  });

  it('clears every mutually-exclusive action-slot field when the form owns the slot (incl. httpLlmResponse / *ObjectCallback / httpForwardValidateAction)', () => {
    const original = {
      httpRequest: { path: '/api' },
      httpLlmResponse: { provider: 'openai', model: 'gpt-4o' },
      httpForwardObjectCallback: { clientId: 'c1' },
      httpForwardValidateAction: { host: 'up', port: 443 },
      id: 'slot',
    };
    const formJson = buildExpectationJson(baseMatcher({ id: 'slot', path: '/api' }), staticAction());
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });
    // The form's static response replaces the whole slot — no stale action lingers.
    expect(merged['httpLlmResponse']).toBeUndefined();
    expect(merged['httpForwardObjectCallback']).toBeUndefined();
    expect(merged['httpForwardValidateAction']).toBeUndefined();
    expect((merged['httpResponse'] as Record<string, unknown>)['statusCode']).toBe(200);
  });

  it('honours a removal the form can express (clearing priority deletes it)', () => {
    const original = {
      httpRequest: { path: '/api' },
      httpResponse: { statusCode: 200 },
      priority: 10,
      id: 'prio',
    };
    // matcher.priority defaults to 0 → buildExpectationJson omits `priority`.
    const formJson = buildExpectationJson(baseMatcher({ id: 'prio', path: '/api' }), staticAction());
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });

    expect(merged['priority']).toBeUndefined();
  });

  it('does not mutate the original or the form JSON', () => {
    const original = { httpRequest: { path: '/api' }, httpResponse: { statusCode: 200 }, scenarioName: 's' };
    const formJson = buildExpectationJson(baseMatcher({ path: '/api' }), staticAction(undefined, 201));
    const originalSnapshot = structuredClone(original);
    const formSnapshot = structuredClone(formJson);
    mergeUnmodeledFields(original, formJson, { actionModeled: true });
    expect(original).toEqual(originalSnapshot);
    expect(formJson).toEqual(formSnapshot);
  });
});

// ---------------------------------------------------------------------------
// buildExpectationJson integration — editOriginal threads the merge through the
// SAME path used by both the preview and the wire payload.
// ---------------------------------------------------------------------------

describe('buildExpectationJson editOriginal overlay (shared preview + wire path)', () => {
  it('merges onto the retained original when editOriginal is supplied', () => {
    const original = {
      httpRequest: { path: '/checkout' },
      httpResponse: { statusCode: 200 },
      scenarioName: 'checkout',
      scenarioState: 'PAID',
      id: 'x',
    };
    const merged = buildExpectationJson(
      baseMatcher({ id: 'x', path: '/checkout' }),
      staticAction({ editOriginal: original, editActionModeled: true }, 201),
    );
    expect((merged['httpResponse'] as Record<string, unknown>)['statusCode']).toBe(201);
    expect(merged['scenarioName']).toBe('checkout');
    expect(merged['scenarioState']).toBe('PAID');
  });

  it('duplicate flow: passthrough carries over but the id is removed', () => {
    // Duplicate strips id from the value before it reaches the composer.
    const originalWithoutId = {
      httpRequest: { path: '/checkout' },
      httpResponse: { statusCode: 200 },
      scenarioName: 'checkout',
      scenarioState: 'PAID',
    };
    const merged = buildExpectationJson(
      // matcher.id is blank on a duplicate.
      baseMatcher({ id: '', path: '/checkout' }),
      staticAction({ editOriginal: originalWithoutId, editActionModeled: true }),
    );
    expect(merged['id']).toBeUndefined();
    expect(merged['scenarioName']).toBe('checkout');
    expect(merged['scenarioState']).toBe('PAID');
  });

  it('Quick-mock "Update mock" wire payload preserves scenarioName (overlay threaded through the quick static path)', () => {
    // Reproduces the reachable bypass: dashboard Edit → toggle to Quick mock →
    // click "Update mock". The Quick path builds a plain static action; the fix
    // spreads editOriginal/editActionModeled into it so the same merge runs.
    const original = {
      httpRequest: { method: 'GET', path: '/checkout' },
      httpResponse: { statusCode: 200, body: 'ok' },
      scenarioName: 'checkout',
      scenarioState: 'PAID',
      namespace: 'orders',
      crossProtocolScenarios: [{ trigger: 'HTTP_REQUEST', scenarioName: 'checkout', targetState: 'PAID' }],
      id: 'checkout-paid',
    };
    // The original action was a plain httpResponse → the form owns the slot
    // (editActionModeled true), exactly what ComposerView records on load.
    const quickUpdateAction: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 201, body: 'ok', contentType: '' },
      editOriginal: original,
      editActionModeled: true,
    };
    const wire = buildExpectationJson(
      baseMatcher({ id: 'checkout-paid', method: 'GET', path: '/checkout' }),
      quickUpdateAction,
    );
    // The status edit is applied…
    expect((wire['httpResponse'] as Record<string, unknown>)['statusCode']).toBe(201);
    // …and NONE of the unmodeled fields are stripped.
    expect(wire['scenarioName']).toBe('checkout');
    expect(wire['scenarioState']).toBe('PAID');
    expect(wire['namespace']).toBe('orders');
    expect(wire['crossProtocolScenarios']).toEqual(original.crossProtocolScenarios);
    expect(wire['id']).toBe('checkout-paid');
  });

  it('Quick-mock update of a RECOGNISED non-static original (httpForward) preserves it — Quick may not own the slot for a non-httpResponse action', () => {
    // The reachable bug: httpForward is a recognised action (Advanced sets
    // editActionModeled=true), but Quick only authors a static response. Quick
    // must compute quickActionModeled=false here so the forward is preserved and
    // the futile Quick static default is dropped (Advanced is the conversion path).
    const original = {
      httpRequest: { path: '/proxy' },
      httpForward: { scheme: 'HTTPS', host: 'upstream', port: 443 },
      scenarioName: 'flow',
      id: 'fwd',
    };
    const wire = buildExpectationJson(
      baseMatcher({ id: 'fwd', path: '/proxy' }),
      // quickActionModeled === false for a non-httpResponse recognised original.
      { type: 'static', static: { statusCode: 200, body: '', contentType: '' }, editOriginal: original, editActionModeled: false },
    );
    expect(wire['httpForward']).toEqual(original.httpForward);
    expect(wire['httpResponse']).toBeUndefined();
    expect(wire['scenarioName']).toBe('flow');
    // The forward would be surfaced in the Quick "Preserving …" chip.
    expect(unmodeledFieldNames(original, { actionModeled: false })).toContain('httpForward');
  });

  it('Quick-mock update on an UNMODELED-action original (response sequence) preserves it rather than replacing it with a static response', () => {
    // If the original action is not a recognisable static response, ComposerView
    // records editActionModeled=false, so the Quick static default must NOT clobber
    // the sequence (prefer preserving — consistent with the Advanced bias).
    const original = {
      httpRequest: { path: '/poll' },
      httpResponses: [{ statusCode: 202 }, { statusCode: 200 }],
      responseMode: 'SEQUENTIAL',
      id: 'poll-seq',
    };
    const wire = buildExpectationJson(
      baseMatcher({ id: 'poll-seq', path: '/poll' }),
      { type: 'static', static: { statusCode: 200, body: '', contentType: '' }, editOriginal: original, editActionModeled: false },
    );
    expect(wire['httpResponses']).toEqual(original.httpResponses);
    expect(wire['responseMode']).toBe('SEQUENTIAL');
    expect(wire['httpResponse']).toBeUndefined();
  });

  it('new-compose output (no editOriginal) contains no passthrough artifacts', () => {
    const json = buildExpectationJson(
      baseMatcher({ path: '/fresh', method: 'GET' }),
      staticAction(undefined, 200),
    );
    // Only the keys the form itself emits — nothing merged in.
    expect(Object.keys(json).sort()).toEqual(['httpRequest', 'httpResponse'].sort());
    expect(json['scenarioName']).toBeUndefined();
    expect(json['httpResponses']).toBeUndefined();
    expect(json['crossProtocolScenarios']).toBeUndefined();
    // The internal overlay fields never leak into the payload.
    expect(json['editOriginal']).toBeUndefined();
    expect(json['editActionModeled']).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// unmodeledFieldNames — drives the "Preserving N fields …" indicator
// ---------------------------------------------------------------------------

describe('unmodeledFieldNames', () => {
  it('lists top-level and nested request fields the form does not model', () => {
    const original = {
      httpRequest: { method: 'GET', path: '/svc', keepAlive: true, protocol: 'HTTP_2' },
      httpResponse: { statusCode: 200 },
      scenarioName: 'checkout',
      scenarioState: 'PAID',
      rateLimit: { limit: 10, windowMillis: 1000 },
      id: 'z',
      priority: 5,
    };
    const names = unmodeledFieldNames(original, { actionModeled: true });
    expect(names).toContain('scenarioName');
    expect(names).toContain('scenarioState');
    expect(names).toContain('rateLimit');
    expect(names).toContain('httpRequest.keepAlive');
    expect(names).toContain('httpRequest.protocol');
    // Modeled fields are NOT reported.
    expect(names).not.toContain('id');
    expect(names).not.toContain('priority');
    expect(names).not.toContain('httpResponse');
    expect(names).not.toContain('httpRequest.method');
    expect(names).not.toContain('httpRequest.path');
  });

  it('reports a preserved unmodeled action (response sequence) only when actionModeled is false', () => {
    const original = {
      httpRequest: { path: '/poll' },
      httpResponses: [{ statusCode: 200 }],
      responseMode: 'SEQUENTIAL',
    };
    expect(unmodeledFieldNames(original, { actionModeled: false })).toEqual(
      expect.arrayContaining(['httpResponses', 'responseMode']),
    );
    // When the form owns the action slot it will replace these, so they are not
    // reported as "preserved".
    expect(unmodeledFieldNames(original, { actionModeled: true })).not.toContain('httpResponses');
  });

  it('returns an empty list when the form models everything present', () => {
    const original = {
      httpRequest: { method: 'GET', path: '/api' },
      httpResponse: { statusCode: 200 },
      id: 'a',
    };
    expect(unmodeledFieldNames(original, { actionModeled: true })).toEqual([]);
  });
});
