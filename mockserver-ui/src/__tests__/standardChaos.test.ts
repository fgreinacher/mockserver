import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  buildChaosJson,
  chaosFromExpectation,
  standardToJava,
  standardChaosErrorStatusError,
  standardChaosErrorProbabilityError,
  hasStandardChaosRangeErrors,
  type StandardMatcher,
  type StandardActionPayload,
  type StandardChaosDraft,
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

// ---------------------------------------------------------------------------
// buildChaosJson
// ---------------------------------------------------------------------------

describe('buildChaosJson', () => {
  it('returns undefined when draft is empty', () => {
    expect(buildChaosJson({})).toBeUndefined();
  });

  it('emits errorStatus and errorProbability', () => {
    const result = buildChaosJson({ errorStatus: 503, errorProbability: 0.5 });
    expect(result).toEqual({ errorStatus: 503, errorProbability: 0.5 });
  });

  it('emits retryAfter', () => {
    const result = buildChaosJson({ errorStatus: 429, errorProbability: 1.0, retryAfter: '30' });
    expect(result).toEqual({ errorStatus: 429, errorProbability: 1.0, retryAfter: '30' });
  });

  it('emits latency as a Delay object', () => {
    const result = buildChaosJson({ latencyValue: 2000, latencyUnit: 'MILLISECONDS' });
    expect(result).toEqual({ latency: { timeUnit: 'MILLISECONDS', value: 2000 } });
  });

  it('omits latency when value is 0', () => {
    const result = buildChaosJson({ latencyValue: 0, latencyUnit: 'SECONDS' });
    expect(result).toBeUndefined();
  });

  it('emits seed', () => {
    const result = buildChaosJson({ errorStatus: 500, errorProbability: 0.3, seed: 42 });
    expect(result).toEqual({ errorStatus: 500, errorProbability: 0.3, seed: 42 });
  });

  it('emits succeedFirst and failRequestCount', () => {
    const result = buildChaosJson({
      errorStatus: 503,
      errorProbability: 1.0,
      succeedFirst: 2,
      failRequestCount: 3,
    });
    expect(result).toEqual({
      errorStatus: 503,
      errorProbability: 1.0,
      succeedFirst: 2,
      failRequestCount: 3,
    });
  });

  it('emits all 7 fields together', () => {
    const draft: StandardChaosDraft = {
      errorStatus: 429,
      errorProbability: 0.8,
      retryAfter: '60',
      latencyValue: 500,
      latencyUnit: 'MILLISECONDS',
      seed: 12345,
      succeedFirst: 0,
      failRequestCount: 5,
    };
    const result = buildChaosJson(draft);
    expect(result).toEqual({
      errorStatus: 429,
      errorProbability: 0.8,
      retryAfter: '60',
      latency: { timeUnit: 'MILLISECONDS', value: 500 },
      seed: 12345,
      succeedFirst: 0,
      failRequestCount: 5,
    });
  });
});

// ---------------------------------------------------------------------------
// buildExpectationJson with chaos — top-level placement
// ---------------------------------------------------------------------------

describe('buildExpectationJson with chaos', () => {
  it('does not include chaos when action has no chaos', () => {
    const result = buildExpectationJson(baseMatcher(), baseAction());
    expect(result).not.toHaveProperty('chaos');
  });

  it('includes chaos as a top-level field (sibling of httpRequest)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 500, errorProbability: 1.0 },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['chaos']).toEqual({ errorStatus: 500, errorProbability: 1.0 });
    expect(result['httpRequest']).toBeDefined();
    expect(result['httpResponse']).toBeDefined();
    // chaos should NOT be nested inside httpResponse
    const resp = result['httpResponse'] as Record<string, unknown>;
    expect(resp).not.toHaveProperty('chaos');
  });

  it('omits chaos when all draft fields are empty', () => {
    const action: StandardActionPayload = { ...baseAction(), chaos: {} };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result).not.toHaveProperty('chaos');
  });

  it('includes chaos with latency on a forward action', () => {
    const action: StandardActionPayload = {
      type: 'forward',
      forward: { scheme: 'HTTPS', host: 'api.example.com', port: 443 },
      chaos: { latencyValue: 3000, latencyUnit: 'MILLISECONDS' },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    expect(result['chaos']).toEqual({ latency: { timeUnit: 'MILLISECONDS', value: 3000 } });
    expect(result['httpForward']).toBeDefined();
  });

  it('includes succeedFirst=0 in chaos output', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 1.0, succeedFirst: 0, failRequestCount: 2 },
    };
    const result = buildExpectationJson(baseMatcher(), action);
    const chaos = result['chaos'] as Record<string, unknown>;
    expect(chaos['succeedFirst']).toBe(0);
    expect(chaos['failRequestCount']).toBe(2);
  });
});

// ---------------------------------------------------------------------------
// chaosFromExpectation — round-trip parsing
// ---------------------------------------------------------------------------

