/**
 * Shared scenario-state sorting and turn-grouping utilities.
 *
 * Unifies the duplicated logic from ExpectationPanel and TrafficInspector
 * into a single canonical implementation.
 */
import type { JsonListItem } from '../types';

/**
 * Canonical ordering for scenarioState values:
 * - 'Started' (default first expectation registered with no prior state) always sorts first
 * - 'turn_N' values sort in numeric order
 * - '__done' sinks to the bottom
 * - Anything else sorts near the end alphabetically
 */
export function scenarioStateSortKey(state: string): number {
  if (state === 'Started') return -1;
  const match = /^turn_(\d+)$/.exec(state);
  if (match) return parseInt(match[1]!, 10);
  if (state === '__done') return 999999;
  return 500000; // unknown states sort near the end
}

/**
 * Build a map: expectation `key` -> { position, total } based on grouping by
 * scenarioName and sorting by scenarioState. Only stateful (LLM conversation)
 * expectations participate; the map is empty for non-stateful ones.
 */
export function buildTurnPositionMap(items: JsonListItem[]): Map<string, { position: number; total: number }> {
  const groups = new Map<string, JsonListItem[]>();
  for (const item of items) {
    const scenarioName = item.value['scenarioName'] as string | undefined;
    if (!scenarioName) continue;
    const arr = groups.get(scenarioName) ?? [];
    arr.push(item);
    groups.set(scenarioName, arr);
  }

  const result = new Map<string, { position: number; total: number }>();
  for (const group of groups.values()) {
    if (group.length < 2) continue; // single-state scenarios get no turn-of label
    const sorted = [...group].sort((a, b) => {
      const aState = (a.value['scenarioState'] as string | undefined) ?? '';
      const bState = (b.value['scenarioState'] as string | undefined) ?? '';
      return scenarioStateSortKey(aState) - scenarioStateSortKey(bState);
    });
    sorted.forEach((item, idx) => {
      result.set(item.key, { position: idx + 1, total: sorted.length });
    });
  }
  return result;
}

// ---------------------------------------------------------------------------
// Per-scenario detail model (what a scenario IS)
// ---------------------------------------------------------------------------

/**
 * One expectation bound to a scenario, flattened to the fields the Scenario
 * panel renders: a method/path summary, the state it matches in
 * (`scenarioState`), and the state it transitions to (`newScenarioState`). The
 * full expectation `value` is carried so the row's Edit action can hand it to
 * the Composer via `editExpectation`, preserving scenario bindings.
 */
export interface BoundExpectation {
  key: string;
  id: string | null;
  method: string | null;
  path: string | null;
  /** The scenario state this expectation matches in. Defaults to 'Started'. */
  scenarioState: string;
  /** The scenario state this expectation transitions to when matched, if any. */
  newScenarioState: string | null;
  /** The full expectation JSON, for the Edit hand-off to the Composer. */
  value: Record<string, unknown>;
}

/** Expectations bound to a single scenario state. */
export interface ScenarioStateGroup {
  state: string;
  expectations: BoundExpectation[];
}

/** A scenario and everything the dashboard knows about it, client-side. */
export interface ScenarioDetail {
  scenarioName: string;
  /** Live current state from the server list, if the scenario is known there. */
  currentState: string | null;
  /** State groups (sorted by canonical scenario-state order). */
  states: ScenarioStateGroup[];
  /** Total number of bound expectations across all states. */
  expectationCount: number;
}

/**
 * Build the per-scenario detail model entirely client-side. Bound expectations
 * come from the store's `activeExpectations` (each carries `scenarioName`,
 * `scenarioState`, `newScenarioState`, and `httpRequest` as top-level fields —
 * no server fetch needed). `currentStates` maps a scenario name to its live
 * current state (from `GET /mockserver/scenario`); it both annotates scenarios
 * with expectations and surfaces scenarios that exist server-side but have no
 * client-side expectation (e.g. cross-protocol trigger-only scenarios).
 */
export function buildScenarioDetails(
  expectations: JsonListItem[],
  currentStates: ReadonlyMap<string, string>,
): ScenarioDetail[] {
  const groups = new Map<string, JsonListItem[]>();
  for (const item of expectations) {
    const scenarioName = item.value['scenarioName'];
    if (typeof scenarioName !== 'string' || scenarioName === '') continue;
    const arr = groups.get(scenarioName) ?? [];
    arr.push(item);
    groups.set(scenarioName, arr);
  }
  // Union in server-side scenarios that have no client-side expectations so the
  // panel still lists them (with their current state) rather than hiding them.
  for (const name of currentStates.keys()) {
    if (!groups.has(name)) groups.set(name, []);
  }

  const details: ScenarioDetail[] = [];
  for (const [scenarioName, items] of groups) {
    const stateMap = new Map<string, BoundExpectation[]>();
    for (const item of items) {
      const state = ((item.value['scenarioState'] as string | undefined)?.trim()) || 'Started';
      const req = item.value['httpRequest'] as Record<string, unknown> | undefined;
      const newState = item.value['newScenarioState'];
      const bound: BoundExpectation = {
        key: item.key,
        id: typeof item.value['id'] === 'string' ? (item.value['id'] as string) : null,
        method: req && typeof req['method'] === 'string' ? (req['method'] as string) : null,
        path: req && typeof req['path'] === 'string' ? (req['path'] as string) : null,
        scenarioState: state,
        newScenarioState: typeof newState === 'string' && newState !== '' ? newState : null,
        value: item.value,
      };
      const arr = stateMap.get(state) ?? [];
      arr.push(bound);
      stateMap.set(state, arr);
    }
    const states: ScenarioStateGroup[] = Array.from(stateMap.entries())
      .map(([state, exps]) => ({ state, expectations: exps }))
      .sort((a, b) => scenarioStateSortKey(a.state) - scenarioStateSortKey(b.state));
    details.push({
      scenarioName,
      currentState: currentStates.get(scenarioName) ?? null,
      states,
      expectationCount: items.length,
    });
  }
  details.sort((a, b) => a.scenarioName.localeCompare(b.scenarioName));
  return details;
}
