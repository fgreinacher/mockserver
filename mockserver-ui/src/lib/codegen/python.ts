/**
 * Python client-library emitter. Hydrates the expectation JSON into a Python
 * dict literal and registers it via Expectation.from_dict — a field the installed
 * client's model does not declare is dropped on hydration; the JSON tab remains
 * the authoritative, lossless source.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort, indentAfterFirst, toPythonLiteral } from './shared';

export function standardToPython(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const dict = toPythonLiteral(buildExpectationJson(matcher, action), 0);
  return [
    'from mockserver import MockServerClient, Expectation',
    '',
    `MockServerClient("${host}", ${port}).upsert(`,
    `    Expectation.from_dict(${indentAfterFirst(dict, 4)})`,
    ')',
  ].join('\n');
}
