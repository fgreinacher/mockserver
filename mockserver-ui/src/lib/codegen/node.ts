/**
 * Node client-library emitter. The Node client is JSON-native (mockAnyResponse
 * takes the raw object), so it represents EVERY expectation field faithfully
 * regardless of the installed client version.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort, indentAfterFirst } from './shared';

export function standardToNode(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = JSON.stringify(buildExpectationJson(matcher, action), null, 2);
  return [
    "const { mockServerClient } = require('mockserver-client');",
    '',
    `mockServerClient("${host}", ${port})`,
    `  .mockAnyResponse(${indentAfterFirst(json, 2)})`,
    '  .then(',
    '    () => console.log("expectation created"),',
    '    (error) => console.error(error)',
    '  );',
  ].join('\n');
}
