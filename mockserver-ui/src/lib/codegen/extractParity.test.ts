/**
 * Byte-identity parity harness for the per-language client-code emitters.
 *
 * The committed golden fixture ({@link ./__fixtures__/emitterGolden.ts}) was
 * generated from the code BEFORE the six non-Java emitters (standardToPython /
 * Go / Csharp / Ruby / Rust — plus Node, now split out) were extracted out of
 * standardCodegen.ts into per-language modules under lib/codegen/. This test
 * asserts every emitter reproduces that golden output character-for-character
 * across the extraction refactor, and permanently guards the emitters against
 * accidental output drift during the subsequent typed-emitter rewrites.
 *
 * The Node emitter has its own byte-identity check (plus a client-type typecheck
 * proof) in ../node.test.ts, so it is no longer part of this shared harness.
 *
 * It drives the emitters via extractParityCases, which imports them from
 * '../standardCodegen' (their canonical re-export surface), so the import path is
 * identical before and after extraction. Snapshots are deliberately NOT used —
 * vitest snapshot state is unavailable in this jsdom pool — and an explicit,
 * committed golden is a clearer, self-documenting proof of byte-identity.
 */
import { describe, it, expect } from 'vitest';
import { combos, emitters } from './extractParityCases';
import { emitterGolden } from './__fixtures__/emitterGolden';
import { goGolden } from './__fixtures__/goGolden';

// Per-language golden lookup. The typed-emitter rewrites move each language's
// golden out of emitterGolden into its own fixture (so each rewrite is an
// isolated diff); the maps are merged here, per-language override winning.
const golden: Record<string, Record<string, string>> = {
  ...emitterGolden,
  go: goGolden,
};

describe('per-language emitter byte-identity parity', () => {
  for (const [lang, emit] of Object.entries(emitters)) {
    describe(lang, () => {
      for (const combo of combos) {
        it(`${combo.name}`, () => {
          const actual = emit(combo.matcher, combo.action, combo.baseUrl);
          expect(actual).toBe(golden[lang]?.[combo.name]);
        });
      }
    });
  }
});
