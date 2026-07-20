import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  sideEffectsFromExpectation,
  standardToJava,
  standardToCurl,
  type StandardMatcher,
  type StandardActionPayload,
  type StandardSideEffectAction,
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

function makeBefore(overrides?: Partial<StandardSideEffectAction>): StandardSideEffectAction {
  return {
    position: 'before',
    method: 'GET',
    path: '/auth',
    host: 'auth.svc:8080',
    body: '',
    delayValue: 0,
    delayUnit: 'MILLISECONDS',
    blocking: true,
    timeoutValue: 2,
    timeoutUnit: 'SECONDS',
    failurePolicy: 'FAIL_FAST',
    ...overrides,
  };
}

function makeAfter(overrides?: Partial<StandardSideEffectAction>): StandardSideEffectAction {
  return {
    position: 'after',
    method: 'POST',
    path: '/audit',
    host: '',
    body: '',
    delayValue: 0,
    delayUnit: 'MILLISECONDS',
    blocking: true,
    timeoutValue: 0,
    timeoutUnit: 'SECONDS',
    failurePolicy: 'BEST_EFFORT',
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// buildExpectationJson — side-effects emission
// ---------------------------------------------------------------------------

describe('buildExpectationJson with sideEffects', () => {
  it('does not include beforeActions / afterActions when no side effects', () => {
    const result = buildExpectationJson(baseMatcher(), baseAction());
    expect(result).not.toHaveProperty('beforeActions');
    expect(result).not.toHaveProperty('afterActions');
  });

  it('does not include beforeActions / afterActions when array is empty', () => {
    const action: StandardActionPayload = { ...baseAction(), sideEffects: [] };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('beforeActions');
    expect(result).not.toHaveProperty('afterActions');
  });

  it('emits beforeActions with httpRequest, timeout, and failurePolicy', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore()],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['beforeActions']).toEqual([
      {
        httpRequest: { method: 'GET', path: '/auth', headers: { Host: ['auth.svc:8080'] } },
        timeout: { timeUnit: 'SECONDS', value: 2 },
        failurePolicy: 'FAIL_FAST',
      },
    ]);
    expect(result).not.toHaveProperty('afterActions');
  });

  it('emits afterActions with httpRequest only', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter()],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['afterActions']).toEqual([
      { httpRequest: { method: 'POST', path: '/audit' } },
    ]);
    expect(result).not.toHaveProperty('beforeActions');
  });

  it('emits both beforeActions and afterActions when mixed', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore(), makeAfter()],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['beforeActions']).toHaveLength(1);
    expect(result['afterActions']).toHaveLength(1);
  });

  it('omits blocking when true (default)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ blocking: true, timeoutValue: 0, failurePolicy: 'BEST_EFFORT' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const beforeArr = result['beforeActions'] as Record<string, unknown>[];
    const before = beforeArr[0]!;
    expect(before).not.toHaveProperty('blocking');
  });

  it('emits blocking: false when explicitly set', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ blocking: false, timeoutValue: 0, failurePolicy: 'BEST_EFFORT' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const beforeArr = result['beforeActions'] as Record<string, unknown>[];
    const before = beforeArr[0]!;
    expect(before['blocking']).toBe(false);
  });

  it('omits failurePolicy when BEST_EFFORT (default)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ failurePolicy: 'BEST_EFFORT', timeoutValue: 0 })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const beforeArr = result['beforeActions'] as Record<string, unknown>[];
    const before = beforeArr[0]!;
    expect(before).not.toHaveProperty('failurePolicy');
  });

  it('includes delay when delayValue > 0', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter({ delayValue: 500, delayUnit: 'MILLISECONDS' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const afterArr = result['afterActions'] as Record<string, unknown>[];
    const after = afterArr[0]!;
    expect(after['delay']).toEqual({ timeUnit: 'MILLISECONDS', value: 500 });
  });

  it('omits delay when delayValue is 0', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter({ delayValue: 0 })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const afterArr = result['afterActions'] as Record<string, unknown>[];
    const after = afterArr[0]!;
    expect(after).not.toHaveProperty('delay');
  });

  it('omits empty method, host, and body from httpRequest', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter({ method: '', host: '', body: '' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const afterArr = result['afterActions'] as Record<string, unknown>[];
    const after = afterArr[0]!;
    const httpReq = after['httpRequest'] as Record<string, unknown>;
    expect(httpReq).not.toHaveProperty('method');
    expect(httpReq).not.toHaveProperty('host');
    expect(httpReq).not.toHaveProperty('headers');
    expect(httpReq).not.toHaveProperty('body');
  });

  it('includes body in httpRequest when present', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter({ body: '{"event":"done"}' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const afterArr = result['afterActions'] as Record<string, unknown>[];
    const after = afterArr[0]!;
    const httpReq = after['httpRequest'] as Record<string, unknown>;
    expect(httpReq['body']).toBe('{"event":"done"}');
  });

  it('skips side-effects with empty path', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ path: '' }), makeAfter({ path: '' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('beforeActions');
    expect(result).not.toHaveProperty('afterActions');
  });

  it('before-only fields are NOT emitted for after-actions', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter({ blocking: false, timeoutValue: 5, failurePolicy: 'FAIL_FAST' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const afterArr = result['afterActions'] as Record<string, unknown>[];
    const after = afterArr[0]!;
    expect(after).not.toHaveProperty('blocking');
    expect(after).not.toHaveProperty('timeout');
    expect(after).not.toHaveProperty('failurePolicy');
  });
});

