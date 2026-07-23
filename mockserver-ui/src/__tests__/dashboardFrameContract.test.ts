import { describe, it, expect, beforeEach } from 'vitest';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';
import contract from '../__fixtures__/dashboardFrameContract.json';

/**
 * UI half of the cross-boundary STRUCTURAL contract for the dashboard WebSocket frame.
 *
 * It reads the SAME shared, checked-in contract file the Java test
 * (DashboardWebSocketFrameContractTest) reads — `src/__fixtures__/dashboardFrameContract.json` —
 * and asserts that when the contract's `representativeFrame` is fed through the REAL store
 * `applyMessage`, every field the panels read is present on the resulting store items, with the
 * type the contract requires.
 *
 * Because both sides read `panels.*.requiredFields` from the one file, renaming or removing a
 * required field name there reddens BOTH this test and the Java test — that is the cross-boundary
 * drift bite. It never asserts dynamic values, timestamps, UUIDs or array ordering, so it cannot
 * drift across environments the way the previous byte-equal-golden did.
 */

type JsonType = 'string' | 'number' | 'boolean' | 'object' | 'array' | 'null';

interface RequiredFields {
  [field: string]: JsonType;
}

interface FlatPanelSpec {
  requiredFields: RequiredFields;
}

interface VariantPanelSpec {
  discriminator: string;
  variants: Record<string, { requiredFields: RequiredFields }>;
}

type PanelSpec = FlatPanelSpec | VariantPanelSpec;

interface Correlation {
  producerPanel: string;
  producerKeySuffix: string;
  correlatedPanel: string;
  correlatedKeySuffix: string;
}

interface Contract {
  panels: Record<string, unknown>;
  correlations: Correlation[];
  representativeFrame: WebSocketMessage;
}

const typedContract = contract as unknown as Contract;

function jsonType(value: unknown): JsonType {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value as JsonType;
}

function isVariantSpec(spec: PanelSpec): spec is VariantPanelSpec {
  return 'variants' in spec;
}

/** Resolve which required-field set applies to an item (flat panel, or entry/group variant). */
function requiredFieldsFor(spec: PanelSpec, item: Record<string, unknown>): RequiredFields {
  if (isVariantSpec(spec)) {
    const { discriminator, variants } = spec;
    let chosen: string | null = null;
    let fallback: string | null = null;
    for (const [variantName, variantSpec] of Object.entries(variants)) {
      if (variantName.startsWith('_')) continue;
      if (discriminator in variantSpec.requiredFields) {
        if (discriminator in item) chosen = variantName;
      } else {
        fallback = variantName;
      }
    }
    const variant = chosen ?? fallback;
    if (variant === null) throw new Error('no matching variant for item');
    return variants[variant]!.requiredFields;
  }
  return spec.requiredFields;
}

/** Every non-underscore panel name declared in the contract. */
function contractPanelNames(): string[] {
  return Object.keys(typedContract.panels).filter((name) => !name.startsWith('_'));
}

function assertItemSatisfiesContract(panel: string, item: Record<string, unknown>): void {
  const spec = typedContract.panels[panel] as PanelSpec;
  const required = requiredFieldsFor(spec, item);
  for (const [field, expectedType] of Object.entries(required)) {
    if (field.startsWith('_')) continue;
    expect(item, `${panel} item missing required field '${field}' (contract drift)`).toHaveProperty(field);
    expect(jsonType(item[field]), `${panel} item field '${field}' wrong type`).toBe(expectedType);
  }
}

describe('dashboard WebSocket frame cross-boundary contract (UI side)', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
      error: null,
      errorSource: null,
    });
  });

  it('the shared representative frame carries all four panels', () => {
    const frame = typedContract.representativeFrame;
    for (const panel of contractPanelNames()) {
      const arr = (frame as unknown as Record<string, unknown>)[panel];
      expect(Array.isArray(arr), `representative frame missing panel '${panel}'`).toBe(true);
      expect((arr as unknown[]).length, `representative frame panel '${panel}' should be populated`).toBeGreaterThan(0);
    }
  });

  it('store items expose every field the panels read, for the required types', () => {
    // Drive the REAL store path the live WebSocket uses.
    useDashboardStore.getState().applyMessage(typedContract.representativeFrame);

    for (const panel of contractPanelNames()) {
      const items = (useDashboardStore.getState() as unknown as Record<string, Record<string, unknown>[]>)[panel];
      if (!items) throw new Error(`store panel '${panel}' missing after applyMessage`);
      expect(items.length).toBeGreaterThan(0);
      for (const item of items) {
        assertItemSatisfiesContract(panel, item);
      }
    }
  });

  it('the server-assigned key correlations hold in the representative frame', () => {
    const frame = typedContract.representativeFrame as unknown as Record<string, Array<Record<string, unknown>>>;
    for (const correlation of typedContract.correlations) {
      const producers = frame[correlation.producerPanel]!;
      const correlated = frame[correlation.correlatedPanel]!;
      for (const producer of producers) {
        const key = producer['key'] as string;
        if (!key.endsWith(correlation.producerKeySuffix)) continue;
        const serverId = key.slice(0, key.length - correlation.producerKeySuffix.length);
        const expectedKey = serverId + correlation.correlatedKeySuffix;
        const match = correlated.some((c) => c['key'] === expectedKey);
        expect(
          match,
          `key correlation broken: ${correlation.producerPanel} key '${key}' has no matching ${correlation.correlatedPanel} key '${expectedKey}'`,
        ).toBe(true);
      }
    }
  });

  it('applyMessage reconciles the representative frame into the store by key', () => {
    useDashboardStore.getState().applyMessage(typedContract.representativeFrame);
    const state = useDashboardStore.getState();
    expect(state.logMessages.length).toBe(typedContract.representativeFrame.logMessages.length);
    expect(state.activeExpectations.length).toBe(typedContract.representativeFrame.activeExpectations.length);
    expect(state.recordedRequests.length).toBe(typedContract.representativeFrame.recordedRequests.length);
    expect(state.proxiedRequests.length).toBe(typedContract.representativeFrame.proxiedRequests.length);
  });
});
