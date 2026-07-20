import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  standardToJava,
  standardToCurl,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '',
    method: 'POST',
    path: '/api/test',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    bodyMatcherType: 'string',
    priority: 0,
    times: 0,
    ...overrides,
  };
}

function baseAction(): StandardActionPayload {
  return {
    type: 'static',
    static: { statusCode: 200, body: '{"ok":true}', contentType: 'application/json' },
  };
}

// ---------------------------------------------------------------------------
// buildExpectationJson — JSON body shapes for each new matcher type
// ---------------------------------------------------------------------------

describe('buildExpectationJson body matcher types', () => {
  it('emits STRING body for the default string type', () => {
    const m = baseMatcher({ body: '{"foo":"bar"}' });
    const result = buildExpectationJson(m, baseAction());
    // String type emits the body as a plain string (no type wrapper)
    expect(result['httpRequest']).toHaveProperty('body', '{"foo":"bar"}');
  });

  it('emits JSON_SCHEMA body', () => {
    const schema = '{"type":"object","properties":{"name":{"type":"string"}}}';
    const m = baseMatcher({ body: schema, bodyMatcherType: 'json-schema' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'JSON_SCHEMA', jsonSchema: schema });
  });

  it('emits JSON_PATH body', () => {
    const expr = '$.store.book[0].title';
    const m = baseMatcher({ body: expr, bodyMatcherType: 'json-path' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'JSON_PATH', jsonPath: expr });
  });

  it('emits XML body', () => {
    const xml = '<root><element>value</element></root>';
    const m = baseMatcher({ body: xml, bodyMatcherType: 'xml' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'XML', xml });
  });

  it('emits XML_SCHEMA body', () => {
    const xsd = '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="root"/></xs:schema>';
    const m = baseMatcher({ body: xsd, bodyMatcherType: 'xml-schema' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'XML_SCHEMA', xmlSchema: xsd });
  });

  it('emits XPATH body', () => {
    const xpath = '/root/element[@attr="value"]';
    const m = baseMatcher({ body: xpath, bodyMatcherType: 'xpath' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'XPATH', xpath });
  });

  it('emits REGEX body', () => {
    const regex = '^Hello.*World$';
    const m = baseMatcher({ body: regex, bodyMatcherType: 'regex' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'REGEX', regex });
  });

  it('emits PARAMETERS body from key=value lines', () => {
    const m = baseMatcher({ body: 'username=admin\npassword=secret', bodyMatcherType: 'parameters' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({
      type: 'PARAMETERS',
      parameters: { username: ['admin'], password: ['secret'] },
    });
  });

  it('emits PARAMETERS with multiple values for same key', () => {
    const m = baseMatcher({ body: 'tag=a\ntag=b\ntag=c', bodyMatcherType: 'parameters' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({
      type: 'PARAMETERS',
      parameters: { tag: ['a', 'b', 'c'] },
    });
  });

  it('emits PARAMETERS with empty object when body has no valid key=value pairs', () => {
    const m = baseMatcher({ body: 'no-equals-sign', bodyMatcherType: 'parameters' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'PARAMETERS', parameters: {} });
  });

  it('trims whitespace from body values', () => {
    const m = baseMatcher({ body: '  $.store.book  ', bodyMatcherType: 'json-path' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'JSON_PATH', jsonPath: '$.store.book' });
  });

  it('does not emit body when body text is empty for new types', () => {
    const m = baseMatcher({ body: '   ', bodyMatcherType: 'json-schema' });
    const result = buildExpectationJson(m, baseAction());
    expect((result['httpRequest'] as Record<string, unknown>)['body']).toBeUndefined();
  });

  it('emits graphql body with the server-canonical "query" key', () => {
    const m = baseMatcher({
      body: '{ hero { name } }',
      bodyMatcherType: 'graphql',
      graphqlOptions: { selectionSetMatchType: 'AST_SUBSET', fields: 'hero, name' },
    });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    // The server's GraphQLBody DTO reads the query from the "query" key (not "graphql").
    expect(body).toEqual({
      type: 'GRAPHQL',
      query: '{ hero { name } }',
      selectionSetMatchType: 'AST_SUBSET',
      fields: ['hero', 'name'],
    });
  });

  it('preserves existing binary behaviour unchanged', () => {
    const m = baseMatcher({ body: 'SGVsbG8=', bodyMatcherType: 'binary', bodyBinary: true });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'BINARY', base64Bytes: 'SGVsbG8=' });
  });
});

// ---------------------------------------------------------------------------
// standardToJava — Java codegen for each new matcher type
// ---------------------------------------------------------------------------

describe('standardToJava body matcher types', () => {
  it('generates jsonSchema() for json-schema', () => {
    const m = baseMatcher({ body: '{"type":"object"}', bodyMatcherType: 'json-schema' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(jsonSchema("{\\"type\\":\\"object\\"}"))');
  });

  it('generates jsonPath() for json-path', () => {
    const m = baseMatcher({ body: '$.store.book', bodyMatcherType: 'json-path' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(jsonPath("$.store.book"))');
  });

  it('generates xml() for xml', () => {
    const m = baseMatcher({ body: '<root/>', bodyMatcherType: 'xml' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(xml("<root/>"))');
  });

  it('generates xmlSchema() for xml-schema', () => {
    const m = baseMatcher({ body: '<xs:schema/>', bodyMatcherType: 'xml-schema' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(xmlSchema("<xs:schema/>"))');
  });

  it('generates xpath() for xpath', () => {
    const m = baseMatcher({ body: '/root/element', bodyMatcherType: 'xpath' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(xpath("/root/element"))');
  });

  it('generates regex() for regex', () => {
    const m = baseMatcher({ body: '^Hello$', bodyMatcherType: 'regex' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(regex("^Hello$"))');
  });

  it('generates params() for parameters', () => {
    const m = baseMatcher({ body: 'key=val', bodyMatcherType: 'parameters' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(params(param("key", "val")))');
  });

  it('generates params() with multiple entries', () => {
    const m = baseMatcher({ body: 'a=1\nb=2', bodyMatcherType: 'parameters' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('param("a", "1")');
    expect(java).toContain('param("b", "2")');
  });
});

// ---------------------------------------------------------------------------
// standardToCurl — curl codegen includes the correct body
// ---------------------------------------------------------------------------

describe('standardToCurl body matcher types', () => {
  it('includes JSON_SCHEMA in curl payload', () => {
    const m = baseMatcher({ body: '{"type":"string"}', bodyMatcherType: 'json-schema' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"JSON_SCHEMA"');
    expect(curl).toContain('"jsonSchema"');
  });

  it('includes REGEX in curl payload', () => {
    const m = baseMatcher({ body: '^test$', bodyMatcherType: 'regex' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"REGEX"');
    expect(curl).toContain('"regex"');
  });

  it('includes PARAMETERS in curl payload', () => {
    const m = baseMatcher({ body: 'foo=bar', bodyMatcherType: 'parameters' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"PARAMETERS"');
    expect(curl).toContain('"parameters"');
  });

  it('includes XPATH in curl payload', () => {
    const m = baseMatcher({ body: '/root', bodyMatcherType: 'xpath' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"XPATH"');
    expect(curl).toContain('"xpath"');
  });

  it('includes JSON body type in curl payload', () => {
    const m = baseMatcher({ body: '{"name":"test"}', bodyMatcherType: 'json' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"JSON"');
  });

  it('includes STRING subString in curl payload', () => {
    const m = baseMatcher({ body: 'partial', bodyMatcherType: 'string', bodySubString: true });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"STRING"');
    expect(curl).toContain('"subString":true');
  });
});

// ---------------------------------------------------------------------------
// Feature: JSON body matcher type — buildExpectationJson
// ---------------------------------------------------------------------------

describe('buildExpectationJson JSON body matcher', () => {
  it('emits JSON body with parsed json value and default (omitted) matchType', () => {
    const m = baseMatcher({ body: '{"name":"test"}', bodyMatcherType: 'json' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body['type']).toBe('JSON');
    // json value is parsed when valid
    expect(body['json']).toEqual({ name: 'test' });
    // ONLY_MATCHING_FIELDS is the default — not emitted
    expect(body).not.toHaveProperty('matchType');
  });

  it('emits JSON body with STRICT matchType when set', () => {
    const m = baseMatcher({ body: '{"name":"test"}', bodyMatcherType: 'json', jsonMatchType: 'STRICT' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body['type']).toBe('JSON');
    expect(body['matchType']).toBe('STRICT');
  });

  it('emits JSON body as string when JSON is invalid', () => {
    const m = baseMatcher({ body: 'not-json{', bodyMatcherType: 'json' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body['type']).toBe('JSON');
    expect(body['json']).toBe('not-json{');
  });
});

// ---------------------------------------------------------------------------
// Feature: STRING body subString — buildExpectationJson
// ---------------------------------------------------------------------------

describe('buildExpectationJson STRING body subString', () => {
  it('emits STRING body with subString=true when toggle is on', () => {
    const m = baseMatcher({ body: 'partial text', bodyMatcherType: 'string', bodySubString: true });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'STRING', string: 'partial text', subString: true });
  });

  it('emits plain string body when subString is false', () => {
    const m = baseMatcher({ body: 'exact text', bodyMatcherType: 'string', bodySubString: false });
    const result = buildExpectationJson(m, baseAction());
    expect((result['httpRequest'] as Record<string, unknown>)['body']).toBe('exact text');
  });
});

// ---------------------------------------------------------------------------
// Feature: JSON body matcher — Java codegen
// ---------------------------------------------------------------------------

describe('standardToJava JSON body matcher', () => {
  it('generates json() for default match type', () => {
    const m = baseMatcher({ body: '{"a":1}', bodyMatcherType: 'json' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(json("{\\"a\\":1}"))');
    expect(java).toContain('import static org.mockserver.model.JsonBody.json;');
    expect(java).not.toContain('MatchType');
  });

  it('generates json() with MatchType.STRICT for strict', () => {
    const m = baseMatcher({ body: '{"a":1}', bodyMatcherType: 'json', jsonMatchType: 'STRICT' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(json("{\\"a\\":1}", MatchType.STRICT))');
    expect(java).toContain('import org.mockserver.matchers.MatchType;');
  });
});

// ---------------------------------------------------------------------------
// Feature: STRING body subString — Java codegen
// ---------------------------------------------------------------------------

describe('standardToJava STRING body subString', () => {
  it('generates subString() when toggle is on', () => {
    const m = baseMatcher({ body: 'partial', bodyMatcherType: 'string', bodySubString: true });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(subString("partial"))');
    expect(java).toContain('import static org.mockserver.model.StringBody.subString;');
  });

  it('generates plain .withBody() when toggle is off', () => {
    const m = baseMatcher({ body: 'exact', bodyMatcherType: 'string', bodySubString: false });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody("exact")');
    expect(java).not.toContain('subString');
  });
});

// ---------------------------------------------------------------------------
// Feature: Static response delay — buildExpectationJson + Java
// ---------------------------------------------------------------------------

describe('static response delay', () => {
  it('emits delay in httpResponse JSON when > 0', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', delayValue: 500, delayUnit: 'MILLISECONDS' },
    };
    const json = buildExpectationJson(baseMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['delay']).toEqual({ timeUnit: 'MILLISECONDS', value: 500 });
  });

  it('omits delay when 0', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', delayValue: 0, delayUnit: 'SECONDS' },
    };
    const json = buildExpectationJson(baseMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp).not.toHaveProperty('delay');
  });

  it('emits .withDelay(TimeUnit, value) in Java', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', delayValue: 3, delayUnit: 'SECONDS' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withDelay(TimeUnit.SECONDS, 3)');
    expect(java).toContain('import java.util.concurrent.TimeUnit;');
  });
});

// ---------------------------------------------------------------------------
// Feature: Static response reasonPhrase — buildExpectationJson + Java
// ---------------------------------------------------------------------------

describe('static response reasonPhrase', () => {
  it('emits reasonPhrase in httpResponse JSON when non-empty', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 404, body: '', contentType: '', reasonPhrase: 'Not Found' },
    };
    const json = buildExpectationJson(baseMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['reasonPhrase']).toBe('Not Found');
  });

  it('omits reasonPhrase when empty', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', reasonPhrase: '' },
    };
    const json = buildExpectationJson(baseMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp).not.toHaveProperty('reasonPhrase');
  });

  it('emits .withReasonPhrase() in Java', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 404, body: '', contentType: '', reasonPhrase: 'Not Found' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withReasonPhrase("Not Found")');
  });
});

// ---------------------------------------------------------------------------
// Feature: Static response cookies — buildExpectationJson + Java
// ---------------------------------------------------------------------------

describe('static response cookies', () => {
  it('emits cookies in httpResponse JSON as name-value map', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', cookies: 'session=abc123\ntheme=dark' },
    };
    const json = buildExpectationJson(baseMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['cookies']).toEqual({ session: 'abc123', theme: 'dark' });
  });

  it('omits cookies when empty', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', cookies: '' },
    };
    const json = buildExpectationJson(baseMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp).not.toHaveProperty('cookies');
  });

  it('emits .withCookie() per cookie in Java', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', cookies: 'session=abc123\ntheme=dark' },
    };
    const java = standardToJava(baseMatcher(), action);
    expect(java).toContain('.withCookie("session", "abc123")');
    expect(java).toContain('.withCookie("theme", "dark")');
  });
});