// ---------------------------------------------------------------------------
// sideEffectsFromExpectation — round-trip parsing
// ---------------------------------------------------------------------------

describe('sideEffectsFromExpectation', () => {
  it('returns undefined when no beforeActions or afterActions', () => {
    expect(sideEffectsFromExpectation({ httpRequest: {}, httpResponse: {} })).toBeUndefined();
  });

  it('parses a single before-action object (not array)', () => {
    const value = {
      beforeActions: {
        httpRequest: { method: 'GET', path: '/auth', host: 'auth.svc:8080' },
        blocking: false,
        timeout: { timeUnit: 'SECONDS', value: 2 },
        failurePolicy: 'FAIL_FAST',
      },
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result).toHaveLength(1);
    expect(result[0]!.position).toBe('before');
    expect(result[0]!.method).toBe('GET');
    expect(result[0]!.path).toBe('/auth');
    expect(result[0]!.host).toBe('auth.svc:8080');
    expect(result[0]!.blocking).toBe(false);
    expect(result[0]!.timeoutValue).toBe(2);
    expect(result[0]!.timeoutUnit).toBe('SECONDS');
    expect(result[0]!.failurePolicy).toBe('FAIL_FAST');
  });

  it('parses an array of after-actions', () => {
    const value = {
      afterActions: [
        { httpRequest: { method: 'POST', path: '/audit' } },
        { httpRequest: { path: '/notify' }, delay: { timeUnit: 'MILLISECONDS', value: 100 } },
      ],
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result).toHaveLength(2);
    expect(result[0]!.position).toBe('after');
    expect(result[0]!.method).toBe('POST');
    expect(result[0]!.path).toBe('/audit');
    expect(result[1]!.position).toBe('after');
    expect(result[1]!.path).toBe('/notify');
    expect(result[1]!.delayValue).toBe(100);
    expect(result[1]!.delayUnit).toBe('MILLISECONDS');
  });

  it('ignores entries without httpRequest (callback-only)', () => {
    const value = {
      beforeActions: [
        { httpRequest: { path: '/auth' } },
        { httpClassCallback: { callbackClass: 'com.example.Foo' } },
      ],
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result).toHaveLength(1);
    expect(result[0]!.path).toBe('/auth');
  });

  it('defaults blocking to true when not present', () => {
    const value = {
      beforeActions: [{ httpRequest: { path: '/check' } }],
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result[0]!.blocking).toBe(true);
  });

  it('defaults failurePolicy to BEST_EFFORT when not present', () => {
    const value = {
      beforeActions: [{ httpRequest: { path: '/check' } }],
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result[0]!.failurePolicy).toBe('BEST_EFFORT');
  });

  it('round-trips through buildExpectationJson -> sideEffectsFromExpectation', () => {
    const original: StandardSideEffectAction[] = [
      makeBefore(),
      makeAfter({ delayValue: 200, delayUnit: 'MILLISECONDS' }),
    ];
    const action: StandardActionPayload = { ...baseAction(), sideEffects: original };
    const json = buildExpectationJson(baseMatcher(), action);
    const parsed = sideEffectsFromExpectation(json)!;
    expect(parsed).toHaveLength(2);
    // Before action
    expect(parsed[0]!.position).toBe('before');
    expect(parsed[0]!.method).toBe('GET');
    expect(parsed[0]!.path).toBe('/auth');
    expect(parsed[0]!.host).toBe('auth.svc:8080');
    expect(parsed[0]!.timeoutValue).toBe(2);
    expect(parsed[0]!.failurePolicy).toBe('FAIL_FAST');
    // After action
    expect(parsed[1]!.position).toBe('after');
    expect(parsed[1]!.method).toBe('POST');
    expect(parsed[1]!.path).toBe('/audit');
    expect(parsed[1]!.delayValue).toBe(200);
  });

  it('handles delay unit MINUTES', () => {
    const value = {
      afterActions: [
        { httpRequest: { path: '/slow' }, delay: { timeUnit: 'MINUTES', value: 1 } },
      ],
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result[0]!.delayUnit).toBe('MINUTES');
    expect(result[0]!.delayValue).toBe(1);
  });

  it('defaults delay unit to MILLISECONDS when unknown', () => {
    const value = {
      afterActions: [
        { httpRequest: { path: '/slow' }, delay: { timeUnit: 'HOURS', value: 1 } },
      ],
    };
    const result = sideEffectsFromExpectation(value)!;
    expect(result[0]!.delayUnit).toBe('MILLISECONDS');
  });
});

