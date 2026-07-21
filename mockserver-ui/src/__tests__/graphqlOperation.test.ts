import { describe, it, expect, afterEach } from 'vitest';
import {
  parseGraphqlBody,
  graphqlOperationOfRequest,
  graphqlOperationOf,
  graphqlOperationLabel,
  looksLikeGraphqlDocument,
  registerGraphqlOperationField,
} from '../lib/graphqlOperation';
import { matchesItemSearch, resetFilterFields, getFilterField, filterFields } from '../lib/filterDSL';

/** A MockServer-shaped captured request carrying `body`. */
function captured(body: unknown, headers?: unknown) {
  return { httpRequest: { method: 'POST', path: '/graphql', ...(headers ? { headers } : {}), body } };
}

/** The GraphQL-over-HTTP POST payload, as MockServer stores a JSON body. */
function jsonBody(payload: unknown) {
  return { type: 'JSON', json: JSON.stringify(payload) };
}

// ---------------------------------------------------------------------------
// Extraction — the two places an operation name can live
// ---------------------------------------------------------------------------

describe('parseGraphqlBody — operation extraction', () => {
  it('reads the name from the query document when there is no operationName member', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'query GetUser { user { id name } }' })))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
  });

  it('reads the name from the operationName member', () => {
    expect(parseGraphqlBody(jsonBody({
      query: 'query GetUser { user { id } }',
      operationName: 'GetUser',
      variables: { id: 1 },
    }))).toEqual({ operationName: 'GetUser', operationType: 'query' });
  });

  it('lets operationName select one operation out of a multi-operation document', () => {
    const query = `
      query GetUser { user { id } }
      mutation CreateOrder { createOrder { id } }
    `;
    expect(parseGraphqlBody(jsonBody({ query, operationName: 'CreateOrder' })))
      .toEqual({ operationName: 'CreateOrder', operationType: 'mutation' });
    // Without a selector, the first definition wins.
    expect(parseGraphqlBody(jsonBody({ query })))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
  });

  it('recognises mutations and subscriptions', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'mutation CreateOrder($in: OrderInput!) { createOrder(input: $in) { id } }' })))
      .toEqual({ operationName: 'CreateOrder', operationType: 'mutation' });
    expect(parseGraphqlBody(jsonBody({ query: 'subscription OnOrder { orderPlaced { id } }' })))
      .toEqual({ operationName: 'OnOrder', operationType: 'subscription' });
  });

  it('handles anonymous operations and the shorthand form', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'query { user { id } }' })))
      .toEqual({ operationName: null, operationType: 'query' });
    expect(parseGraphqlBody(jsonBody({ query: '{ user { id } }' })))
      .toEqual({ operationName: null, operationType: 'query' });
    expect(parseGraphqlBody(jsonBody({ query: 'mutation { ping }' })))
      .toEqual({ operationName: null, operationType: 'mutation' });
  });

  it('skips leading fragment definitions and comments', () => {
    const query = `
      # fetch the current user
      fragment UserFields on User { id name }
      query GetUser { user { ...UserFields } }
    `;
    expect(parseGraphqlBody(jsonBody({ query })))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
  });

  it('does not mistake a field called query for an operation keyword', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'mutation Refresh { query { id } }' })))
      .toEqual({ operationName: 'Refresh', operationType: 'mutation' });
  });

  it('is not desynchronised by braces or hashes inside string literals', () => {
    const query = 'query Search { results(term: "a { b # c") { id } }';
    expect(parseGraphqlBody(jsonBody({ query })))
      .toEqual({ operationName: 'Search', operationType: 'query' });
  });

  it('accepts the raw application/graphql document form', () => {
    expect(parseGraphqlBody({ type: 'STRING', string: 'query GetUser { user { id } }' }))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
    expect(parseGraphqlBody('mutation CreateOrder { createOrder { id } }'))
      .toEqual({ operationName: 'CreateOrder', operationType: 'mutation' });
  });

  it('accepts an already-parsed body object', () => {
    expect(parseGraphqlBody({ query: 'query GetUser { user { id } }' }))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
  });
});

// ---------------------------------------------------------------------------
// False positives — the risk the plan calls out explicitly
// ---------------------------------------------------------------------------

