import { useEffect } from 'react';

interface ShortcutHandlers {
  onSearch: () => void;
  onClear: () => void;
  onToggleFilter: () => void;
  onShowShortcuts: () => void;
}

/** True when the event targets a text field / contenteditable, or an IME is composing. */
function isTypingContext(e: KeyboardEvent): boolean {
  if (e.isComposing) return true;
  const el = e.target as HTMLElement | null;
  if (!el || typeof el.tagName !== 'string') return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true;
}

export function useKeyboardShortcuts(handlers: ShortcutHandlers) {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey;

      // `?` (Shift+/) opens the keyboard-shortcuts help — the standard web convention.
      // Only when focus is not in a text field / contenteditable and no IME is composing.
      if (e.key === '?' && !mod) {
        if (isTypingContext(e)) return;
        e.preventDefault();
        handlers.onShowShortcuts();
        return;
      }

      // Never fire mod-key shortcuts while the user is typing in a field or composing
      // via an IME — the clear-logs shortcut asks for confirmation but must not trigger
      // from a stray keypress in an input.
      if (!mod) return;

      // Cmd/Ctrl+K → focus the log search field.
      if (e.key === 'k') {
        if (isTypingContext(e)) return;
        e.preventDefault();
        handlers.onSearch();
        return;
      }

      // Cmd/Ctrl+Shift+L → clear server logs (confirmed downstream). Moved off the
      // bare Cmd/Ctrl+L, which the browser reserves for focusing the address bar.
      if (e.shiftKey && (e.key === 'l' || e.key === 'L')) {
        if (isTypingContext(e)) return;
        e.preventDefault();
        handlers.onClear();
        return;
      }

      // Cmd/Ctrl+Shift+F → toggle the request-filter panel. Moved off Esc, which
      // universally means "dismiss" and caused surprise layout jumps.
      if (e.shiftKey && (e.key === 'f' || e.key === 'F')) {
        if (isTypingContext(e)) return;
        e.preventDefault();
        handlers.onToggleFilter();
        return;
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handlers]);
}
