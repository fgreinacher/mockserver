import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';
import { createElement } from 'react';
import { ensureWebStorage } from './test-setup-storage';


// jsdom 30.1.0 changed its focus fixup: when a focused element is removed from
// the DOM, `Node-impl._removingSteps()` now sets the document's
// `_lastFocusedElement` to the *Document itself* (previously it was cleared to
// null) so that `activeElement`/`hasFocus()` keep resolving to the viewport. The
// next `.focus()` then reads that stale value as `previous` and fires the
// `focus`/`focusin` events with `relatedTarget` set to the Document. Real
// browsers use `null` there, never the Document node.
//
// MUI's FocusTrap records `nodeToRestore.current = event.relatedTarget` on the
// sentinel's focus and, on unmount, calls `nodeToRestore.current.focus()` to
// restore focus. A Document has no `focus()` method, so this throws
// "nodeToRestore.current.focus is not a function" and takes out every test that
// renders a Dialog (109 across the *Dialog suites) despite none of them
// asserting anything focus-related.
//
// Normalise a Document `relatedTarget` back to null inside jsdom's own focus
// dispatch helper, restoring the pre-30.1.0 (and real-browser) semantics while
// leaving the intentional `activeElement`/`hasFocus()` fix untouched. The
// wrapper only ever rewrites a nodeType-9 relatedTarget, so it neutralises
// itself if jsdom stops emitting the Document there; delete it then, with this
// comment. Reached by path import rather than node:module so the file stays free
// of node typings, which the UI tsconfig does not carry.
type FocusEventFirer = ((...args: unknown[]) => unknown) & { patched?: boolean };

async function guardJsdomFocusRelatedTargetDocument(): Promise<void> {
  try {
    const specifier = 'jsdom/lib/jsdom/living/helpers/focusing.js';
    const imported = (await import(/* @vite-ignore */ specifier)) as {
      default?: { fireFocusEventWithTargetAdjustment?: FocusEventFirer };
      fireFocusEventWithTargetAdjustment?: FocusEventFirer;
    };
    // A CommonJS module reached through ESM exposes module.exports as .default;
    // that is the same object jsdom's own callers reach through `focusing.`, so
    // reassigning the property takes effect for the live document.
    const focusing = imported.default ?? imported;
    const original = focusing.fireFocusEventWithTargetAdjustment;
    if (typeof original !== 'function' || original.patched) {
      return;
    }
    const guarded: FocusEventFirer = function (this: unknown, ...args: unknown[]): unknown {
      // Signature: (name, target, relatedTarget, options?). Only the focus/
      // focusin calls ever pass a Document as relatedTarget; blur/focusout pass
      // an element or null, so this never rewrites those.
      const relatedTarget = args[2] as { nodeType?: number } | null | undefined;
      if (relatedTarget && relatedTarget.nodeType === 9) {
        args[2] = null;
      }
      return original.apply(this, args);
    };
    guarded.patched = true;
    focusing.fireFocusEventWithTargetAdjustment = guarded;
  } catch {
    // jsdom's internals are not a public API; if the path moves there is
    // nothing to guard and the suite should still run.
  }
}
await guardJsdomFocusRelatedTargetDocument();

// jsdom does not always expose localStorage/sessionStorage (origin- and
// build-dependent). Guarantee a working Storage so suites that clear/read it in
// beforeEach are deterministic regardless of node_modules/jsdom install state.
ensureWebStorage();

// Monaco editor cannot run in jsdom (it needs real layout + clipboard/worker
// APIs). Globally replace the @monaco-editor/react wrapper (and the bundled
// monaco module + its ?worker imports) with lightweight stand-ins so any
// component that embeds a code editor (e.g. the Composer body matcher) renders
// in tests. Individual test files can still override this with their own
// vi.mock to assert editor-specific behaviour.
vi.mock('@monaco-editor/react', () => ({
  loader: { config: vi.fn() },
  default: ({
    value,
    language,
    onChange,
    onMount,
  }: {
    value?: string;
    language?: string;
    onChange?: (value: string | undefined) => void;
    onMount?: (editor: unknown, monaco: unknown) => void;
  }) => {
    onMount?.({}, { languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } } });
    return createElement('textarea', {
      'data-testid': 'monaco-textarea',
      'data-language': language,
      value: value ?? '',
      onChange: (e: { target: { value: string } }) => onChange?.(e.target.value),
    });
  },
  // The DiffEditor cannot run in jsdom either; render both panes as read-only
  // text areas so the JsonDiffViewer (capture/composer preview-diff) is testable.
  DiffEditor: ({
    original,
    modified,
    language,
  }: {
    original?: string;
    modified?: string;
    language?: string;
  }) =>
    createElement('div', { 'data-testid': 'monaco-diff', 'data-language': language }, [
      createElement('textarea', {
        key: 'original',
        'data-testid': 'monaco-diff-original',
        readOnly: true,
        value: original ?? '',
      }),
      createElement('textarea', {
        key: 'modified',
        'data-testid': 'monaco-diff-modified',
        readOnly: true,
        value: modified ?? '',
      }),
    ]),
}));

vi.mock('monaco-editor', () => ({
  MarkerSeverity: { Hint: 1, Info: 2, Warning: 4, Error: 8 },
  languages: { json: { jsonDefaults: { diagnosticsOptions: { schemas: [] }, setDiagnosticsOptions: vi.fn() } } },
}));

vi.mock('monaco-editor/editor/editor.worker?worker', () => ({ default: class {} }));
vi.mock('monaco-editor/language/json/json.worker?worker', () => ({ default: class {} }));

// jsdom does not implement ResizeObserver, which @mui/x-charts (and other
// responsive components) rely on. Provide a no-op so charts can render in tests.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserverStub {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  } as unknown as typeof ResizeObserver;
}
