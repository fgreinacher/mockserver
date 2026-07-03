import { describe, it, expect } from 'vitest';
import { scenarioStateSortKey, buildTurnPositionMap, buildScenarioDetails } from '../lib/scenarioState';
import type { JsonListItem } from '../types';

describe('scenarioStateSortKey', () => {
  it('sorts Started first', () => {
    expect(scenarioStateSortKey('Started')).toBe(-1);
  });

  it('sorts turn_N values numerically', () => {
    expect(scenarioStateSortKey('turn_0')).toBe(0);
    expect(scenarioStateSortKey('turn_1')).toBe(1);
    expect(scenarioStateSortKey('turn_10')).toBe(10);
  });

  it('sorts __done last', () => {
    expect(scenarioStateSortKey('__done')).toBe(999999);
  });

  it('sorts unknown states near the end', () => {
    const key = scenarioStateSortKey('custom_state');
    expect(key).toBeGreaterThan(scenarioStateSortKey('turn_100'));
    expect(key).toBeLessThan(scenarioStateSortKey('__done'));
  });

  it('preserves relative order: Started < turn_1 < turn_2 < __done', () => {
    const keys = ['Started', 'turn_1', 'turn_2', '__done'].map(scenarioStateSortKey);
    for (let i = 1; i < keys.length; i++) {
      expect(keys[i]).toBeGreaterThan(keys[i - 1]!);
    }
  });
});

describe('buildTurnPositionMap', () => {
  it('returns empty map when no stateful expectations', () => {
    const items = [
      { key: 'e1', value: { httpResponse: {} } },
    ];
    expect(buildTurnPositionMap(items).size).toBe(0);
  });

  it('returns empty map for single-state scenarios', () => {
    const items = [
      { key: 'e1', value: { scenarioName: 'conv1', scenarioState: 'Started' } },
    ];
    expect(buildTurnPositionMap(items).size).toBe(0);
  });

  it('assigns positions for multi-state scenarios', () => {
    const items = [
      { key: 'e2', value: { scenarioName: 'conv1', scenarioState: 'turn_1' } },
      { key: 'e1', value: { scenarioName: 'conv1', scenarioState: 'Started' } },
    ];
    const map = buildTurnPositionMap(items);
    expect(map.get('e1')).toEqual({ position: 1, total: 2 });
    expect(map.get('e2')).toEqual({ position: 2, total: 2 });
  });
});

describe('buildScenarioDetails', () => {
  const items: JsonListItem[] = [
    {
      key: 'e-paid',
      value: {
        id: 'e-paid',
        scenarioName: 'checkout',
        scenarioState: 'PAID',
        newScenarioState: 'SHIPPED',
        httpRequest: { method: 'POST', path: '/ship' },
        httpResponse: { statusCode: 200 },
      },
    },
    {
      key: 'e-start',
      value: {
        id: 'e-start',
        scenarioName: 'checkout',
        scenarioState: 'Started',
        newScenarioState: 'PAID',
        httpRequest: { method: 'POST', path: '/pay' },
        httpResponse: { statusCode: 200 },
      },
    },
    {
      // Stateless mock — no scenarioName — must be ignored.
      key: 'e-none',
      value: { httpRequest: { method: 'GET', path: '/health' }, httpResponse: { statusCode: 200 } },
    },
  ];

  it('groups a scenario by state in canonical order with method/path and transitions', () => {
    const details = buildScenarioDetails(items, new Map([['checkout', 'PAID']]));
    expect(details).toHaveLength(1);
    const checkout = details[0]!;
    expect(checkout.scenarioName).toBe('checkout');
    expect(checkout.currentState).toBe('PAID');
    expect(checkout.expectationCount).toBe(2);
    // Started sorts before PAID (canonical scenarioStateSortKey order).
    expect(checkout.states.map((s) => s.state)).toEqual(['Started', 'PAID']);

    const started = checkout.states[0]!;
    expect(started.expectations[0]!.method).toBe('POST');
    expect(started.expectations[0]!.path).toBe('/pay');
    expect(started.expectations[0]!.scenarioState).toBe('Started');
    expect(started.expectations[0]!.newScenarioState).toBe('PAID');
    // The full JSON is carried for the Edit hand-off, preserving bindings.
    expect(started.expectations[0]!.value['scenarioName']).toBe('checkout');
    expect(started.expectations[0]!.value['newScenarioState']).toBe('PAID');

    const paid = checkout.states[1]!;
    expect(paid.expectations[0]!.newScenarioState).toBe('SHIPPED');
  });

  it('defaults a missing scenarioState to Started and a missing newScenarioState to null', () => {
    const detail = buildScenarioDetails(
      [{ key: 'x', value: { scenarioName: 's', httpRequest: { path: '/a' } } }],
      new Map(),
    )[0]!;
    expect(detail.states[0]!.state).toBe('Started');
    expect(detail.states[0]!.expectations[0]!.newScenarioState).toBeNull();
    expect(detail.states[0]!.expectations[0]!.method).toBeNull();
  });

  it('surfaces a server-side scenario that has no client-side expectations', () => {
    const details = buildScenarioDetails([], new Map([['crossProto', 'OBSERVED']]));
    expect(details).toHaveLength(1);
    expect(details[0]!.scenarioName).toBe('crossProto');
    expect(details[0]!.currentState).toBe('OBSERVED');
    expect(details[0]!.expectationCount).toBe(0);
    expect(details[0]!.states).toEqual([]);
  });

  it('ignores expectations with no scenarioName', () => {
    const details = buildScenarioDetails([items[2]!], new Map());
    expect(details).toEqual([]);
  });
});
