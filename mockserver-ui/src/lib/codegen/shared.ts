/**
 * Shared, language-agnostic helpers for the per-language client-code emitters
 * (node / python / go / csharp / ruby / rust) plus the verification and
 * load-scenario code generators.
 *
 * These live here — rather than inside any one per-language emitter module — so
 * they remain a stable dependency surface while the individual emitters are
 * rewritten independently. `standardCodegen.ts` re-exports all four under their
 * original names, so existing import sites that pull them from `./standardCodegen`
 * keep working unchanged.
 */

/** Derive the client host/port from a base URL, defaulting to localhost:1080
 *  (or :443 for https) and falling back to localhost:1080 on a parse failure. */
export function clientHostPort(baseUrl: string): { host: string; port: number } {
  try {
    const u = new URL(baseUrl);
    return {
      host: u.hostname || 'localhost',
      port: u.port ? Number(u.port) : (u.protocol === 'https:' ? 443 : 1080),
    };
  } catch {
    return { host: 'localhost', port: 1080 };
  }
}

/** Re-indents every line of `block` after the first by `pad` spaces (the first line is already
 *  positioned by the caller). */
export function indentAfterFirst(block: string, pad: number): string {
  return block.split('\n').join('\n' + ' '.repeat(pad));
}

/** Renders a JSON-compatible value as a Python literal (true/false/null → True/False/None). */
export function toPythonLiteral(value: unknown, indent: number): string {
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 4);
  if (value === null || value === undefined) return 'None';
  if (typeof value === 'boolean') return value ? 'True' : 'False';
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    return '[\n' + value.map((v) => pad2 + toPythonLiteral(v, indent + 4)).join(',\n') + '\n' + pad + ']';
  }
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) return '{}';
  return '{\n' + entries.map(([k, v]) => pad2 + JSON.stringify(k) + ': ' + toPythonLiteral(v, indent + 4)).join(',\n') + '\n' + pad + '}';
}

/** Wraps `s` in a Rust raw string literal, using as many `#`s as needed so the content can't
 *  prematurely terminate it. */
export function rustRawString(s: string): string {
  let hashes = '#';
  while (s.includes('"' + hashes)) hashes += '#';
  return `r${hashes}"${s}"${hashes}`;
}
