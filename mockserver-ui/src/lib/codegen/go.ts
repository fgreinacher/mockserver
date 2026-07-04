/**
 * Go client-library emitter. Unmarshals the expectation JSON into a
 * mockserver.Expectation and Upserts it.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort } from './shared';

export function standardToGo(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = JSON.stringify(buildExpectationJson(matcher, action), null, 2);
  // A Go raw string literal cannot contain a backtick; if the JSON has one (e.g. a path/body
  // value), break out of the raw string and concatenate a quoted backtick — the standard idiom.
  const goRaw = json.replace(/`/g, '` + "`" + `');
  return [
    'package main',
    '',
    'import (',
    '\t"encoding/json"',
    '',
    '\tmockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7"',
    ')',
    '',
    'func main() {',
    `\tclient := mockserver.New("${host}", ${port})`,
    '',
    '\texpectationJSON := `' + goRaw + '`',
    '\tvar expectation mockserver.Expectation',
    '\tif err := json.Unmarshal([]byte(expectationJSON), &expectation); err != nil {',
    '\t\tpanic(err)',
    '\t}',
    '\tif _, err := client.Upsert(expectation); err != nil {',
    '\t\tpanic(err)',
    '\t}',
    '}',
  ].join('\n');
}
