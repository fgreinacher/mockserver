import { describe, it, expect, beforeEach, afterAll } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import Panel from '../components/Panel';
import RequestPanel from '../components/RequestPanel';
import LogPanel from '../components/LogPanel';
import { useDashboardStore } from '../store';

// Regression coverage for the "opened dashboard item closes as soon as new data
// arrives" report on the Log Messages / Received Requests panels.
//
// Two independent things are pinned here:
//
//  1. EXPANSION STATE is held ABOVE the list and keyed by each row's stable id,
//     so it survives a live push (re-render) — proven with windowing active.
//     This was already correct; these are guards against a regression back to
//     row-local or index-keyed state.
//
//  2. AUTO-SCROLL is TAIL-FOLLOWING: a push only snaps the panel back to the top
//     when the user is already at the top. If they have scrolled down to open a
//     row, the push must leave their scroll position alone — otherwise every
//     ~1/sec push scrolls that row out of view (and virtualization unmounts it),
//     which is exactly the "opened item closes" the user saw. This is the fix.

const realGetComputedStyle = globalThis.getComputedStyle;
let offsetHeightPatched = false;

// Give jsdom just enough layout for ProgressiveList to resolve a scroll ancestor
// with a real height, so its WINDOWED branch runs (matching the real browser),
// rather than its "no usable viewport" fallback that renders every row.
function enableLayout(): void {
  if (!offsetHeightPatched) {
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() {
        return 600;
      },
    });
    offsetHeightPatched = true;
  }
  globalThis.getComputedStyle = ((element: Element, pseudo?: string | null) => {
    const style = realGetComputedStyle(element, pseudo ?? undefined);
    if ((element as HTMLElement).dataset?.['scrollhost'] !== undefined) {
      return { ...style, overflowY: 'auto' } as CSSStyleDeclaration;
    }
    return style;
  }) as typeof globalThis.getComputedStyle;
}

afterAll(() => {
  globalThis.getComputedStyle = realGetComputedStyle;
  if (offsetHeightPatched) {
    delete (HTMLElement.prototype as unknown as Record<string, unknown>)['offsetHeight'];
  }
});

describe('dashboard row expansion survives a live update (windowed)', () => {
  beforeEach(() => {
    useDashboardStore.getState().clearUI();
    useDashboardStore.setState({
      logMessages: [],
      logSearch: '',
      autoScroll: true,
      logShowForwarded: true,
    });
  });

  it('RequestPanel keeps an expanded row open when a new request is prepended', async () => {
    enableLayout();
    const user = userEvent.setup();
    const items = [{ key: 'r1', value: { method: 'GET', path: '/first' } }];

    const { rerender } = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
          <RequestPanel title="Received Requests" items={items} searchValue="" onSearchChange={() => {}} />
        </div>
      </ThemeProvider>,
    );

    await user.click(screen.getByLabelText('Expand'));
    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();

    const updated = [
      { key: 'r2', value: { method: 'POST', path: '/second' } },
      { key: 'r1', value: { method: 'GET', path: '/first' } },
    ];
    rerender(
      <ThemeProvider theme={buildTheme('dark')}>
        <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
          <RequestPanel title="Received Requests" items={updated} searchValue="" onSearchChange={() => {}} />
        </div>
      </ThemeProvider>,
    );

    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();
  });

  it('LogPanel keeps an expanded entry open when a new log message is prepended', async () => {
    enableLayout();
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'm1', value: 'first log entry body' }] } },
      ],
    });

    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
          <LogPanel />
        </div>
      </ThemeProvider>,
    );

    await user.click(screen.getByLabelText('Expand'));
    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();

    act(() => {
      useDashboardStore.getState().applyMessage({
        logMessages: [
          { key: 'log2', value: { messageParts: [{ key: 'm2', value: 'second log entry body' }] } },
          { key: 'log1', value: { messageParts: [{ key: 'm1', value: 'first log entry body' }] } },
        ],
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
      });
    });

    expect(screen.getByLabelText('Collapse')).toBeInTheDocument();
  });
});

describe('Panel auto-scroll is tail-following', () => {
  beforeEach(() => {
    useDashboardStore.setState({ autoScroll: true });
  });

  function renderPanel(count: number) {
    return render(
      <Panel title="Log Messages" count={count} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
  }

  // NOTE this one is a positive guard, not a differential one: it passes with the OLD
  // unconditional-yank implementation too. It is kept because tail-following MUST still
  // work for the common case, but the tests that actually distinguish the fix are the two
  // below (no yank when scrolled down, and resuming once back at the top).
  it('snaps to the top on new data when the user is already at the top', () => {
    const { rerender } = renderPanel(10);
    const region = screen.getByRole('log');
    // The user is parked at the top.
    region.scrollTop = 0;
    fireEvent.scroll(region);

    // A live push arrives (count grows). Tail-following should keep them at top.
    region.scrollTop = 40; // simulate content shift; the effect should reset it
    rerender(
      <Panel title="Log Messages" count={11} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    expect(region.scrollTop).toBe(0);
  });

  it('never scrolls on new data when auto-scroll is switched off', () => {
    // autoScroll is a user-accessible toggle. With it off, a live push must leave the
    // viewport exactly where it is - whether the user is at the top or scrolled away.
    useDashboardStore.setState({ autoScroll: false });

    const { rerender } = renderPanel(10);
    const region = screen.getByRole('log');
    region.scrollTop = 900;
    fireEvent.scroll(region);

    rerender(
      <Panel title="Log Messages" count={11} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    expect(region.scrollTop).toBe(900);

    // And at the top it must not re-assert scrollTop either - nothing should touch it.
    region.scrollTop = 0;
    fireEvent.scroll(region);
    region.scrollTop = 40;
    rerender(
      <Panel title="Log Messages" count={12} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    expect(region.scrollTop).toBe(40);
  });

  it('does NOT yank the viewport back to the top when the user has scrolled down', () => {
    const { rerender } = renderPanel(10);
    const region = screen.getByRole('log');
    // The user scrolled down to inspect / open a row.
    region.scrollTop = 500;
    fireEvent.scroll(region);

    // A live push arrives (count grows). The opened row must stay put: the panel
    // must NOT reset the scroll to the top.
    rerender(
      <Panel title="Log Messages" count={11} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    expect(region.scrollTop).toBe(500);
  });

  it('resumes tail-following once the user scrolls back to the top', () => {
    const { rerender } = renderPanel(10);
    const region = screen.getByRole('log');

    region.scrollTop = 500;
    fireEvent.scroll(region);
    rerender(
      <Panel title="Log Messages" count={11} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    expect(region.scrollTop).toBe(500); // stayed put while scrolled down

    // User scrolls back to the top.
    region.scrollTop = 0;
    fireEvent.scroll(region);
    region.scrollTop = 40;
    rerender(
      <Panel title="Log Messages" count={12} searchValue="" onSearchChange={() => {}} liveRegion>
        <div style={{ height: 5000 }}>rows</div>
      </Panel>,
    );
    expect(region.scrollTop).toBe(0); // tail-following resumed
  });
});
