import { describe, it, expect } from 'vitest';
import {
  mismatchDifferencesToDiffResult,
  filterIgnoredHeaderDiffs,
  parseIgnoredHeaders,
  DEFAULT_IGNORED_DIFF_HEADERS,
  type DiffResult,
} from '../lib/diff';

describe('mismatchDifferencesToDiffResult', () => {
  it('returns an identical/zero result for undefined or empty differences', () => {
    expect(mismatchDifferencesToDiffResult(undefined)).toEqual({
      diffCount: 0,
      identical: true,
      diffs: [],
    });
    expect(mismatchDifferencesToDiffResult({})).toEqual({
      diffCount: 0,
      identical: true,
      diffs: [],
    });
  });

  it('parses "expected X but was Y" reasons into expected/actual CHANGED rows', () => {
    const result = mismatchDifferencesToDiffResult({
      path: ['expected /api/users but was /api/items'],
      method: ['expected POST but was GET'],
    });
    expect(result.identical).toBe(false);
    expect(result.diffCount).toBe(2);
    expect(result.diffs).toContainEqual({
      field: 'path',
      expectedValue: '/api/users',
      actualValue: '/api/items',
      diffType: 'CHANGED',
    });
    expect(result.diffs).toContainEqual({
      field: 'method',
      expectedValue: 'POST',
      actualValue: 'GET',
      diffType: 'CHANGED',
    });
  });

  it('keeps the raw reason text when it does not match the expected/was pattern', () => {
    const result = mismatchDifferencesToDiffResult({
      body: ['no JSON body was present'],
    });
    expect(result.diffs).toEqual([
      {
        field: 'body',
        expectedValue: 'no JSON body was present',
        actualValue: undefined,
        diffType: 'CHANGED',
      },
    ]);
  });

  it('emits one row per reason for a field with multiple differences', () => {
    const result = mismatchDifferencesToDiffResult({
      headers: [
        'expected accept but was missing',
        'expected application/json but was text/plain',
      ],
    });
    expect(result.diffCount).toBe(2);
    expect(result.diffs.every((d) => d.field === 'headers')).toBe(true);
  });

  it('parses multi-line / whitespace-heavy body reasons', () => {
    const result = mismatchDifferencesToDiffResult({
      body: ['expected {\n  "a": 1\n} but was {\n  "a": 2\n}'],
    });
    expect(result.diffs[0]).toEqual({
      field: 'body',
      expectedValue: '{\n  "a": 1\n}',
      actualValue: '{\n  "a": 2\n}',
      diffType: 'CHANGED',
    });
  });
});

describe('parseIgnoredHeaders', () => {
  it('splits, trims, lower-cases and de-duplicates', () => {
    expect(parseIgnoredHeaders('Date, X-Request-Id ,  date ,traceparent')).toEqual([
      'date',
      'x-request-id',
      'traceparent',
    ]);
  });

  it('returns an empty array for blank input', () => {
    expect(parseIgnoredHeaders('')).toEqual([]);
    expect(parseIgnoredHeaders('   ,  ,')).toEqual([]);
  });
});

describe('filterIgnoredHeaderDiffs', () => {
  const changed = (field: string): DiffResult['diffs'][number] => ({
    field,
    expectedValue: 'a',
    actualValue: 'b',
    diffType: 'CHANGED',
  });
  const resultOf = (fields: string[]): DiffResult => ({
    diffs: fields.map(changed),
    diffCount: fields.length,
    identical: fields.length === 0,
  });

  it('drops rows whose field references an ignored header and recomputes counts', () => {
    const result = resultOf(['headers.Date', 'method', 'headers.X-Request-Id']);
    const filtered = filterIgnoredHeaderDiffs(result, ['date', 'x-request-id']);
    expect(filtered.diffs.map((d) => d.field)).toEqual(['method']);
    expect(filtered.diffCount).toBe(1);
    expect(filtered.identical).toBe(false);
  });

  it('matches the header-prefixed field shapes, including the real server shape header.<name>', () => {
    const result = resultOf(['header.date', 'headers.Date', 'header:Date', 'headers[Date]', 'header.content-length', 'body']);
    const filtered = filterIgnoredHeaderDiffs(result, ['date', 'content-length']);
    expect(filtered.diffs.map((d) => d.field)).toEqual(['body']);
  });

  it('never hides non-header fields that share a name with an ignored header', () => {
    // queryParam.date / cookie.date / a bare Date field are NOT headers.
    const result = resultOf(['queryParam.date', 'cookie.date', 'Date', 'header.date']);
    const filtered = filterIgnoredHeaderDiffs(result, ['date']);
    expect(filtered.diffs.map((d) => d.field)).toEqual(['queryParam.date', 'cookie.date', 'Date']);
  });

  it('keeps multi-word header names whole — never splits on hyphen', () => {
    // "date" must NOT spuriously match "date-of-birth"; "content-length" must match.
    const result = resultOf(['headers.date-of-birth', 'headers.content-length']);
    const filtered = filterIgnoredHeaderDiffs(result, ['date', 'content-length']);
    expect(filtered.diffs.map((d) => d.field)).toEqual(['headers.date-of-birth']);
  });

  it('becomes identical when every remaining diff is an ignored header', () => {
    const filtered = filterIgnoredHeaderDiffs(resultOf(['headers.Date']), ['date']);
    expect(filtered.diffs).toEqual([]);
    expect(filtered.diffCount).toBe(0);
    expect(filtered.identical).toBe(true);
  });

  it('returns the input unchanged (same reference) when nothing is ignored or nothing matched', () => {
    const result = resultOf(['method', 'path']);
    expect(filterIgnoredHeaderDiffs(result, [])).toBe(result);
    expect(filterIgnoredHeaderDiffs(result, ['date'])).toBe(result);
  });

  it('ships a sensible default ignore-list', () => {
    expect(DEFAULT_IGNORED_DIFF_HEADERS).toEqual(['date', 'x-request-id', 'traceparent', 'content-length']);
  });
});