// ---------------------------------------------------------------------------
// standardToJava with side-effects
// ---------------------------------------------------------------------------

describe('standardToJava with sideEffects', () => {
  it('includes beforeAction import and .withBeforeAction() chain', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore()],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.AfterAction.beforeAction;');
    expect(java).toContain('.withBeforeAction(');
    expect(java).toContain('beforeAction()');
    // request() must start on its own line after the opening bracket
    expect(java).toContain('.withHttpRequest(\n');
    expect(java).toMatch(/\.withHttpRequest\(\s*\n\s*request\(\)/);
    expect(java).toContain('.withPath("/auth")');
    expect(java).toContain('.withHeader("Host", "auth.svc:8080")');
  });

  it('includes afterAction import and .withAfterAction() chain', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter()],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.AfterAction.afterAction;');
    expect(java).toContain('.withAfterAction(');
    expect(java).toContain('afterAction()');
    expect(java).toContain('.withPath("/audit")');
  });

  it('puts the closing semicolon at the end of the last code line, not on its own line', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore()],
    };
    const java = standardToJava(baseMatcher(), action);
    const codeLines = java.split('\n');
    const lastLine = codeLines[codeLines.length - 1] ?? '';
    expect(lastLine).not.toBe(';');
    expect(lastLine.trim().endsWith(';')).toBe(true);
    expect(lastLine.trim().length).toBeGreaterThan(1);
  });

  it('includes Delay/TimeUnit imports when side-effect has delay', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter({ delayValue: 100, delayUnit: 'MILLISECONDS' })],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import org.mockserver.model.Delay;');
    expect(java).toContain('import java.util.concurrent.TimeUnit;');
    expect(java).toContain('.withDelay(new Delay(TimeUnit.MILLISECONDS, 100))');
  });

  it('includes FailurePolicy import when FAIL_FAST', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ failurePolicy: 'FAIL_FAST' })],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import org.mockserver.model.FailurePolicy;');
    expect(java).toContain('.withFailurePolicy(FailurePolicy.FAIL_FAST)');
  });

  it('does not include FailurePolicy import when BEST_EFFORT (default)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ failurePolicy: 'BEST_EFFORT', timeoutValue: 0 })],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).not.toContain('FailurePolicy');
  });

  it('includes .withBlocking(false) when blocking is false', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ blocking: false, timeoutValue: 0, failurePolicy: 'BEST_EFFORT' })],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withBlocking(false)');
  });

  it('omits .withBlocking() when blocking is true (default)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ blocking: true, timeoutValue: 0, failurePolicy: 'BEST_EFFORT' })],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).not.toContain('.withBlocking(');
  });

  it('includes timeout as Delay in Java codegen', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore({ timeoutValue: 5, timeoutUnit: 'SECONDS' })],
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withTimeout(new Delay(TimeUnit.SECONDS, 5))');
  });

  it('does not include side-effects when array is empty', () => {
    const action: StandardActionPayload = { ...baseAction(), sideEffects: [] };
    const java = standardToJava(baseMatcher(), action);
    expect(java).not.toContain('BeforeAction');
    expect(java).not.toContain('AfterAction');
    expect(java).not.toContain('.withBeforeAction(');
    expect(java).not.toContain('.withAfterAction(');
  });
});

// ---------------------------------------------------------------------------
// standardToCurl — side-effects come through buildExpectationJson automatically
// ---------------------------------------------------------------------------

describe('standardToCurl with sideEffects', () => {
  it('includes beforeActions in the curl JSON payload', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeBefore()],
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"beforeActions"');
    expect(curl).toContain('"failurePolicy":"FAIL_FAST"');
  });

  it('includes afterActions in the curl JSON payload', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      sideEffects: [makeAfter()],
    };
    const curl = standardToCurl(baseMatcher(), action, 'http://localhost:1080');
    expect(curl).toContain('"afterActions"');
  });
});
