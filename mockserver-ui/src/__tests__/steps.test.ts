import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  stepsFromExpectation,
  standardToJava,
  standardToCurl,
  RESPONDER_CAPABLE_ACTIONS,
  STEP_ACTION_TYPES,
  STEP_ACTION_LABELS,
  type StandardMatcher,
  type StandardActionPayload,
  type StandardExpectationStep,
  type StepActionType,
} from '../lib/standardCodegen';

function baseMatcher(): StandardMatcher {
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
    priority: 0,
    times: 0,
  };
}

function baseAction(): StandardActionPayload {
  return {
    type: 'static',
    static: { statusCode: 200, body: '{"ok":true}', contentType: 'application/json' },
  };
}

function makeStep(overrides?: Partial<StandardExpectationStep>): StandardExpectationStep {
  return {
    actionType: 'httpResponse',
    responder: false,
    actionBody: '{"statusCode":200,"body":"hello"}',
    blocking: true,
    delayValue: 0,
    delayUnit: 'MILLISECONDS',
    timeoutValue: 0,
    timeoutUnit: 'SECONDS',
    failurePolicy: 'BEST_EFFORT',
    ...overrides,
  };
}

function makeResponderStep(overrides?: Partial<StandardExpectationStep>): StandardExpectationStep {
  return makeStep({ responder: true, ...overrides });
}

function makeSideEffectStep(overrides?: Partial<StandardExpectationStep>): StandardExpectationStep {
  return makeStep({
    actionType: 'httpRequest',
    responder: false,
    actionBody: '{"method":"POST","path":"/webhook"}',
    ...overrides,
  });
}

// ---------------------------------------------------------------------------
// buildExpectationJson — steps emission
// ---------------------------------------------------------------------------

describe('buildExpectationJson with steps', () => {
  it('does not include steps when no steps are provided', () => {
    const result = buildExpectationJson(baseMatcher(), baseAction());
    expect(result).not.toHaveProperty('steps');
  });

  it('does not include steps when steps array is empty', () => {
    const action: StandardActionPayload = { ...baseAction(), steps: [] };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('steps');
    // Should still have the top-level action
    expect(result).toHaveProperty('httpResponse');
  });

  it('emits steps array with responder step', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep()],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['steps']).toEqual([
      {
        httpResponse: { statusCode: 200, body: 'hello' },
        responder: true,
      },
    ]);
    // Top-level action should be removed when steps are present
    expect(result).not.toHaveProperty('httpResponse');
  });

  it('emits multiple steps in order', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep(),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps).toHaveLength(2);
    // First step: side-effect webhook
    expect(steps[0]).toHaveProperty('httpRequest');
    expect(steps[0]).not.toHaveProperty('responder');
    // Second step: responder
    expect(steps[1]).toHaveProperty('httpResponse');
    expect(steps[1]!['responder']).toBe(true);
  });

  it('removes top-level action keys when steps are present', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep()],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    // All top-level action keys should be removed
    expect(result).not.toHaveProperty('httpResponse');
    expect(result).not.toHaveProperty('httpForward');
    expect(result).not.toHaveProperty('httpOverrideForwardedRequest');
    expect(result).not.toHaveProperty('httpResponseClassCallback');
    expect(result).not.toHaveProperty('httpError');
    // Should have steps
    expect(result).toHaveProperty('steps');
  });

  it('removes beforeActions/afterActions when steps are present', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [
        {
          position: 'before',
          method: 'GET',
          path: '/auth',
          host: '',
          body: '',
          delayValue: 0,
          delayUnit: 'MILLISECONDS',
          blocking: true,
          timeoutValue: 0,
          timeoutUnit: 'SECONDS',
          failurePolicy: 'BEST_EFFORT',
        },
      ],
      steps: [makeResponderStep()],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('beforeActions');
    expect(result).not.toHaveProperty('afterActions');
    expect(result).toHaveProperty('steps');
  });

  it('emits blocking:false for non-responder steps with blocking=false', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({ blocking: false }),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['blocking']).toBe(false);
  });

  it('omits blocking for non-responder steps when blocking=true (default)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({ blocking: true }),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]).not.toHaveProperty('blocking');
  });

  it('emits delay for non-responder steps with delayValue > 0', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({ delayValue: 500, delayUnit: 'MILLISECONDS' }),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['delay']).toEqual({ timeUnit: 'MILLISECONDS', value: 500 });
  });

  it('emits timeout for non-responder steps with timeoutValue > 0', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({ timeoutValue: 3, timeoutUnit: 'SECONDS' }),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['timeout']).toEqual({ timeUnit: 'SECONDS', value: 3 });
  });

  it('emits failurePolicy FAIL_FAST for non-responder steps', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({ failurePolicy: 'FAIL_FAST' }),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['failurePolicy']).toBe('FAIL_FAST');
  });

  it('omits failurePolicy when BEST_EFFORT (default)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({ failurePolicy: 'BEST_EFFORT' }),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]).not.toHaveProperty('failurePolicy');
  });

  it('does NOT emit side-effect controls for responder steps', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeResponderStep({
          blocking: false,
          delayValue: 100,
          timeoutValue: 5,
          failurePolicy: 'FAIL_FAST',
        }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    const responder = steps[0]!;
    expect(responder).not.toHaveProperty('blocking');
    expect(responder).not.toHaveProperty('delay');
    expect(responder).not.toHaveProperty('timeout');
    expect(responder).not.toHaveProperty('failurePolicy');
    expect(responder['responder']).toBe(true);
  });

  it('handles all supported step action types', () => {
    const actionTypes: StepActionType[] = [
      'httpResponse',
      'httpForward',
      'httpOverrideForwardedRequest',
      'httpError',
      'httpRequest',
      'httpClassCallback',
    ];
    for (const at of actionTypes) {
      const isResponder = RESPONDER_CAPABLE_ACTIONS.has(at);
      const step = makeStep({
        actionType: at,
        responder: isResponder,
        actionBody: '{"test":true}',
      });
      const action: StandardActionPayload = {
        ...baseAction(),
        steps: isResponder ? [step] : [step, makeResponderStep()],
      };
      const result = buildExpectationJson(baseMatcher(), action);
      const steps = result['steps'] as Record<string, unknown>[];
      // The first step should have the correct action type key
      expect(steps[0]).toHaveProperty(at);
    }
  });

  it('parses free-text JSON in actionBody correctly', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeResponderStep({
          actionType: 'httpResponse',
          actionBody: '{"statusCode": 404, "body": "not found", "headers": {"X-Custom": ["val"]}}',
        }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    const payload = steps[0]!['httpResponse'] as Record<string, unknown>;
    expect(payload['statusCode']).toBe(404);
    expect(payload['body']).toBe('not found');
    expect(payload['headers']).toEqual({ 'X-Custom': ['val'] });
  });

  it('handles invalid JSON in actionBody gracefully (sends as string)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeResponderStep({
          actionBody: 'this is not json',
        }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['httpResponse']).toBe('this is not json');
  });

  it('handles empty actionBody as empty object', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep({ actionBody: '' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['httpResponse']).toEqual({});
  });
});

