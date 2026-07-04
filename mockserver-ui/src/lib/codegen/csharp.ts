/**
 * C# client-library emitter. Deserializes the expectation JSON into an
 * Expectation via System.Text.Json and Upserts it.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort } from './shared';

export function standardToCsharp(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = JSON.stringify(buildExpectationJson(matcher, action), null, 2);
  // C# verbatim string: escape embedded double quotes by doubling them.
  const verbatim = json.replace(/"/g, '""');
  return [
    'using System.Text.Json;',
    'using MockServer.Client;',
    'using MockServer.Client.Models;',
    '',
    `using var client = new MockServerClient("${host}", ${port});`,
    '',
    `var expectation = JsonSerializer.Deserialize<Expectation>(@"${verbatim}");`,
    'client.Upsert(expectation!);',
  ].join('\n');
}
