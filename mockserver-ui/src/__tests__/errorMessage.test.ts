import { describe, it, expect } from 'vitest';
import { humanizeServerError, humanizeError } from '../lib/errorMessage';

describe('humanizeServerError', () => {
  it('400 → invalid, keeps raw body in details', () => {
    const r = humanizeServerError(400, 'some validation wall of text');
    expect(r.message).toBe('The request was rejected as invalid.');
    expect(r.details).toBe('some validation wall of text');
  });

  it('400 → extracts reason from a JSON {error} envelope', () => {
    const r = humanizeServerError(400, JSON.stringify({ error: 'path must start with /' }));
    expect(r.message).toBe('The request was rejected as invalid: path must start with /');
    expect(r.details).toContain('path must start with /');
  });

  it('400 → extracts the first line of a JSON-schema validation body', () => {
    const body = '1 error:\nfield "method" is required\n  at /httpRequest';
    const r = humanizeServerError(400, body);
    expect(r.message).toBe('The request was rejected as invalid: 1 error:');
    expect(r.details).toBe(body);
  });

  it('401 → not authorised', () => {
    expect(humanizeServerError(401, '').message).toBe(
      'Not authorised — the server rejected the credentials.',
    );
  });

  it('403 → not authorised', () => {
    expect(humanizeServerError(403, 'denied').message).toBe(
      'Not authorised — the server rejected the credentials.',
    );
  });

  it('404 → feature unavailable / older version', () => {
    expect(humanizeServerError(404, '').message).toContain('isn’t available');
  });

  it('409 → conflict', () => {
    expect(humanizeServerError(409, '').message).toBe(
      'The request conflicts with the current state.',
    );
  });

  it('409 → conflict with extracted reason', () => {
    const r = humanizeServerError(409, JSON.stringify({ error: 'already exists' }));
    expect(r.message).toBe('The request conflicts with the current state: already exists');
  });

  it('500 → internal error', () => {
    expect(humanizeServerError(500, 'NPE stacktrace').message).toBe(
      'The MockServer encountered an internal error.',
    );
  });

  it('503 → internal error (any 5xx)', () => {
    expect(humanizeServerError(503, '').message).toBe(
      'The MockServer encountered an internal error.',
    );
  });

  it('other status → generic message including the status', () => {
    expect(humanizeServerError(418, '').message).toBe(
      'The MockServer returned an unexpected status (418).',
    );
  });

  it('omits details when the body is blank', () => {
    expect(humanizeServerError(400, '   ').details).toBeUndefined();
  });
});

describe('humanizeError', () => {
  it('parses the "MockServer returned <status>: <body>" shape and delegates', () => {
    const err = new Error('MockServer returned 404: Not Found');
    const r = humanizeError(err);
    expect(r.message).toContain('isn’t available');
  });

  it('parses a 400 server-returned shape and extracts the JSON reason', () => {
    const err = new Error('MockServer returned 400: {"error":"bad path"}');
    const r = humanizeError(err);
    expect(r.message).toBe('The request was rejected as invalid: bad path');
  });

  it('parses a multi-line server-returned body', () => {
    const err = new Error('MockServer returned 500: java.lang.RuntimeException\n  at Foo');
    const r = humanizeError(err);
    expect(r.message).toBe('The MockServer encountered an internal error.');
    expect(r.details).toContain('java.lang.RuntimeException');
  });

  it('parses the "Replay failed (<status>): <body>" shape', () => {
    const err = new Error('Replay failed (502): upstream down');
    const r = humanizeError(err);
    expect(r.message).toBe('The MockServer encountered an internal error.');
  });

  it('detects a TypeError: Failed to fetch as a network error', () => {
    const err = new TypeError('Failed to fetch');
    const r = humanizeError(err);
    expect(r.message).toBe('Couldn’t reach the MockServer — is it still running?');
    expect(r.details).toBe('Failed to fetch');
  });

  it('detects connection-refused text as a network error', () => {
    const r = humanizeError(new Error('connect ECONNREFUSED 127.0.0.1:1080'));
    expect(r.message).toBe('Couldn’t reach the MockServer — is it still running?');
  });

  it("detects Safari's 'Load failed' TypeError as a network error", () => {
    const r = humanizeError(new TypeError('Load failed'));
    expect(r.message).toBe('Couldn’t reach the MockServer — is it still running?');
  });

  it("detects Firefox's 'NetworkError when attempting to fetch resource' as a network error", () => {
    const r = humanizeError(
      new TypeError('NetworkError when attempting to fetch resource.'),
    );
    expect(r.message).toBe('Couldn’t reach the MockServer — is it still running?');
  });

  it('does NOT treat a genuine programming TypeError as a network error', () => {
    // A real bug thrown inside a fetch .then chain must surface, not be masked
    // as "couldn't reach the server".
    const r = humanizeError(
      new TypeError("Cannot read properties of undefined (reading 'body')"),
    );
    expect(r.message).toBe("Cannot read properties of undefined (reading 'body')");
    expect(r.message).not.toBe('Couldn’t reach the MockServer — is it still running?');
    expect(r.details).toBeUndefined();
  });

  it('passes through an unrelated error message', () => {
    const r = humanizeError(new Error('something specific broke'));
    expect(r.message).toBe('something specific broke');
    expect(r.details).toBeUndefined();
  });

  it('handles non-Error throwables', () => {
    expect(humanizeError('plain string failure').message).toBe('plain string failure');
  });
});