// ---------------------------------------------------------------------------
// stepsFromExpectation — round-trip parsing
// ---------------------------------------------------------------------------

describe('stepsFromExpectation', () => {
  it('returns undefined when no steps array', () => {
    expect(stepsFromExpectation({ httpRequest: {}, httpResponse: {} })).toBeUndefined();
  });

  it('returns undefined for empty steps array', () => {
    expect(stepsFromExpectation({ steps: [] })).toBeUndefined();
  });

  it('parses a single responder step', () => {
    const value = {
      steps: [
        {
          httpResponse: { statusCode: 200, body: 'hello' },
          responder: true,
        },
      ],
    };
    const result = stepsFromExpectation(value)!;
    expect(result).toHaveLength(1);
    expect(result[0]!.actionType).toBe('httpResponse');
    expect(result[0]!.responder).toBe(true);
    expect(JSON.parse(result[0]!.actionBody)).toEqual({ statusCode: 200, body: 'hello' });
  });

  it('parses multiple steps in order', () => {
    const value = {
      steps: [
        { httpRequest: { method: 'POST', path: '/webhook' } },
        { httpResponse: { statusCode: 200 }, responder: true },
      ],
    };
    const result = stepsFromExpectation(value)!;
    expect(result).toHaveLength(2);
    expect(result[0]!.actionType).toBe('httpRequest');
    expect(result[0]!.responder).toBe(false);
    expect(result[1]!.actionType).toBe('httpResponse');
    expect(result[1]!.responder).toBe(true);
  });

  it('parses side-effect controls (blocking, delay, timeout, failurePolicy)', () => {
    const value = {
      steps: [
        {
          httpRequest: { path: '/notify' },
          blocking: false,
          delay: { timeUnit: 'SECONDS', value: 2 },
          timeout: { timeUnit: 'MILLISECONDS', value: 500 },
          failurePolicy: 'FAIL_FAST',
        },
        { httpResponse: { statusCode: 200 }, responder: true },
      ],
    };
    const result = stepsFromExpectation(value)!;
    const sideEffect = result[0]!;
    expect(sideEffect.blocking).toBe(false);
    expect(sideEffect.delayValue).toBe(2);
    expect(sideEffect.delayUnit).toBe('SECONDS');
    expect(sideEffect.timeoutValue).toBe(500);
    expect(sideEffect.timeoutUnit).toBe('MILLISECONDS');
    expect(sideEffect.failurePolicy).toBe('FAIL_FAST');
  });

  it('defaults blocking to true when not present', () => {
    const value = {
      steps: [
        { httpRequest: { path: '/check' } },
        { httpResponse: { statusCode: 200 }, responder: true },
      ],
    };
    const result = stepsFromExpectation(value)!;
    expect(result[0]!.blocking).toBe(true);
  });

  it('defaults failurePolicy to BEST_EFFORT when not present', () => {
    const value = {
      steps: [
        { httpRequest: { path: '/check' } },
        { httpResponse: { statusCode: 200 }, responder: true },
      ],
    };
    const result = stepsFromExpectation(value)!;
    expect(result[0]!.failurePolicy).toBe('BEST_EFFORT');
  });

  it('defaults delay units to MILLISECONDS for unknown units', () => {
    const value = {
      steps: [
        {
          httpRequest: { path: '/slow' },
          delay: { timeUnit: 'HOURS', value: 1 },
        },
        { httpResponse: { statusCode: 200 }, responder: true },
      ],
    };
    const result = stepsFromExpectation(value)!;
    expect(result[0]!.delayUnit).toBe('MILLISECONDS');
  });

  it('parses all supported step action types', () => {
    const actionTypes: StepActionType[] = [
      'httpResponse',
      'httpForward',
      'httpOverrideForwardedRequest',
      'httpError',
      'httpRequest',
      'httpClassCallback',
    ];
    for (const at of actionTypes) {
      const value = {
        steps: [
          { [at]: { test: true }, responder: true },
        ],
      };
      const result = stepsFromExpectation(value)!;
      expect(result).toHaveLength(1);
      expect(result[0]!.actionType).toBe(at);
    }
  });

  it('ignores step entries without a recognized action key', () => {
    const value = {
      steps: [
        { unknownAction: {} },
        { httpResponse: { statusCode: 200 }, responder: true },
      ],
    };
    const result = stepsFromExpectation(value)!;
    expect(result).toHaveLength(1);
    expect(result[0]!.actionType).toBe('httpResponse');
  });

  it('round-trips through buildExpectationJson -> stepsFromExpectation', () => {
    const originalSteps: StandardExpectationStep[] = [
      makeSideEffectStep({
        blocking: false,
        delayValue: 100,
        delayUnit: 'MILLISECONDS',
        timeoutValue: 3,
        timeoutUnit: 'SECONDS',
        failurePolicy: 'FAIL_FAST',
      }),
      makeResponderStep({
        actionBody: '{"statusCode":201,"body":"created"}',
      }),
    ];
    const action: StandardActionPayload = { ...baseAction(), steps: originalSteps };
    const json = buildExpectationJson(baseMatcher(), action);
    const parsed = stepsFromExpectation(json)!;
    expect(parsed).toHaveLength(2);

    // Side-effect step
    expect(parsed[0]!.actionType).toBe('httpRequest');
    expect(parsed[0]!.responder).toBe(false);
    expect(parsed[0]!.blocking).toBe(false);
    expect(parsed[0]!.delayValue).toBe(100);
    expect(parsed[0]!.delayUnit).toBe('MILLISECONDS');
    expect(parsed[0]!.timeoutValue).toBe(3);
    expect(parsed[0]!.timeoutUnit).toBe('SECONDS');
    expect(parsed[0]!.failurePolicy).toBe('FAIL_FAST');

    // Responder step
    expect(parsed[1]!.actionType).toBe('httpResponse');
    expect(parsed[1]!.responder).toBe(true);
    const body = JSON.parse(parsed[1]!.actionBody);
    expect(body.statusCode).toBe(201);
    expect(body.body).toBe('created');
  });
});