describe('chaosFromExpectation', () => {
  it('returns undefined when no chaos block', () => {
    expect(chaosFromExpectation({ httpRequest: {}, httpResponse: {} })).toBeUndefined();
  });

  it('parses all 7 fields', () => {
    const value = {
      chaos: {
        errorStatus: 429,
        errorProbability: 0.5,
        retryAfter: '30',
        latency: { timeUnit: 'SECONDS', value: 2 },
        seed: 42,
        succeedFirst: 1,
        failRequestCount: 3,
      },
    };
    const draft = chaosFromExpectation(value);
    expect(draft).toEqual({
      errorStatus: 429,
      errorProbability: 0.5,
      retryAfter: '30',
      latencyUnit: 'SECONDS',
      latencyValue: 2,
      seed: 42,
      succeedFirst: 1,
      failRequestCount: 3,
    });
  });

  it('round-trips through buildChaosJson -> chaosFromExpectation', () => {
    const original: StandardChaosDraft = {
      errorStatus: 503,
      errorProbability: 1.0,
      retryAfter: '60',
      latencyValue: 1000,
      latencyUnit: 'MILLISECONDS',
      seed: 999,
      succeedFirst: 0,
      failRequestCount: 5,
    };
    const json = buildChaosJson(original)!;
    const parsed = chaosFromExpectation({ chaos: json });
    expect(parsed).toEqual(original);
  });

  it('round-trips through buildExpectationJson -> chaosFromExpectation', () => {
    const chaosDraft: StandardChaosDraft = {
      errorStatus: 500,
      errorProbability: 0.3,
      seed: 7,
    };
    const action: StandardActionPayload = { ...baseAction(), chaos: chaosDraft };
    const jsonObj = buildExpectationJson(baseMatcher(), action);
    const parsed = chaosFromExpectation(jsonObj);
    expect(parsed).toEqual(chaosDraft);
  });

  it('handles latency with MINUTES unit', () => {
    const draft = chaosFromExpectation({
      chaos: { latency: { timeUnit: 'MINUTES', value: 5 } },
    });
    expect(draft).toEqual({ latencyUnit: 'MINUTES', latencyValue: 5 });
  });

  it('defaults latency unit to MILLISECONDS when unknown', () => {
    const draft = chaosFromExpectation({
      chaos: { latency: { timeUnit: 'HOURS', value: 1 } },
    });
    expect(draft?.latencyUnit).toBe('MILLISECONDS');
  });
});

// ---------------------------------------------------------------------------
// standardToJava with chaos
// ---------------------------------------------------------------------------

describe('standardToJava with chaos', () => {
  it('includes chaos imports and .withChaos() builder', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 1.0 },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;');
    expect(java).toContain('.withChaos(');
    expect(java).toContain('httpChaosProfile()');
    expect(java).toContain('.withErrorStatus(503)');
    expect(java).toContain('.withErrorProbability(1.0)');
  });

  it('formats errorProbability as double literal: 1.0, 0.0, and fractional', () => {
    // whole number 1.0 => "1.0"
    const action1: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 1.0 },
    };
    expect(standardToJava(baseMatcher(), action1)).toContain('.withErrorProbability(1.0)');

    // whole number 0.0 => "0.0"
    const action0: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 0.0 },
    };
    expect(standardToJava(baseMatcher(), action0)).toContain('.withErrorProbability(0.0)');

    // fractional 0.3 => "0.3" (no trailing zero added)
    const action03: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 0.3 },
    };
    expect(standardToJava(baseMatcher(), action03)).toContain('.withErrorProbability(0.3)');
  });

  it('does not include Delay/TimeUnit imports when chaos has only errorStatus (no latency)', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 1.0 },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).not.toContain('import org.mockserver.model.Delay;');
    expect(java).not.toContain('import java.util.concurrent.TimeUnit;');
  });

  it('includes Delay/TimeUnit imports when chaos has latency set', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 0.5, latencyValue: 1000, latencyUnit: 'MILLISECONDS' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import org.mockserver.model.Delay;');
    expect(java).toContain('import java.util.concurrent.TimeUnit;');
  });

  it('includes Delay import for chaos latency', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { latencyValue: 2000, latencyUnit: 'MILLISECONDS' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('import org.mockserver.model.Delay;');
    expect(java).toContain('import java.util.concurrent.TimeUnit;');
    expect(java).toContain('.withLatency(new Delay(TimeUnit.MILLISECONDS, 2000))');
  });

  it('includes seed with L suffix', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 500, errorProbability: 0.5, seed: 42 },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withSeed(42L)');
  });

  it('includes succeedFirst and failRequestCount', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 503, errorProbability: 1.0, succeedFirst: 2, failRequestCount: 3 },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withSucceedFirst(2)');
    expect(java).toContain('.withFailRequestCount(3)');
  });

  it('does not include chaos when draft is empty', () => {
    const action: StandardActionPayload = { ...baseAction(), chaos: {} };
    const java = standardToJava(baseMatcher(), action);
    expect(java).not.toContain('.withChaos(');
    expect(java).not.toContain('httpChaosProfile');
  });

  it('includes retryAfter in chaos Java codegen', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: { errorStatus: 429, errorProbability: 1.0, retryAfter: '30' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withRetryAfter("30")');
  });

  it('generates all 7 fields in Java codegen', () => {
    const action: StandardActionPayload = {
      ...baseAction(),
      chaos: {
        errorStatus: 429,
        errorProbability: 0.8,
        retryAfter: '60',
        latencyValue: 500,
        latencyUnit: 'MILLISECONDS',
        seed: 12345,
        succeedFirst: 0,
        failRequestCount: 5,
      },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withErrorStatus(429)');
    expect(java).toContain('.withErrorProbability(0.8)');
    expect(java).toContain('.withRetryAfter("60")');
    expect(java).toContain('.withLatency(new Delay(TimeUnit.MILLISECONDS, 500))');
    expect(java).toContain('.withSeed(12345L)');
    expect(java).toContain('.withSucceedFirst(0)');
    expect(java).toContain('.withFailRequestCount(5)');
  });
});

