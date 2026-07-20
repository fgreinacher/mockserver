import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  captureFromExpectation,
  type StandardMatcher,
  type StandardActionPayload,
  type StandardCaptureRule,
} from '../lib/standardCodegen';

function baseMatcher(): StandardMatcher {
  return {
    id: '',
    method: 'POST',
    path: '/orders',
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

function rule(overrides?: Partial<StandardCaptureRule>): StandardCaptureRule {
  return { source: 'jsonPath', expression: '$.order.id', into: 'orderId', ...overrides };
}

// ---------------------------------------------------------------------------
// buildExpectationJson — capture emission
// ---------------------------------------------------------------------------

describe('buildExpectationJson with capture rules', () => {
  it('omits capture when there are no rules', () => {
    const result = buildExpectationJson(baseMatcher(), baseAction());
    expect(result).not.toHaveProperty('capture');
  });

  it('omits capture when the array is empty', () => {
    const action: StandardActionPayload = { ...baseAction(), capture: [] };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('capture');
  });

  it('includes a capture rule in the built expectation JSON', () => {
    const action: StandardActionPayload = { ...baseAction(), capture: [rule()] };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['capture']).toEqual([
      { source: 'jsonPath', expression: '$.order.id', into: 'orderId' },
    ]);
  });

  it('trims expression and into', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      capture: [rule({ expression: '  $.a  ', into: '  key  ' })],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['capture']).toEqual([
      { source: 'jsonPath', expression: '$.a', into: 'key' },
    ]);
  });

  it('drops blank rows (missing expression or into) and omits capture when all blank', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      capture: [
        rule({ expression: '', into: 'x' }),
        rule({ expression: '$.y', into: '' }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('capture');
  });

  it('keeps only the valid rows when some are blank', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      capture: [
        rule({ source: 'header', expression: 'X-Id', into: 'id' }),
        rule({ expression: '', into: '' }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['capture']).toEqual([
      { source: 'header', expression: 'X-Id', into: 'id' },
    ]);
  });

  it('emits all supported sources', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      capture: [
        rule({ source: 'jsonPath', expression: '$.a', into: 'a' }),
        rule({ source: 'xpath', expression: '/b', into: 'b' }),
        rule({ source: 'header', expression: 'C', into: 'c' }),
        rule({ source: 'queryStringParameter', expression: 'd', into: 'd' }),
        rule({ source: 'cookie', expression: 'e', into: 'e' }),
        rule({ source: 'pathParameter', expression: 'f', into: 'f' }),
      ],
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const sources = (result['capture'] as Array<{ source: string }>).map((c) => c.source);
    expect(sources).toEqual([
      'jsonPath', 'xpath', 'header', 'queryStringParameter', 'cookie', 'pathParameter',
    ]);
  });
});

// ---------------------------------------------------------------------------
// captureFromExpectation — round-trip parsing
// ---------------------------------------------------------------------------

describe('captureFromExpectation', () => {
  it('returns undefined when there is no capture key', () => {
    expect(captureFromExpectation({ httpRequest: {}, httpResponse: {} })).toBeUndefined();
  });

  it('returns undefined when capture is not an array', () => {
    expect(captureFromExpectation({ capture: {} as unknown })).toBeUndefined();
  });

  it('returns undefined when capture is an empty array', () => {
    expect(captureFromExpectation({ capture: [] })).toBeUndefined();
  });

  it('parses capture rules from an existing expectation', () => {
    const value = {
      capture: [
        { source: 'jsonPath', expression: '$.order.id', into: 'orderId' },
        { source: 'header', expression: 'X-Request-Id', into: 'reqId' },
      ],
    };
    const result = captureFromExpectation(value)!;
    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({ source: 'jsonPath', expression: '$.order.id', into: 'orderId' });
    expect(result[1]).toEqual({ source: 'header', expression: 'X-Request-Id', into: 'reqId' });
  });

  it('falls back to jsonPath for an unknown source', () => {
    const result = captureFromExpectation({ capture: [{ source: 'bogus', expression: '$.a', into: 'a' }] })!;
    expect(result[0]!.source).toBe('jsonPath');
  });

  it('defaults missing expression / into to empty strings', () => {
    const result = captureFromExpectation({ capture: [{ source: 'cookie' }] })!;
    expect(result[0]).toEqual({ source: 'cookie', expression: '', into: '' });
  });

  it('round-trips through buildExpectationJson -> captureFromExpectation', () => {
    const original: StandardCaptureRule[] = [
      rule({ source: 'queryStringParameter', expression: 'sessionId', into: 'session' }),
      rule({ source: 'pathParameter', expression: 'userId', into: 'user' }),
    ];
    const action: StandardActionPayload = { ...baseAction(), capture: original };
    const json = buildExpectationJson(baseMatcher(), action);
    const parsed = captureFromExpectation(json)!;
    expect(parsed).toEqual(original);
  });
});