describe('parseGraphqlBody — non-GraphQL bodies are not GraphQL', () => {
  it('rejects ordinary JSON that merely has a query key', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'SELECT * FROM users WHERE id = 1' }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: 'widgets', page: 2 }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: '' }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: '*' }))).toBeNull();
  });

  it('rejects a JSON body whose query is an object (Elasticsearch-style DSL)', () => {
    expect(parseGraphqlBody(jsonBody({ query: { match: { title: 'graphql' } } }))).toBeNull();
  });

  it('rejects a query string that is itself JSON, not a selection set', () => {
    expect(parseGraphqlBody(jsonBody({ query: '{"filter":"x"}' }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: '{}' }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: '{   }' }))).toBeNull();
  });

  it('rejects a JSON body with an operationName but no GraphQL document', () => {
    // The built-in resolver matched this; a GraphQL-aware parser must not.
    expect(parseGraphqlBody(jsonBody({ operationName: 'GetUser', foo: 'bar' }))).toBeNull();
  });

  it('rejects bodies with no query at all', () => {
    expect(parseGraphqlBody(jsonBody({ id: 1, name: 'x' }))).toBeNull();
    expect(parseGraphqlBody(undefined)).toBeNull();
    expect(parseGraphqlBody(null)).toBeNull();
    expect(parseGraphqlBody('')).toBeNull();
    expect(parseGraphqlBody('plain text log line')).toBeNull();
  });

  it('rejects the word query without a selection set', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'query' }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: 'query GetUser' }))).toBeNull();
  });

  it('rejects a body whose Content-Type cannot carry GraphQL', () => {
    const graphql = jsonBody({ query: 'query GetUser { user { id } }' });
    expect(graphqlOperationOfRequest(
      captured(graphql, [{ name: 'content-type', values: ['multipart/form-data; boundary=x'] }]).httpRequest,
    )).toBeNull();
    expect(graphqlOperationOfRequest(
      captured(graphql, [{ name: 'Content-Type', values: ['application/json'] }]).httpRequest,
    )).toEqual({ operationName: 'GetUser', operationType: 'query' });
    expect(graphqlOperationOfRequest(
      captured(graphql, { 'content-type': ['application/graphql+json'] }).httpRequest,
    )).toEqual({ operationName: 'GetUser', operationType: 'query' });
    // No Content-Type at all still falls back to the body checks.
    expect(graphqlOperationOfRequest(captured(graphql).httpRequest))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
  });
});

// ---------------------------------------------------------------------------
// Degradation — malformed, huge, binary and streamed bodies
// ---------------------------------------------------------------------------

describe('parseGraphqlBody — bounded, graceful degradation', () => {
  it('returns null (never throws) for a BINARY / compressed body', () => {
    expect(parseGraphqlBody({ type: 'BINARY', base64Bytes: 'H4sIAAAAAAAA/w==' })).toBeNull();
  });

  it('returns null for binary noise and control characters', () => {
    const noise = String.fromCharCode(...Array.from({ length: 512 }, (_, i) => (i * 7) % 256));
    expect(() => parseGraphqlBody(noise)).not.toThrow();
    expect(parseGraphqlBody(noise)).toBeNull();
    expect(parseGraphqlBody({ type: 'STRING', string: ' {{{{' })).toBeNull();
  });

  it('returns null for truncated / malformed documents rather than throwing', () => {
    for (const query of [
      'query GetUser { user { id',            // unclosed braces
      'query GetUser { user { id } } }}}}}}', // unbalanced the other way
      'query "unterminated string { a }',
      '{'.repeat(5000),
      '}'.repeat(5000),
      '#'.repeat(5000),
      '"'.repeat(5000),
      '{ """ unterminated block string',
    ]) {
      expect(() => parseGraphqlBody(jsonBody({ query })), query.slice(0, 20)).not.toThrow();
    }
    // Nothing above may be reported with a bogus operation name.
    expect(parseGraphqlBody(jsonBody({ query: '{'.repeat(5000) }))).toBeNull();
    expect(parseGraphqlBody(jsonBody({ query: '"'.repeat(5000) }))).toBeNull();
  });

  it('still names a document that is merely truncated after its operation', () => {
    expect(parseGraphqlBody(jsonBody({ query: 'query GetUser { user { id' })))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
  });

  it('gives up on an oversized body instead of scanning it', () => {
    const huge = 'query GetUser { user { id } } ' + 'x'.repeat(300 * 1024);
    const start = Date.now();
    expect(parseGraphqlBody({ type: 'STRING', string: huge })).toBeNull();
    expect(Date.now() - start).toBeLessThan(2000);
  });

  it('stays fast on a large but in-bounds document', () => {
    const query = 'query GetUser { user { ' + 'field '.repeat(20000) + '} }';
    const start = Date.now();
    expect(parseGraphqlBody(jsonBody({ query })))
      .toEqual({ operationName: 'GetUser', operationType: 'query' });
    expect(Date.now() - start).toBeLessThan(2000);
  });
});

describe('looksLikeGraphqlDocument', () => {
  it('accepts documents and rejects look-alikes', () => {
    expect(looksLikeGraphqlDocument('query GetUser { user { id } }')).toBe(true);
    expect(looksLikeGraphqlDocument('{ user { id } }')).toBe(true);
    expect(looksLikeGraphqlDocument('# comment\nmutation M { a }')).toBe(true);
    expect(looksLikeGraphqlDocument('SELECT * FROM users')).toBe(false);
    expect(looksLikeGraphqlDocument('{"a":1}')).toBe(false);
    expect(looksLikeGraphqlDocument('')).toBe(false);
    expect(looksLikeGraphqlDocument('   ')).toBe(false);
    expect(looksLikeGraphqlDocument('# only a comment')).toBe(false);
  });
});