// ---------------------------------------------------------------------------
// Exactly-one-responder enforcement
// ---------------------------------------------------------------------------

describe('exactly-one-responder enforcement', () => {
  it('RESPONDER_CAPABLE_ACTIONS includes the correct action types', () => {
    expect(RESPONDER_CAPABLE_ACTIONS.has('httpResponse')).toBe(true);
    expect(RESPONDER_CAPABLE_ACTIONS.has('httpForward')).toBe(true);
    expect(RESPONDER_CAPABLE_ACTIONS.has('httpOverrideForwardedRequest')).toBe(true);
    expect(RESPONDER_CAPABLE_ACTIONS.has('httpError')).toBe(true);
    expect(RESPONDER_CAPABLE_ACTIONS.has('httpClassCallback')).toBe(true);
    // httpRequest (webhook) is NOT responder-capable
    expect(RESPONDER_CAPABLE_ACTIONS.has('httpRequest')).toBe(false);
  });

  it('STEP_ACTION_TYPES includes all expected action types', () => {
    expect(STEP_ACTION_TYPES).toContain('httpResponse');
    expect(STEP_ACTION_TYPES).toContain('httpForward');
    expect(STEP_ACTION_TYPES).toContain('httpOverrideForwardedRequest');
    expect(STEP_ACTION_TYPES).toContain('httpError');
    expect(STEP_ACTION_TYPES).toContain('httpRequest');
    expect(STEP_ACTION_TYPES).toContain('httpClassCallback');
    expect(STEP_ACTION_TYPES).toHaveLength(6);
  });

  it('STEP_ACTION_LABELS has labels for all action types', () => {
    for (const at of STEP_ACTION_TYPES) {
      expect(STEP_ACTION_LABELS[at]).toBeTruthy();
      expect(typeof STEP_ACTION_LABELS[at]).toBe('string');
    }
  });

  it('buildExpectationJson emits responder:true for exactly one step', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep(),
        makeResponderStep(),
        makeSideEffectStep({ actionBody: '{"method":"PUT","path":"/audit"}' }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    const responders = steps.filter((s) => s['responder'] === true);
    expect(responders).toHaveLength(1);
    expect(steps[1]!['responder']).toBe(true);
  });

  it('non-responder steps do not have responder key at all', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep(),
        makeResponderStep(),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    // Non-responder step should not have responder field
    expect(steps[0]).not.toHaveProperty('responder');
  });
});

