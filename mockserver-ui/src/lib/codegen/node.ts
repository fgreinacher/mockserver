/**
 * Node client-library emitter. Emits the website's typed idiom: a
 * `mockServerClient(host, port).mockAnyResponse({ ...object literal... })` call.
 * The Node client is JSON-native (mockAnyResponse takes the raw object), so the
 * literal represents EVERY expectation field faithfully regardless of the
 * installed client version.
 *
 * The emitted object literal is the client's typed entry point: it is a valid
 * `Expectation` (from mockserver-client's mockServer.d.ts). node.test.ts proves
 * this by typechecking every generated literal against that `Expectation` type
 * (with a bogus-key negative control), so a client-type change that the literal
 * violated would fail the build rather than ship broken generated code.
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