describe('graphqlOperationLabel', () => {
  it('prefers the name and falls back to the type', () => {
    expect(graphqlOperationLabel({ operationName: 'GetUser', operationType: 'query' })).toBe('GetUser');
    expect(graphqlOperationLabel({ operationName: null, operationType: 'mutation' })).toBe('mutation');
    expect(graphqlOperationLabel({ operationName: null, operationType: null })).toBe('GraphQL');
  });
});

describe('graphqlOperationOf', () => {
  it('returns the same object for the same request reference', () => {
    const value = captured(jsonBody({ query: 'query GetUser { user { id } }' }));
    const first = graphqlOperationOf(value);
    expect(first).toEqual({ operationName: 'GetUser', operationType: 'query' });
    // Reference stability is what keeps the memoised traffic row skippable.
    expect(graphqlOperationOf({ ...value })).toBe(first);
  });

  it('returns null for a value with no request', () => {
    expect(graphqlOperationOf({ httpResponse: { statusCode: 200 } })).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// The `operation:` filter operator
// ---------------------------------------------------------------------------

describe('operation: filter field', () => {
  afterEach(() => {
    // The registry is module-level mutable state — restore the built-ins, then
    // re-apply this module's registration (which import time performed once).
    resetFilterFields();
    registerGraphqlOperationField();
  });

  it('is registered with a GraphQL-aware description on import', () => {
    expect(getFilterField('operation')?.description).toMatch(/GraphQL operation name/);
  });

  it('matches a name that only appears inside the query document', () => {
    const value = captured(jsonBody({ query: 'query GetUser { user { id } }' }));
    expect(matchesItemSearch(value, 'operation:GetUser')).toBe(true);
    expect(matchesItemSearch(value, 'operation:CreateOrder')).toBe(false);
  });

  it('matches a name given as an operationName member', () => {
    const value = captured(jsonBody({
      query: 'query GetUser { user { id } } query GetOrder { order { id } }',
      operationName: 'GetOrder',
    }));
    expect(matchesItemSearch(value, 'operation:GetOrder')).toBe(true);
    expect(matchesItemSearch(value, 'operation:GetUser')).toBe(false);
  });

  it('supports globs and is case-insensitive', () => {
    const value = captured(jsonBody({ query: 'query GetUserOrders { orders { id } }' }));
    expect(matchesItemSearch(value, 'operation:Get*')).toBe(true);
    expect(matchesItemSearch(value, 'operation:getuserorders')).toBe(true);
    expect(matchesItemSearch(value, 'operation:Create*')).toBe(false);
  });

  it('does not match non-GraphQL JSON carrying an operationName key', () => {
    const value = captured(jsonBody({ operationName: 'GetUser', result: 'ok' }));
    expect(matchesItemSearch(value, 'operation:GetUser')).toBe(false);
  });

  it('never matches an anonymous operation', () => {
    const value = captured(jsonBody({ query: '{ user { id } }' }));
    expect(matchesItemSearch(value, 'operation:query')).toBe(false);
    expect(matchesItemSearch(value, 'operation:*')).toBe(false);
  });

  // Degrade-and-confirm-red: without the registration this module performs, the
  // built-in resolver cannot see a name that lives only inside the document, so
  // the assertion above must fail. This proves the test is actually exercising
  // the new resolver rather than passing for an unrelated reason.
  it('DEGRADED: the built-in resolver alone cannot match a document-only name', () => {
    resetFilterFields();
    const value = captured(jsonBody({ query: 'query GetUser { user { id } }' }));
    expect(matchesItemSearch(value, 'operation:GetUser')).toBe(false);
  });

  // The app entry (main.tsx) calls this explicitly, so the operator's behaviour
  // does not depend on which components happened to be pulled into the module
  // graph. Re-registering must be safe and must restore the parser after a
  // reset, since every search surface shares the one registry.
  it('re-registers idempotently, restoring the parser after a reset', () => {
    resetFilterFields();
    expect(getFilterField('operation')?.description).not.toMatch(/GraphQL operation name/);

    registerGraphqlOperationField();
    registerGraphqlOperationField();

    expect(getFilterField('operation')?.description).toMatch(/GraphQL operation name/);
    expect(filterFields().filter((f) => f.name === 'operation')).toHaveLength(1);

    const value = captured(jsonBody({ query: 'query GetUser { user { id } }' }));
    expect(matchesItemSearch(value, 'operation:GetUser')).toBe(true);
  });
});
