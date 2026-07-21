import { describe, it, expect, afterEach } from 'vitest';
import {
  filterFieldNames,
  filterFields,
  getFilterField,
  registerFilterField,
  resetFilterFields,
  parseSearchTerm,
  describeUnsupportedOperators,
  matchesFieldOperator,
  matchesItemSearch,
  matchesLogSearch,
  hostFromHeaders,
  decodedRequestBody,
} from '../lib/filterDSL';

const BUILT_INS = ['status', 'method', 'path', 'host', 'operation'];

// The registry is module-level mutable state. Reset after every test so a test
// that registers a field cannot leak into another — which would otherwise only
// be masked by the runner's per-file isolation.
afterEach(() => resetFilterFields());

describe('field registry', () => {
  it('registers every built-in field', () => {
    for (const name of BUILT_INS) expect(filterFieldNames()).toContain(name);
  });

  it('keeps the built-ins in registration order (drives placeholder/help order)', () => {
    expect(filterFieldNames().filter((n) => BUILT_INS.includes(n))).toEqual(BUILT_INS);
  });

  it('declares match semantics and help metadata for every field', () => {
    for (const field of filterFields()) {
      expect(field.name).toBe(field.name.toLowerCase());
      expect(typeof field.resolve).toBe('function');
      expect(field.example.startsWith(`${field.name}:`)).toBe(true);
      expect(field.description.length).toBeGreaterThan(0);
    }
    expect(getFilterField('status')?.numeric).toBe(true);
    expect(getFilterField('method')?.numeric).toBeUndefined();
    expect(getFilterField('path')?.glob).toBe(true);
    expect(getFilterField('host')?.glob).toBe(true);
    expect(getFilterField('operation')?.glob).toBe(true);
  });

  it('looks fields up case-insensitively', () => {
    expect(getFilterField('STATUS')?.name).toBe('status');
    expect(getFilterField('nope')).toBeUndefined();
  });

  it('lets a later feature swap in a richer resolver without engine changes', () => {
    const original = getFilterField('operation')!;
    const item = { httpRequest: { path: '/graphql' } };
    // The built-in resolver finds nothing (no operationName in the body).
    expect(matchesItemSearch(item, 'operation:GetUser')).toBe(false);

    registerFilterField({ ...original, resolve: () => ({ text: 'GetUser' }) });
    expect(matchesItemSearch(item, 'operation:GetUser')).toBe(true);
    // Registration is by name, so the field is replaced rather than duplicated.
    expect(filterFieldNames().filter((n) => n === 'operation')).toHaveLength(1);
  });

  it('registers a brand-new field additively', () => {
    registerFilterField({
      name: 'tenant',
      example: 'tenant:acme',
      description: 'test-only field',
      resolve: () => ({ text: 'acme' }),
    });
    expect(filterFieldNames()).toContain('tenant');
    expect(matchesItemSearch({}, 'tenant:acme')).toBe(true);
  });

  it('resetFilterFields() drops later registrations and restores the built-ins', () => {
    registerFilterField({ name: 'tenant', example: 'tenant:acme', description: 'x', resolve: () => ({ text: 'acme' }) });
    registerFilterField({ ...getFilterField('method')!, resolve: () => ({ text: 'ALWAYS' }) });

    resetFilterFields();

    expect(filterFieldNames()).not.toContain('tenant');
    // `tenant:acme` is no longer a registered field, so it is free text again.
    expect(parseSearchTerm('tenant:acme').operators).toHaveLength(0);
    // The built-in method resolver is back.
    expect(matchesItemSearch({ httpRequest: { method: 'POST' } }, 'method:POST')).toBe(true);
    expect(matchesItemSearch({ httpRequest: { method: 'POST' } }, 'method:ALWAYS')).toBe(false);
  });
});

