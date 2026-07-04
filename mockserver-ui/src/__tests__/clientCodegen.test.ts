/**
 * Tests for the Node / Python / Go / C# client-library code generators.
 *
 * All four hydrate the same expectation JSON the JSON tab shows, via each
 * client's native facility, then register through the native client. The tests
 * assert the client wrapper and that the full payload (including templateFile /
 * FILE-body fields) round-trips into the generated snippet.
 */
import { describe, it, expect } from 'vitest';
import {
  standardToNode,
  standardToPython,
  standardToGo,
  standardToCsharp,
  standardToRuby,
  standardToRust,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

const BASE_URL = 'http://localhost:1080';

const templateFileAction: StandardActionPayload = {
  type: 'forward_template',
  forwardTemplate: { templateType: 'VELOCITY', template: '', templateFile: 'templates/foo.vm' },
};

const fileBodyAction: StandardActionPayload = {
  type: 'static',
  static: {
    statusCode: 200, body: '', contentType: 'application/json',
    bodyFromFile: true, filePath: 'responses/order.json', fileTemplateType: 'MUSTACHE',
  },
};

describe('standardToNode', () => {
  it('wraps the expectation JSON in mockAnyResponse with host/port', () => {
    const code = standardToNode(baseMatcher(), templateFileAction, BASE_URL);
    expect(code).toContain("require('mockserver-client')");
    expect(code).toContain('mockServerClient("localhost", 1080)');
    expect(code).toContain('.mockAnyResponse(');
    // full fidelity — the templateFile survives into the generated object
    expect(code).toContain('"httpForwardTemplate"');
    expect(code).toContain('"templateFile": "templates/foo.vm"');
  });

  it('carries a templated FILE body through', () => {
    const code = standardToNode(baseMatcher(), fileBodyAction, BASE_URL);
    expect(code).toContain('"type": "FILE"');
    expect(code).toContain('"templateType": "MUSTACHE"');
  });
});

describe('standardToPython', () => {
  it('uses Expectation.from_dict with a Python dict literal', () => {
    const code = standardToPython(baseMatcher(), templateFileAction, BASE_URL);
    expect(code).toContain('from mockserver import MockServerClient, Expectation');
    expect(code).toContain('MockServerClient("localhost", 1080).upsert(');
    expect(code).toContain('Expectation.from_dict(');
    expect(code).toContain('"templateFile": "templates/foo.vm"');
  });

  it('renders JSON booleans/null as Python True/False/None', () => {
    const code = standardToPython(baseMatcher({ secure: true }), {
      type: 'static',
      static: { statusCode: 200, body: '{"ok":true}', contentType: 'application/json', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('"secure": True');
    expect(code).not.toContain(': true');
  });
});

describe('standardToGo', () => {
  it('unmarshals the JSON into a mockserver.Expectation and Upserts it', () => {
    const code = standardToGo(baseMatcher(), templateFileAction, BASE_URL);
    expect(code).toContain('mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7"');
    expect(code).toContain('mockserver.New("localhost", 1080)');
    expect(code).toContain('json.Unmarshal([]byte(expectationJSON), &expectation)');
    expect(code).toContain('client.Upsert(expectation)');
    expect(code).toContain('"templateFile": "templates/foo.vm"');
  });

  it('escapes a backtick in the JSON so the Go raw string stays valid', () => {
    const code = standardToGo(baseMatcher({ path: '/a`b' }), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    // the literal backtick is broken out and concatenated as a quoted backtick
    expect(code).toContain('` + "`" + `');
    // no bare backtick remains inside the path value
    expect(code).not.toContain('/a`b');
  });
});

describe('standardToRuby', () => {
  it('builds a typed Expectation from model constructors and upserts it', () => {
    const code = standardToRuby(baseMatcher(), templateFileAction, BASE_URL);
    expect(code).toContain("require 'mockserver-client'");
    expect(code).toContain("MockServer::Client.new('localhost', 1080)");
    expect(code).toContain('client.upsert(');
    expect(code).toContain('MockServer::Expectation.new(');
    // typed forward-template action, not an embedded JSON payload
    expect(code).toContain('MockServer::HttpTemplate.new(');
    expect(code).toContain('template_file: "templates/foo.vm"');
    // no whole-payload from_hash / heredoc round-trip anymore
    expect(code).not.toContain('from_hash');
    expect(code).not.toContain("<<~'JSON'");
  });

  it('carries a templated FILE body through as a typed Body', () => {
    const code = standardToRuby(baseMatcher(), fileBodyAction, BASE_URL);
    expect(code).toContain('MockServer::Body.new(');
    expect(code).toContain('type: "FILE"');
    expect(code).toContain('template_type: "MUSTACHE"');
  });
});

describe('standardToRust', () => {
  it('deserializes the JSON into an Expectation and upserts it', () => {
    const code = standardToRust(baseMatcher(), fileBodyAction, BASE_URL);
    expect(code).toContain('use mockserver_client::{ClientBuilder, Expectation};');
    expect(code).toContain('ClientBuilder::new("localhost", 1080).build()?');
    expect(code).toContain('serde_json::from_str(r#"');
    expect(code).toContain('client.upsert(&[expectation])?');
    expect(code).toContain('"templateType": "MUSTACHE"');
  });

  it('bumps the raw-string hash count when the JSON contains a quote-hash sequence', () => {
    // the body value contains `"#`, which would terminate an r#"..."# raw string early,
    // so the wrapper must escalate to r##"..."##
    const code = standardToRust(baseMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'a"#b', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('serde_json::from_str(r##"');
    expect(code).not.toContain('serde_json::from_str(r#"');
  });
});

describe('clientHostPort (via generated snippets)', () => {
  it('defaults the port to 443 for https and 1080 on parse failure', () => {
    const https = standardToNode(baseMatcher(), { type: 'static', static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' } }, 'https://mock.example.com');
    expect(https).toContain('mockServerClient("mock.example.com", 443)');
    const garbage = standardToNode(baseMatcher(), { type: 'static', static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' } }, 'not a url');
    expect(garbage).toContain('mockServerClient("localhost", 1080)');
  });
});

describe('standardToCsharp', () => {
  it('deserializes the JSON into an Expectation and Upserts it', () => {
    const code = standardToCsharp(baseMatcher(), fileBodyAction, BASE_URL);
    expect(code).toContain('using MockServer.Client;');
    expect(code).toContain('new MockServerClient("localhost", 1080)');
    expect(code).toContain('JsonSerializer.Deserialize<Expectation>(@"');
    expect(code).toContain('client.Upsert(expectation!);');
    // verbatim string escapes double quotes by doubling them
    expect(code).toContain('""type"": ""FILE""');
  });
});
