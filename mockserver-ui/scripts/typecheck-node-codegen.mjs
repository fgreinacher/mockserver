// Typecheck helper for the Node client-code generator's emitted object literals.
//
// WHY: the composer's Node tab (standardCodegen.ts -> standardToNode) emits a
// `mockServerClient(...).mockAnyResponse({ ...literal... })` call whose object
// literal is the client's typed entry point. String-assertion unit tests pin the
// SHAPE of that literal but cannot catch a client-type change that the literal
// would violate (e.g. a renamed/removed Expectation field) — the generated code
// would ship type-broken silently. This helper compiles each emitted literal as
// `const _n: Expectation = <literal>;` against the REAL client type
// (../mockserver-client-node/mockServer.d.ts) with a strict `tsc`, so node.test.ts
// can assert the whole matrix typechecks (and a bogus key is rejected).
//
// WHY A SCRIPT (not inline in the vitest test): the mechanics need node:fs /
// node:child_process, but the app tsconfig (include: ["src"]) has no @types/node,
// so importing those built-ins from a typechecked test under src/ would break
// `tsc --noEmit`. Living in scripts/ (outside the tsconfig include, like
// emit-java-codegen-samples.mjs) keeps this plain JS and untypechecked, while the
// test imports it dynamically and passes in the already-emitted literals — so the
// test itself needs no node built-ins and stays tsc-clean.

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, relative, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const tscBin = resolve(here, '../node_modules/.bin/tsc');
// The client's generated types (self-contained — the file has no imports of its own).
const clientDts = resolve(here, '../../mockserver-client-node/mockServer');
const scratchDir = resolve(here, '../../.tmp/node-typecheck');

/**
 * Compile a synthetic module of `const sample_N: Expectation = <literal>;`
 * declarations with the workspace `tsc` under --strict.
 *
 * @param {string} fileName scratch file name (unique per call site)
 * @param {string[]} literals emitted object literals (mockAnyResponse arguments)
 * @returns {{ ok: boolean, output: string }} ok=true when tsc reports no errors
 */
export function typecheckExpectationLiterals(fileName, literals) {
  mkdirSync(scratchDir, { recursive: true });
  const file = join(scratchDir, fileName);
  const importPath = relative(scratchDir, clientDts).replace(/\\/g, '/');
  const body = literals
    .map((lit, i) => `export const sample_${i}: Expectation = ${lit};`)
    .join('\n\n');
  writeFileSync(file, `import type { Expectation } from '${importPath}';\n\n${body}\n`);
  try {
    execFileSync(
      tscBin,
      ['--noEmit', '--strict', '--skipLibCheck', '--module', 'esnext', '--moduleResolution', 'bundler', '--ignoreConfig', file],
      { encoding: 'utf8', stdio: 'pipe' },
    );
    return { ok: true, output: '' };
  } catch (err) {
    return { ok: false, output: `${err.stdout ?? ''}${err.stderr ?? ''}` };
  }
}

/** Remove the scratch directory (best-effort). */
export function cleanupTypecheckScratch() {
  try { rmSync(scratchDir, { recursive: true, force: true }); } catch { /* noop */ }
}
