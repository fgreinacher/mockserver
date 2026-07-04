/**
 * Byte-exact golden output for the per-language emitters, captured from the
 * code BEFORE the six non-Java emitters were extracted from standardCodegen.ts
 * into per-language modules under lib/codegen/. Consumed by extractParity.test.ts
 * to prove the extraction (and later typed-emitter rewrites) leave emitter output
 * character-for-character identical.
 *
 * Shape: { [language]: { [comboName]: emittedSource } }. This is a generated
 * fixture — do not hand-edit. To intentionally update it after a reviewed output
 * change, regenerate from the current emitters over the shared `combos`.
 *
 * The Node case has been moved into its own fixture (./nodeGolden.ts) and test
 * (../node.test.ts), which additionally proves each emitted literal typechecks
 * against the client's `Expectation` type; the remaining languages stay here.
 */
export const emitterGolden: Record<string, Record<string, string>> = {
};