// ---------------------------------------------------------------------------
// standardToJava with steps
// ---------------------------------------------------------------------------

describe('standardToJava with steps', () => {
  it('includes step() import when steps are present', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep()],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.ExpectationStep.step;');
    expect(java).toContain('import org.mockserver.model.ExpectationStep;');
  });

  it('includes .withSteps() call', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep()],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withSteps(');
    expect(java).toContain('step()');
    expect(java).toContain('.withResponder(true)');
  });

  it('includes per-step action target factory imports', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep(),
        makeResponderStep(),
      ],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.HttpResponse.response;');
    expect(java).toContain('import static org.mockserver.model.HttpRequest.request;');
  });

  it('includes blocking/delay/timeout/failurePolicy in Java for non-responder steps', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep({
          blocking: false,
          delayValue: 100,
          delayUnit: 'MILLISECONDS',
          timeoutValue: 5,
          timeoutUnit: 'SECONDS',
          failurePolicy: 'FAIL_FAST',
        }),
        makeResponderStep(),
      ],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withBlocking(false)');
    expect(java).toContain('.withDelay(new Delay(TimeUnit.MILLISECONDS, 100))');
    expect(java).toContain('.withTimeout(new Delay(TimeUnit.SECONDS, 5))');
    expect(java).toContain('.withFailurePolicy(FailurePolicy.FAIL_FAST)');
  });

  it('does not include side-effect controls for responder steps in Java', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep({ blocking: false, delayValue: 100, timeoutValue: 5, failurePolicy: 'FAIL_FAST' })],
    };
    const java = standardToJava(baseMatcher(), action);
    // The Java codegen for steps should NOT emit blocking/delay/timeout/failurePolicy for responders
    expect(java).not.toContain('.withBlocking(');
    expect(java).not.toContain('.withDelay(');
    expect(java).not.toContain('.withTimeout(');
    expect(java).not.toContain('.withFailurePolicy(');
  });

  it('does not emit before/after action chains when steps are used', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep()],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).not.toContain('.withBeforeAction(');
    expect(java).not.toContain('.withAfterAction(');
    expect(java).not.toContain('.respond(');
    expect(java).not.toContain('.forward(');
    expect(java).not.toContain('.error(');
  });

  it('includes JSON tab note for step payloads', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [makeResponderStep()],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('See the JSON tab for the full step bodies');
  });
});

// ---------------------------------------------------------------------------
// standardToCurl with steps
// ---------------------------------------------------------------------------

