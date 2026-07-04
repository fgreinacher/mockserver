/**
 * Ruby client-library emitter. Passes the expectation JSON through JSON.parse via
 * a non-interpolating squiggly heredoc and registers it with
 * Expectation.from_hash.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort } from './shared';

export function standardToRuby(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = JSON.stringify(buildExpectationJson(matcher, action), null, 2);
  // Pass the JSON through JSON.parse via a non-interpolating squiggly heredoc (<<~'JSON'),
  // which sidesteps all Ruby string-literal escaping. Indent the body so <<~ dedents it.
  const heredoc = json.split('\n').map((l) => '  ' + l).join('\n');
  return [
    "require 'json'",
    "require 'mockserver-client'",
    '',
    `client = MockServer::Client.new('${host}', ${port})`,
    '',
    "expectation = <<~'JSON'",
    heredoc,
    'JSON',
    '',
    'client.upsert(MockServer::Expectation.from_hash(JSON.parse(expectation)))',
  ].join('\n');
}
