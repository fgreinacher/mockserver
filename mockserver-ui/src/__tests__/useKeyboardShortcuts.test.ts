import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useKeyboardShortcuts } from '../hooks/useKeyboardShortcuts';

function makeHandlers() {
  return {
    onSearch: vi.fn(),
    onClear: vi.fn(),
    onToggleFilter: vi.fn(),
    onShowShortcuts: vi.fn(),
  };
}

function fireKey(key: string, options: Partial<KeyboardEvent> = {}) {
  window.dispatchEvent(
    new KeyboardEvent('keydown', { key, bubbles: true, ...options }),
  );
}

describe('useKeyboardShortcuts', () => {
  it('calls onSearch on Ctrl+K', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('k', { ctrlKey: true });
    expect(handlers.onSearch).toHaveBeenCalledOnce();
    expect(handlers.onClear).not.toHaveBeenCalled();
  });

  it('calls onSearch on Meta+K (macOS)', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('k', { metaKey: true });
    expect(handlers.onSearch).toHaveBeenCalledOnce();
  });

  it('calls onClear on Ctrl+Shift+L', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('L', { ctrlKey: true, shiftKey: true });
    expect(handlers.onClear).toHaveBeenCalledOnce();
  });

  it('does NOT clear on bare Ctrl+L (left for the browser address bar)', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('l', { ctrlKey: true });
    expect(handlers.onClear).not.toHaveBeenCalled();
  });

  it('calls onToggleFilter on Ctrl+Shift+F', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('F', { ctrlKey: true, shiftKey: true });
    expect(handlers.onToggleFilter).toHaveBeenCalledOnce();
  });

  it('does NOT toggle the filter on Escape (Esc is reserved for dismiss)', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('Escape');
    expect(handlers.onToggleFilter).not.toHaveBeenCalled();
  });

  it('opens the shortcuts help on ? (no modifier)', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('?', { shiftKey: true });
    expect(handlers.onShowShortcuts).toHaveBeenCalledOnce();
  });

  it('does not open the shortcuts help when ? is typed in an input', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true }));
    expect(handlers.onShowShortcuts).not.toHaveBeenCalled();
    document.body.removeChild(input);
  });

  it('does not call handlers for unrelated keys', () => {
    const handlers = makeHandlers();
    renderHook(() => useKeyboardShortcuts(handlers));

    fireKey('a');
    fireKey('Enter');
    fireKey('k'); // no modifier
    expect(handlers.onSearch).not.toHaveBeenCalled();
    expect(handlers.onClear).not.toHaveBeenCalled();
    expect(handlers.onToggleFilter).not.toHaveBeenCalled();
    expect(handlers.onShowShortcuts).not.toHaveBeenCalled();
  });
});