// ---------------------------------------------------------------------------
// Standard chaos range validation (bounds from HttpChaosProfile.java)
// ---------------------------------------------------------------------------

describe('standardChaosErrorStatusError', () => {
  it('returns undefined for undefined input', () => {
    expect(standardChaosErrorStatusError(undefined)).toBeUndefined();
  });

  it('returns undefined for in-range status (100)', () => {
    expect(standardChaosErrorStatusError(100)).toBeUndefined();
  });

  it('returns undefined for in-range status (599)', () => {
    expect(standardChaosErrorStatusError(599)).toBeUndefined();
  });

  it('returns error for status below 100', () => {
    expect(standardChaosErrorStatusError(99)).toBe('100–599');
  });

  it('returns error for status above 599', () => {
    expect(standardChaosErrorStatusError(600)).toBe('100–599');
  });

  it('returns error for non-integer status', () => {
    expect(standardChaosErrorStatusError(500.5)).toBe('100–599');
  });

  it('returns error for NaN', () => {
    expect(standardChaosErrorStatusError(NaN)).toBe('100–599');
  });
});

describe('standardChaosErrorProbabilityError', () => {
  it('returns undefined for undefined input', () => {
    expect(standardChaosErrorProbabilityError(undefined)).toBeUndefined();
  });

  it('returns undefined for 0.0', () => {
    expect(standardChaosErrorProbabilityError(0.0)).toBeUndefined();
  });

  it('returns undefined for 1.0', () => {
    expect(standardChaosErrorProbabilityError(1.0)).toBeUndefined();
  });

  it('returns undefined for fractional in range', () => {
    expect(standardChaosErrorProbabilityError(0.5)).toBeUndefined();
  });

  it('returns error for negative probability', () => {
    expect(standardChaosErrorProbabilityError(-0.1)).toBe('0.0–1.0');
  });

  it('returns error for probability above 1.0', () => {
    expect(standardChaosErrorProbabilityError(1.1)).toBe('0.0–1.0');
  });

  it('returns error for NaN', () => {
    expect(standardChaosErrorProbabilityError(NaN)).toBe('0.0–1.0');
  });
});

describe('hasStandardChaosRangeErrors', () => {
  it('returns false for empty draft', () => {
    expect(hasStandardChaosRangeErrors({})).toBe(false);
  });

  it('returns false when both fields are in range', () => {
    expect(hasStandardChaosRangeErrors({ errorStatus: 500, errorProbability: 0.5 })).toBe(false);
  });

  it('returns true when errorStatus is out of range', () => {
    expect(hasStandardChaosRangeErrors({ errorStatus: 99, errorProbability: 0.5 })).toBe(true);
  });

  it('returns true when errorProbability is out of range', () => {
    expect(hasStandardChaosRangeErrors({ errorStatus: 500, errorProbability: 1.5 })).toBe(true);
  });

  it('returns true when only errorStatus is set and out of range', () => {
    expect(hasStandardChaosRangeErrors({ errorStatus: 600 })).toBe(true);
  });

  it('returns false when only unvalidated fields are set (latency, seed, etc.)', () => {
    expect(hasStandardChaosRangeErrors({ latencyValue: 5000, seed: 42 })).toBe(false);
  });

  it('returns true for NaN errorStatus', () => {
    expect(hasStandardChaosRangeErrors({ errorStatus: NaN })).toBe(true);
  });

  it('returns true for NaN errorProbability', () => {
    expect(hasStandardChaosRangeErrors({ errorProbability: NaN })).toBe(true);
  });
});
