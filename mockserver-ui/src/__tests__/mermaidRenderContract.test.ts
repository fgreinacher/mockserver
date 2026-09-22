import { describe, it, expect, beforeAll } from 'vitest';
import { toMermaid, type CallGraph } from '../lib/callGraph';
import { buildScenarioGraphModel, toScenarioMermaid } from '../lib/scenarioGraph';

/**
 * REAL, UNMOCKED contract test against the *installed* mermaid library.
 *
 * Unlike AgentRunGraph.test.tsx / ScenarioPanel.test.tsx — which `vi.mock('mermaid')`
 * and assert against a hand-written fake SVG, so they pass identically whether
 * mermaid is present, absent, or has changed its API — this suite imports the
 * real library and actually renders. It exists to give genuine confidence that a
 * mermaid upgrade (e.g. 11 -> 12) did not break the two things the dashboard
 * relies on:
 *   1. that the exact diagram source the app emits still parses and renders to a
 *      structurally-correct SVG (nodes/edges present, not just a `<svg` prefix), and
 *   2. that the SVG the dashboard injects via dangerouslySetInnerHTML is INERT
 *      end-to-end for hostile label content — no `<script>`, no `on*` handler, no
 *      `javascript:` URL survives the app-builder + mermaid render pipeline.
 *
 * What (2) does NOT claim: it does not prove `securityLevel: 'strict'` is the thing
 * doing the neutralising. Mermaid escapes these QUOTED labels regardless of security
 * level (`'loose'` and `'strict'` both produce inert output for them), and the app's
 * own `escapeMermaid` / `sanitizeStateLabel` already strip quotes and angle brackets
 * before mermaid ever sees them — so these assertions would still pass with strict
 * relaxed. That is fine: this suite proves the OUTPUT is inert; the guard against
 * someone quietly relaxing the level is the explicit `securityLevel === 'strict'`
 * assertion in the (mocked) AgentRunGraph.test.tsx / ScenarioPanel.test.tsx. The two
 * cover different things and both are needed.
 *
 * The diagram sources are produced by the app's OWN builders (`toMermaid`,
 * `toScenarioMermaid`) — the identical functions the components feed to
 * `mermaid.render` — so the test tracks the app rather than a hand-copied sample.
 *
 * FAIL-CLOSED: there is deliberately NO environment guard, `skipIf`, `try/catch`,
 * or `assumeTrue` anywhere in this file. If mermaid is missing, fails to load, or
 * errors, the `await import('mermaid')` / `mermaid.render(...)` throws and the test
 * FAILS. A test that skips when the library is broken would recreate the exact
 * false-green this suite is here to remove.
 *
 * VERSION-AGNOSTIC: assertions target behaviour and structure (element counts,
 * class names mermaid has used across major versions, presence of label text and
 * the absence of active content) — never raw-SVG snapshots or version strings —
 * so the suite keeps passing across legitimate upgrades while still catching real
 * breakage.
 */

/**
 * jsdom implements the SVG DOM but NOT its layout/measurement primitives
 * (`getBBox`, `getComputedTextLength`), which mermaid needs to size nodes. This
 * provides plausible, text-length-derived boxes so mermaid's layout maths produce
 * finite geometry. This is NOT a mermaid mock — mermaid itself is the real,
 * installed library; we are only completing the DOM environment jsdom leaves
 * incomplete. The escaping/sanitising that makes the output inert happens at parse
 * time (mermaid's label handling plus its DOMPurify / @braintree/sanitize-url
 * integration) and is entirely independent of these measurements, so the
 * inert-output assertions below are exercised faithfully.
 */
beforeAll(() => {
  const proto = (globalThis as unknown as { SVGElement?: { prototype: Record<string, unknown> } })
    .SVGElement?.prototype;
  if (proto) {
    if (typeof proto.getBBox !== 'function') {
      proto.getBBox = function (this: { textContent?: string | null }) {
        const text = (this.textContent ?? '').length;
        return { x: 0, y: 0, width: Math.max(text * 8, 16), height: 18 };
      };
    }
    if (typeof proto.getComputedTextLength !== 'function') {
      proto.getComputedTextLength = function (this: { textContent?: string | null }) {
        return (this.textContent ?? '').length * 8;
      };
    }
  }
});

/** Import + initialise the real mermaid with the SAME config the components use. */
async function realMermaid(theme: 'default' | 'dark' = 'default') {
  const mermaid = (await import('mermaid')).default;
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme });
  return mermaid;
}

function parseSvg(svg: string): Document {
  const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
  // A malformed SVG would surface as a <parsererror> element rather than throw.
  expect(doc.querySelector('parsererror')).toBeNull();
  return doc;
}

/**
 * Assert the rendered SVG carries NO active/executable content. This is the
 * load-bearing sanitisation check: script elements, `on*` event-handler
 * attributes and `javascript:` URLs must all be gone. We inspect the parsed DOM
 * (attributes, not substrings) so harmless label TEXT that merely mentions
 * "onerror" or "javascript:" does not trip a false positive — only a real
 * event-handler attribute or javascript: URL value fails.
 */
