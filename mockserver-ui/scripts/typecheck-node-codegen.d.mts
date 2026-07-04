// Type declarations for typecheck-node-codegen.mjs so the (typechecked) test at
// src/lib/codegen/node.test.ts can import it without pulling node built-ins into
// the app tsconfig (which has no @types/node). See the .mjs header for rationale.

/**
 * Compile a synthetic module of `const sample_N: Expectation = <literal>;`
 * declarations with the workspace `tsc` under --strict.
 *
 * @param fileName scratch file name (unique per call site)
 * @param literals emitted object literals (mockAnyResponse arguments)
 * @returns ok=true when tsc reports no errors; output carries tsc diagnostics
 */
export function typecheckExpectationLiterals(
  fileName: string,
  literals: string[],
): { ok: boolean; output: string };

/** Remove the scratch directory (best-effort). */
export function cleanupTypecheckScratch(): void;
