import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, renderHook, screen } from '@testing-library/react';
import { useFollow } from '../hooks/useFollow';
import { useDashboardStore } from '../store';
import LogPanel from '../components/LogPanel';
import RequestPanel from '../components/RequestPanel';

/**
 * The toolbar's follow control is a MASTER SWITCH over the panels, not a separate
 * mechanism. It went dead once before — the panels stopped reading the store value
 * during the console redesign, so the button toggled its own icon and did nothing
 * else — which is why these assert the wiring end to end rather than just the
 * store field.
 */
describe('useFollow — the toolbar control drives every panel', () => {
  beforeEach(() => {
    useDashboardStore.setState({ autoScroll: true });
  });

  it('starts following when the global control is on', () => {
    const { result } = renderHook(() => useFollow());
    expect(result.current[0]).toBe(true);
  });

  it('stops a following panel when the global control is switched off', () => {
    const { result } = renderHook(() => useFollow());
    expect(result.current[0]).toBe(true);

    act(() => { useDashboardStore.getState().setAutoScroll(false); });
    expect(result.current[0]).toBe(false);
  });

  it('resumes a paused panel when the global control is switched back on', () => {
    const { result } = renderHook(() => useFollow());
    act(() => { result.current[1](false); });   // panel paused on its own
    expect(result.current[0]).toBe(false);

    act(() => { useDashboardStore.getState().setAutoScroll(false); });
    act(() => { useDashboardStore.getState().setAutoScroll(true); });
    expect(result.current[0]).toBe(true);
  });

  it('lets a panel diverge from the global control until it next moves', () => {
    const { result } = renderHook(() => useFollow());

    // The reader stops this one panel; the others keep following.
    act(() => { result.current[1](false); });
    expect(result.current[0]).toBe(false);
    expect(useDashboardStore.getState().autoScroll).toBe(true);

    // The global control moving re-syncs it.
    act(() => { useDashboardStore.getState().setAutoScroll(false); });
    expect(result.current[0]).toBe(false);
    act(() => { useDashboardStore.getState().setAutoScroll(true); });
    expect(result.current[0]).toBe(true);
  });
});

/**
 * The hook tests above pass even if a panel never calls the hook, so these assert
 * the wiring itself: the panel's own Follow chip must answer to the toolbar.
 */
describe('the panels are actually wired to the master switch', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      autoScroll: true,
      logMessages: [],
      logSearch: '',
      logShowForwarded: true,
      recordedRequests: [],
      proxiedRequests: [],
    });
  });

  it('LogPanel follows or pauses with the toolbar control', () => {
    render(<LogPanel />);
    expect(screen.getByText('Following')).toBeInTheDocument();

    act(() => { useDashboardStore.getState().setAutoScroll(false); });
    expect(screen.getByText('Follow')).toBeInTheDocument();

    act(() => { useDashboardStore.getState().setAutoScroll(true); });
    expect(screen.getByText('Following')).toBeInTheDocument();
  });

  it('RequestPanel follows or pauses with the toolbar control', () => {
    render(
      <RequestPanel title="Received Requests" items={[]} searchValue="" onSearchChange={() => {}} />,
    );
    expect(screen.getByText('Following')).toBeInTheDocument();

    act(() => { useDashboardStore.getState().setAutoScroll(false); });
    expect(screen.getByText('Follow')).toBeInTheDocument();
  });
});