describe('request body decoding is memoised on the request reference', () => {
  it('parses a JSON body once per stable request object', () => {
    const httpRequest = { path: '/graphql', body: { type: 'JSON', json: JSON.stringify({ operationName: 'GetUser' }) } };
    const item = { httpRequest };
    const first = decodedRequestBody(item);
    // Same reference → same decoded object identity (no re-parse).
    expect(decodedRequestBody(item)).toBe(first);
    expect(decodedRequestBody({ httpRequest })).toBe(first);
    expect((first as Record<string, unknown>)['operationName']).toBe('GetUser');
  });

  it('caches an undecodable body as a hit rather than re-parsing it', () => {
    const httpRequest = { path: '/x', body: 'not json at all' };
    expect(decodedRequestBody({ httpRequest })).toBeUndefined();
    expect(decodedRequestBody({ httpRequest })).toBeUndefined();
  });

  it('decodes a new request reference independently', () => {
    const before = { httpRequest: { path: '/graphql', body: { type: 'JSON', json: { operationName: 'GetUser' } } } };
    const after = { httpRequest: { path: '/graphql', body: { type: 'JSON', json: { operationName: 'CreateOrder' } } } };
    expect(matchesItemSearch(before, 'operation:GetUser')).toBe(true);
    expect(matchesItemSearch(after, 'operation:CreateOrder')).toBe(true);
    expect(matchesItemSearch(after, 'operation:GetUser')).toBe(false);
  });

  it('returns undefined when there is no request at all', () => {
    expect(decodedRequestBody({})).toBeUndefined();
  });
});

describe('host: operator', () => {
  const arrayHeaders = (name: string, value: string) => ({
    httpRequest: { path: '/x', headers: [{ name, values: [value] }] },
  });

  it('reads the Host header from the array header shape', () => {
    expect(matchesItemSearch(arrayHeaders('Host', 'api.example.com'), 'host:api.example.com')).toBe(true);
    expect(matchesItemSearch(arrayHeaders('Host', 'api.example.com'), 'host:other.example.com')).toBe(false);
  });

  it('reads the Host header from the map header shape', () => {
    const item = { httpRequest: { path: '/x', headers: { host: ['api.example.com'] } } };
    expect(matchesItemSearch(item, 'host:api.example.com')).toBe(true);
  });

  it('matches the header name case-insensitively (host / Host / HOST)', () => {
    for (const name of ['host', 'Host', 'HOST']) {
      expect(matchesItemSearch(arrayHeaders(name, 'api.example.com'), 'host:api.example.com')).toBe(true);
    }
  });

  it('compares the value case-insensitively', () => {
    expect(matchesItemSearch(arrayHeaders('Host', 'API.Example.COM'), 'host:api.example.com')).toBe(true);
  });

  it('supports globs like path:', () => {
    const item = arrayHeaders('Host', 'api.example.com');
    expect(matchesItemSearch(item, 'host:*.example.com')).toBe(true);
    expect(matchesItemSearch(item, 'host:api.*')).toBe(true);
    expect(matchesItemSearch(item, 'host:*.other.com')).toBe(false);
  });

  it('keeps a port in the compared value (Host carries it verbatim)', () => {
    const item = arrayHeaders('Host', 'localhost:1080');
    expect(matchesItemSearch(item, 'host:localhost:1080')).toBe(true);
    expect(matchesItemSearch(item, 'host:localhost')).toBe(false);
  });

  it('never matches when the Host header is missing or empty', () => {
    expect(matchesItemSearch({ httpRequest: { path: '/x' } }, 'host:api.example.com')).toBe(false);
    expect(matchesItemSearch({ httpRequest: { path: '/x', headers: [] } }, 'host:*')).toBe(false);
    expect(matchesItemSearch({ httpRequest: { path: '/x' } }, 'host:*')).toBe(false);
    expect(matchesItemSearch({}, 'host:api.example.com')).toBe(false);
    expect(matchesItemSearch(arrayHeaders('Host', ''), 'host:*')).toBe(false);
  });

  it('exposes the same extraction the Traffic host column uses', () => {
    expect(hostFromHeaders([{ name: 'Host', values: ['a.example.com'] }])).toBe('a.example.com');
    expect(hostFromHeaders({ Host: 'b.example.com' })).toBe('b.example.com');
    expect(hostFromHeaders([{ name: 'Accept', values: ['*/*'] }])).toBeUndefined();
    expect(hostFromHeaders(undefined)).toBeUndefined();
  });
});