describe('standardToCurl with steps', () => {
  it('includes steps in the curl JSON payload', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeSideEffectStep(),
        makeResponderStep(),
      ],
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"steps"');
    expect(curl).toContain('"responder":true');
    // The JSON should have steps as a top-level key, and httpResponse only
    // inside a step object, not as a sibling of httpRequest at the top level.
    // Parse the JSON from the curl payload to verify structure.
    const jsonMatch = curl.match(/-d '(.+)'/s);
    expect(jsonMatch).toBeTruthy();
    const payload = JSON.parse(jsonMatch![1]!);
    expect(payload).toHaveProperty('steps');
    // Top-level httpResponse should NOT exist (it was deleted by steps logic)
    expect(payload).not.toHaveProperty('httpResponse');
  });

  it('does not include beforeActions/afterActions in curl when steps are present', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [
        {
          position: 'before',
          method: 'GET',
          path: '/auth',
          host: '',
          body: '',
          delayValue: 0,
          delayUnit: 'MILLISECONDS',
          blocking: true,
          timeoutValue: 0,
          timeoutUnit: 'SECONDS',
          failurePolicy: 'BEST_EFFORT',
        },
      ],
      steps: [makeResponderStep()],
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).not.toContain('"beforeActions"');
    expect(curl).toContain('"steps"');
  });
});

// ---------------------------------------------------------------------------
// Steps pipeline ordering invariants
// ---------------------------------------------------------------------------

describe('steps ordering', () => {
  it('preserves step order in JSON emission', () => {
    const steps: StandardExpectationStep[] = [
      makeSideEffectStep({ actionBody: '{"path":"/first"}' }),
      makeSideEffectStep({ actionBody: '{"path":"/second"}' }),
      makeResponderStep({ actionBody: '{"statusCode":201}' }),
      makeSideEffectStep({ actionBody: '{"path":"/third"}' }),
    ];
    const action: StandardActionPayload = { ...baseAction(), steps };
    const result = buildExpectationJson(baseMatcher(), action);
    const stepsJson = result['steps'] as Record<string, unknown>[];
    expect(stepsJson).toHaveLength(4);
    expect((stepsJson[0]!['httpRequest'] as Record<string, unknown>)['path']).toBe('/first');
    expect((stepsJson[1]!['httpRequest'] as Record<string, unknown>)['path']).toBe('/second');
    expect((stepsJson[2]!['httpResponse'] as Record<string, unknown>)['statusCode']).toBe(201);
    expect((stepsJson[3]!['httpRequest'] as Record<string, unknown>)['path']).toBe('/third');
  });

  it('preserves step order through round-trip', () => {
    const steps: StandardExpectationStep[] = [
      makeSideEffectStep({ actionBody: '{"path":"/a"}' }),
      makeResponderStep({ actionBody: '{"statusCode":200}' }),
      makeSideEffectStep({ actionBody: '{"path":"/b"}' }),
    ];
    const action: StandardActionPayload = { ...baseAction(), steps };
    const json = buildExpectationJson(baseMatcher(), action);
    const parsed = stepsFromExpectation(json)!;
    expect(parsed).toHaveLength(3);
    expect(parsed[0]!.actionType).toBe('httpRequest');
    expect(parsed[1]!.actionType).toBe('httpResponse');
    expect(parsed[1]!.responder).toBe(true);
    expect(parsed[2]!.actionType).toBe('httpRequest');
  });
});

// ---------------------------------------------------------------------------
// Steps with httpForward action type
// ---------------------------------------------------------------------------

describe('steps with httpForward', () => {
  it('emits httpForward step as responder', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeStep({
          actionType: 'httpForward',
          responder: true,
          actionBody: '{"scheme":"HTTPS","host":"api.example.com","port":443}',
        }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['httpForward']).toEqual({
      scheme: 'HTTPS',
      host: 'api.example.com',
      port: 443,
    });
    expect(steps[0]!['responder']).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Steps with httpClassCallback action type
// ---------------------------------------------------------------------------

describe('steps with httpClassCallback', () => {
  it('emits httpClassCallback step correctly', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      steps: [
        makeStep({
          actionType: 'httpClassCallback',
          responder: true,
          actionBody: '{"callbackClass":"com.example.MyCallback"}',
        }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const steps = result['steps'] as Record<string, unknown>[];
    expect(steps[0]!['httpClassCallback']).toEqual({ callbackClass: 'com.example.MyCallback' });
    expect(steps[0]!['responder']).toBe(true);
  });
});
