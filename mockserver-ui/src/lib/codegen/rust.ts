/**
 * Rust client-library emitter. Deserializes the expectation JSON (wrapped in a
 * raw string literal) into an Expectation via serde_json and upserts it.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort, rustRawString } from './shared';

export function standardToRust(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = JSON.stringify(buildExpectationJson(matcher, action), null, 2);
  return [
    '// Cargo.toml: mockserver-client = "7" and serde_json = "1"',
    'use mockserver_client::{ClientBuilder, Expectation};',
    '',
    'fn main() -> mockserver_client::Result<()> {',
    `    let client = ClientBuilder::new("${host}", ${port}).build()?;`,
    '',
    `    let expectation: Expectation = serde_json::from_str(${rustRawString(json)})?;`,
    '    client.upsert(&[expectation])?;',
    '    Ok(())',
    '}',
  ].join('\n');
}