function assertNoActiveContent(svg: string): void {
  // A literal `<script` tag can never be legitimate output — escaped user text
  // would appear as `&lt;script&gt;`, so this substring check is safe.
  expect(/<script[\s/>]/i.test(svg)).toBe(false);

  const doc = parseSvg(svg);
  expect(doc.querySelectorAll('script').length).toBe(0);

  const offendingOnAttrs: string[] = [];
  const offendingJsUrls: string[] = [];
  doc.querySelectorAll('*').forEach((el) => {
    for (const attr of Array.from(el.attributes)) {
      if (/^on/i.test(attr.name)) offendingOnAttrs.push(`${el.tagName}@${attr.name}`);
      if (/javascript:/i.test(attr.value)) offendingJsUrls.push(`${el.tagName}@${attr.name}`);
    }
  });
  expect(offendingOnAttrs).toEqual([]);
  expect(offendingJsUrls).toEqual([]);
}

// These import and drive the REAL mermaid bundle rather than a mock, which is the
// point of the file — but it is hundreds of kB, and under a full-suite run it loads
// while ~200 other test files compete for the same workers. The global 20s timeout
// (vitest.config.ts) is sized for ordinary tests and this one has timed out under
// that contention while passing in ~1s on its own. The timeout is raised rather
// than the load mocked: what is being asserted here is a RENDER CONTRACT against
// the installed library, not how fast it loads, so a clock is the wrong constraint
// and a mock would delete the only thing the file proves.
describe('mermaid render contract (real, unmocked installed library)', { timeout: 60000 }, () => {
  it('loads the real mermaid library and renders a genuine SVG (fail-closed)', async () => {
    const mermaid = await realMermaid();
    // These prove we have the real library, not a stand-in: an accidental mock
    // would not expose both callable API functions.
    expect(typeof mermaid.render).toBe('function');
    expect(typeof mermaid.initialize).toBe('function');

    const { svg } = await mermaid.render('contract-smoke', 'flowchart TD\n  a["hi"] --> b["there"]');
    const doc = parseSvg(svg);
    expect(doc.querySelector('svg')).not.toBeNull();
    // Structural, not prefix-only: the two nodes actually made it into the SVG.
    expect(doc.querySelectorAll('.node').length).toBe(2);
  });

  it('renders the AgentRunGraph flowchart source: node shapes, all edge kinds, labels', async () => {
    const mermaid = await realMermaid();
    // Exercises every feature the app's flowchart emits: rounded-rect message
    // nodes, a stadium TOOL_CALL node, and NEXT / INVOKES / RESULT edge labels.
    const graph: CallGraph = {
      nodes: [
        { id: 'm0', kind: 'USER', label: 'what is the weather in London?' },
        { id: 'm1', kind: 'ASSISTANT', label: 'let me check that for you' },
        { id: 't0', kind: 'TOOL_CALL', label: 'get_weather(location)' },
      ],
      edges: [
        { from: 'm0', to: 'm1', kind: 'NEXT' },
        { from: 'm1', to: 't0', kind: 'INVOKES' },
        { from: 't0', to: 'm1', kind: 'RESULT' },
      ],
    };
    const source = toMermaid(graph);
    expect(source.startsWith('flowchart TD')).toBe(true);

    const { svg } = await mermaid.render('contract-flowchart', source);
    const doc = parseSvg(svg);

    // Every node is laid out and present (not merely a valid <svg> wrapper).
    const nodes = Array.from(doc.querySelectorAll('.node'));
    expect(nodes.length).toBe(graph.nodes.length);
    // Each source node id appears in a rendered node's id (mermaid prefixes them).
    for (const node of graph.nodes) {
      expect(nodes.some((n) => n.id.includes(node.id))).toBe(true);
    }
    // Every edge is drawn.
    expect(doc.querySelectorAll('.edgePath, .flowchart-link').length).toBe(graph.edges.length);
    // Edge-kind labels survive into the diagram.
    for (const kind of ['NEXT', 'INVOKES', 'RESULT']) {
      expect(svg).toContain(kind);
    }
    // Node label text (including parens from a tool signature) is rendered.
    expect(svg).toContain('get_weather(location)');
    expect(svg).toContain('what is the weather in London?');
  });

  it('renders the ScenarioPanel stateDiagram source: states, transitions, current-state highlight', async () => {
    const mermaid = await realMermaid();
    const model = buildScenarioGraphModel(
      ['awaiting payment', 'paid', 'shipped'],
      [
        { from: 'awaiting payment', to: 'paid' },
        { from: 'paid', to: 'shipped' },
      ],
      'paid',
    );
    const source = toScenarioMermaid(model);
    expect(source.startsWith('stateDiagram-v2')).toBe(true);

    const { svg } = await mermaid.render('contract-state', source);
    const doc = parseSvg(svg);

    // Exactly one rendered node per observed state. The count is keyed off the
    // state LABEL TEXT, not a class name, so (a) the `[*]` initial marker — which
    // carries no state label — cannot inflate it, meaning a dropped/under-rendered
    // state brings the count below states.length and fails, and (b) a mermaid class
    // rename cannot make it silently pass (it would return 0 nodes and fail loudly).
    // A prior `>= (.statediagram-state, .node)` form was a no-op here: the marker
    // made the union N+1, so losing a state still satisfied `>= N`.
    const stateLabelNodes = Array.from(doc.querySelectorAll('.node, .statediagram-state')).filter(
      (node) => model.states.some((state) => (node.textContent ?? '').includes(state)),
    );
    expect(stateLabelNodes.length).toBe(model.states.length);
    // And each specific state's label is present in the SVG.
    for (const label of ['awaiting payment', 'paid', 'shipped']) {
      expect(svg).toContain(label);
    }
    // Transitions are drawn as edges (the 2 transitions + the [*] initial marker).
    expect(doc.querySelectorAll('.edge, .transition').length).toBeGreaterThanOrEqual(
      model.transitions.length,
    );
    // The live current-state highlight (classDef `current` + its fill) is applied.
    expect(svg).toContain('current');
    expect(svg).toContain('#1976d2');
  });

  it('renders the app diagrams in both light and dark themes', async () => {
    const graph: CallGraph = {
      nodes: [
        { id: 'm0', kind: 'USER', label: 'hello' },
        { id: 't0', kind: 'TOOL_CALL', label: 'lookup(x)' },
      ],
      edges: [{ from: 'm0', to: 't0', kind: 'INVOKES' }],
    };
    for (const theme of ['default', 'dark'] as const) {
      const mermaid = await realMermaid(theme);
      const { svg } = await mermaid.render(`contract-theme-${theme}`, toMermaid(graph));
      expect(parseSvg(svg).querySelectorAll('.node').length).toBe(2);
    }
  });

  describe('injected SVG is inert for hostile label content', () => {
    // NOTE: `<img src=...>` and `<svg onload=...>` payloads are intentionally
    // absent — they wedge *jsdom itself* (its handling of an <img> resource / an
    // <svg> element), which is a jsdom limitation, NOT mermaid behaviour, and has
    // nothing to do with whether the rendered SVG is inert. The `on*`-event-handler
    // and `javascript:`-URL paths are exercised in full below via tags that jsdom
    // renders without hanging (`<span onerror>`, `<p onload>`, `<div onclick>`,
    // `<a href=javascript:>`, `<script>`).
    const hostileLabels: Array<[string, string]> = [
      ['script tag', '<script>alert(1)</script>'],
      ['quote-break then script', '"><script>alert(document.cookie)</script>'],
      ['onerror handler', '<span onerror=alert(1)>x</span>'],
      ['onload handler', '<p onload=alert(1)>x</p>'],
      ['onclick handler', '<div onclick=alert(1)>x</div>'],
      ['onmouseover handler', '<b onmouseover=alert(1)>hi</b>'],
      ['javascript: url in href', '<a href="javascript:alert(1)">x</a>'],
      ['javascript: bare url', 'javascript:alert(1)'],
      ['angle brackets', '<b>bold</b> & <i>italic</i>'],
    ];

    it.each(hostileLabels)(
      'flowchart: neutralises %s fed through the real toMermaid source',
      async (_name, payload) => {
        const mermaid = await realMermaid();
        // Hostile content flows through the app's REAL source builder, exactly as
        // a malicious tool name or message would in production.
        const source = toMermaid({
          nodes: [
            { id: 'a', kind: 'USER', label: payload },
            { id: 'b', kind: 'TOOL_CALL', label: payload },
          ],
          edges: [{ from: 'a', to: 'b', kind: 'INVOKES' }],
        });
        const { svg } = await mermaid.render(`contract-xss-flow-${_name.replace(/\W+/g, '')}`, source);
        // Sanitisation must not have swallowed the whole render — the diagram
        // still exists...
        expect(svg).toContain('<svg');
        // ...but carries no executable content.
        assertNoActiveContent(svg);
      },
    );

    it.each(hostileLabels)(
      'stateDiagram: neutralises %s fed through the real toScenarioMermaid source',
      async (_name, payload) => {
        const mermaid = await realMermaid();
        const model = buildScenarioGraphModel(
          [payload, 'safe next state'],
          [{ from: payload, to: 'safe next state' }],
          payload,
        );
        const source = toScenarioMermaid(model);
        const { svg } = await mermaid.render(`contract-xss-state-${_name.replace(/\W+/g, '')}`, source);
        expect(svg).toContain('<svg');
        assertNoActiveContent(svg);
      },
    );
  });
});
