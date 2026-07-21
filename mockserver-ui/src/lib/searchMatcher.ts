/**
 * Search-matching interface shim.
 *
 * The implementation moved to `lib/filterDSL.ts`, which generalises the former
 * hard-coded `status:`/`method:`/`path:` vocabulary into a field registry shared
 * by every filter surface. This module re-exports the original public API
 * unchanged so existing call sites (`Panel.tsx`, `TrafficInspector.tsx`,
 * `LogPanel.tsx`, `RequestPanel.tsx`, `ExpectationPanel.tsx`) keep working
 * without edits.
 *
 * New code should import from `lib/filterDSL` directly — it additionally exposes
 * the field registry (`registerFilterField`), the per-call-site operator subset
 * (`FilterOptions`), and `describeUnsupportedOperators`.
 */

export {
  extractSearchableFields,
  isForwardedLogEntry,
  parseSearchTerm,
  matchesItemSearch,
  matchesLogSearch,
} from './filterDSL';

export type { FieldOperator, ParsedTerm } from './filterDSL';
