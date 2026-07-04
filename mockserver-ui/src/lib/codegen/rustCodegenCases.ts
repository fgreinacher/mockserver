/**
 * Combo fixtures for the typed-Rust emitter tests ({@link ./rustCodegen.test.ts}
 * and the {@link ./__fixtures__/rustGolden.ts} generator).
 *
 * Reuses the shared `combos` that drive the other language emitters (so the Rust
 * emitter is exercised over the same buildExpectationJson branches) and adds a few
 * Rust-only combos for features the shared set does not exercise: chaos profiles,
 * forward-template and forward-class-callback actions. Together they cover every
 * buildExpectationJson feature the Rust emitter must map to typed construction.
 */
import { combos as sharedCombos, type Combo } from './extractParityCases.ts';
import type { StandardMatcher } from '../standardCodegen.ts';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

const BASE_URL = 'http://localhost:1080';

const rustOnly: Combo[] = [
  {
    name: 'chaos-profile',
    matcher: baseMatcher({ path: '/flaky' }),
    action: {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' },
      chaos: {
        errorStatus: 503, errorProbability: 0.25, retryAfter: '5',
        latencyValue: 200, latencyUnit: 'MILLISECONDS', seed: 42, succeedFirst: 2, failRequestCount: 3,
      },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'forward-template',
    matcher: baseMatcher(),
    action: {
      type: 'forward_template',
      forwardTemplate: { templateType: 'MUSTACHE', template: '{ "path": "{{ request.path }}" }' },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'forward-class-callback',
    matcher: baseMatcher(),
    action: {
      type: 'forward_class_callback',
      forwardClassCallback: { callbackClass: 'com.example.ForwardCallback' },
    },
    baseUrl: BASE_URL,
  },
];

export const rustCombos: Combo[] = [...sharedCombos, ...rustOnly];