describe('operation: operator', () => {
  const graphql = (body: unknown) => ({ httpRequest: { method: 'POST', path: '/graphql', body } });

  it('resolves operationName from a JSON-envelope body', () => {
    const item = graphql({ type: 'JSON', json: { query: 'query GetUser { user { id } }', operationName: 'GetUser' } });
    expect(matchesItemSearch(item, 'operation:GetUser')).toBe(true);
    expect(matchesItemSearch(item, 'operation:getuser')).toBe(true);
    expect(matchesItemSearch(item, 'operation:CreateOrder')).toBe(false);
  });

  it('resolves operationName from a string-envelope or raw string body', () => {
    const payload = JSON.stringify({ query: '{ user { id } }', operationName: 'GetUser' });
    expect(matchesItemSearch(graphql({ type: 'STRING', string: payload }), 'operation:GetUser')).toBe(true);
    expect(matchesItemSearch(graphql(payload), 'operation:GetUser')).toBe(true);
  });

  it('supports globs', () => {
    const item = graphql({ type: 'JSON', json: { operationName: 'GetUserOrders' } });
    expect(matchesItemSearch(item, 'operation:Get*')).toBe(true);
    expect(matchesItemSearch(item, 'operation:Create*')).toBe(false);
  });

  it('never matches when no operation name is available (rather than guessing)', () => {
    // Anonymous operation, name only inside the query document, unparseable body,
    // and no body at all all resolve to "absent".
    expect(matchesItemSearch(graphql({ type: 'JSON', json: { query: 'query GetUser { id }' } }), 'operation:GetUser')).toBe(false);
    expect(matchesItemSearch(graphql({ type: 'STRING', string: 'query GetUser { id }' }), 'operation:GetUser')).toBe(false);
    expect(matchesItemSearch(graphql({ type: 'JSON', json: { operationName: '' } }), 'operation:*')).toBe(false);
    expect(matchesItemSearch(graphql(undefined), 'operation:GetUser')).toBe(false);
    expect(matchesItemSearch({}, 'operation:GetUser')).toBe(false);
  });

  it('does not throw on a body that is not an object', () => {
    expect(matchesItemSearch(graphql(42), 'operation:GetUser')).toBe(false);
    expect(matchesItemSearch(graphql([1, 2]), 'operation:GetUser')).toBe(false);
  });
});

describe('comparators are gated on the field declaring itself numeric', () => {
  const item = { httpRequest: { method: 'POST', path: '/api' }, httpResponse: { statusCode: 503 } };

  it('applies numeric comparators only to numeric fields', () => {
    expect(matchesItemSearch(item, 'status:>=500')).toBe(true);
    // method/path/host/operation are not numeric — a comparator cannot match.
    expect(matchesItemSearch(item, 'method:>=1')).toBe(false);
    expect(matchesItemSearch(item, 'path:>/a')).toBe(false);
  });

  it('treats `=` as plain equality on any field', () => {
    expect(matchesItemSearch(item, 'method:=POST')).toBe(true);
    expect(matchesItemSearch(item, 'status:=503')).toBe(true);
  });

  it('does not glob a field that is not glob-capable', () => {
    // `status` has no glob semantics, so `*` is compared literally and fails.
    expect(matchesItemSearch(item, 'status:5*')).toBe(false);
  });
});

