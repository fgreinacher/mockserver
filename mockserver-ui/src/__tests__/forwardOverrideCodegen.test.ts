import { describe, it, expect } from 'vitest';
import { standardToJava, type StandardMatcher, type StandardActionPayload } from '../lib/standardCodegen';

function baseMatcher(): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    priority: 0, times: 0,
  };
}

describe('forward_override Java code generation is correctly indented', () => {
  it('emits request() and its builder calls at a consistent, increasing indent', () => {
    const action: StandardActionPayload = {
      type: 'forward_override',
      forwardOverride: {
        overrideMethod: 'PUT',
        overrideHost: 'upstream:9090',
        overrideScheme: 'HTTPS',
        overridePath: '/v2/api',
        overrideQueryString: '',
        overrideHeaders: '',
        overrideBody: '',
      },
    };

    const java = standardToJava(baseMatcher(), action);

    // The override block must be properly nested: forwardOverriddenRequest( (aligned with the
    // matcher's request() at 4 spaces) then an indented request() then further-indented .withX
    // calls — never a column-0 request() or builder calls jammed out to column ~22.
    expect(java).toContain('  .forward(\n    forwardOverriddenRequest(\n      request()\n        .withMethod("PUT")');
    expect(java).toContain('        .withPath("/v2/api")');
    expect(java).toContain('        .withHeader("Host", "upstream:9090")');
    expect(java).toContain('        .withSecure(true)');
    // The old double-indent bug pushed the override builder calls out to ~column
    // 22; no line should be that deeply indented.
    expect(java.split('\n').some((l) => /^\s{20,}\.with/.test(l))).toBe(false);
  });
});