describe('unsupported operators for a restricted call site', () => {
  const options = { fields: ['method', 'path'] as const };

  it('parses a known-but-unsupported operator as a flagged operator, not free text', () => {
    const parsed = parseSearchTerm('status:>=400 method:POST', options);
    expect(parsed.operators).toEqual([
      { field: 'status', comparator: '>=', expr: '400', unsupported: true },
      { field: 'method', expr: 'POST' },
    ]);
    expect(parsed.unsupportedFields).toEqual(['status']);
    expect(parsed.text).toBe('');
  });

  it('reports no unsupported fields when the call site declares no restriction', () => {
    const parsed = parseSearchTerm('status:>=400 host:api.example.com');
    expect(parsed.unsupportedFields).toEqual([]);
    expect(parsed.operators.every((op) => op.unsupported === undefined)).toBe(true);
  });

  it('makes an unsupported operator visibly match nothing rather than being ignored', () => {
    const item = { httpRequest: { method: 'POST', path: '/api' }, httpResponse: { statusCode: 503 } };
    // Supported operators still work under the restriction.
    expect(matchesItemSearch(item, 'method:POST', options)).toBe(true);
    // The same term that matches unrestricted matches nothing when unsupported.
    expect(matchesItemSearch(item, 'status:503')).toBe(true);
    expect(matchesItemSearch(item, 'status:503', options)).toBe(false);
    // ...and it is not silently downgraded to free text either.
    expect(matchesItemSearch(item, 'status:503 api', options)).toBe(false);
  });

  it('explains the restriction to the user', () => {
    const message = describeUnsupportedOperators(parseSearchTerm('status:503', options), options);
    expect(message).toContain('status:');
    expect(message).toContain('not supported here');
    expect(message).toContain('Supported here: method:, path:');
  });

  it('returns no message for a fully supported term', () => {
    expect(describeUnsupportedOperators(parseSearchTerm('method:POST free text', options), options)).toBeNull();
    expect(describeUnsupportedOperators(parseSearchTerm('status:503'))).toBeNull();
  });

  it('says so plainly when a surface supports no operators at all', () => {
    const noFields = { fields: [] as const };
    const message = describeUnsupportedOperators(parseSearchTerm('status:503', noFields), noFields);
    expect(message).toContain('No field operators are supported here');
  });

  it('never advertises a declared field that is not registered', () => {
    // A typo'd subset must not offer the user an operator that does not exist.
    const typo = { fields: ['staus', 'method'] };
    const message = describeUnsupportedOperators(parseSearchTerm('status:503', typo), typo);
    expect(message).toContain('Supported here: method:.');
    expect(message).not.toContain('staus');

    const allTypos = { fields: ['staus'] };
    expect(describeUnsupportedOperators(parseSearchTerm('status:503', allTypos), allTypos))
      .toContain('No field operators are supported here');
  });

  it('rejects an unknown-field operator object defensively', () => {
    expect(matchesFieldOperator({}, { field: 'nosuchfield', expr: 'x' })).toBe(false);
    expect(matchesFieldOperator(
      { httpResponse: { statusCode: 503 } },
      { field: 'status', expr: '503', unsupported: true },
    )).toBe(false);
  });
});

describe('log rows support no field operators', () => {
  it('matches nothing for an operator-only term (unchanged)', () => {
    expect(matchesLogSearch({ key: 'k', value: { message: 'anything' } }, 'status:>=400')).toBe(false);
  });

  it('matches nothing when an operator is combined with free text', () => {
    // The operator can never be satisfied by a log row, so under AND semantics
    // the whole term matches nothing — it is not silently dropped in favour of
    // the free-text portion.
    const message = { key: 'k', value: { message: 'anything' } };
    expect(matchesLogSearch(message, 'anything')).toBe(true);
    expect(matchesLogSearch(message, 'status:>=400 anything')).toBe(false);
    expect(matchesLogSearch(message, 'host:api.example.com anything')).toBe(false);
  });

  it('still treats an unregistered field-like token as free text', () => {
    expect(matchesLogSearch({ key: 'k', value: { message: 'see http://example.com/x' } }, 'http://example.com/x')).toBe(true);
  });
});
