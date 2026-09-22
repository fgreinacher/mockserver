import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

// Coverage for the console-order fixes on the Traffic (Observe) list. The panels
// were made stable by following the BOTTOM (newest appended at the tail) with an
// explicit Follow control; Traffic derived `follow` from scroll and reversed into
// console order but never wrote scrollTop, so in the default following state it
// sat parked at the OLDEST row while the top evicted — the original bug. These
// pin the WRITER (tail pin), the visible Follow control, and the timestamp column.

function req(key: string, method: string, path: string, timestamp?: string): JsonListItem {
  return { key, timestamp, value: { httpRequest: { method, path } } };
}

function renderTraffic() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

describe('Traffic console-order following', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
      autoScroll: true,
    });
  });

  it('pins the scroll container to the tail on a live push while following', () => {
    // Two rows, following (autoScroll true). Note the store is newest-first, so
    // this reverses to console order [older, newer] with the newest at the BOTTOM.
    useDashboardStore.setState({
      recordedRequests: [req('r2', 'GET', '/second'), req('r1', 'GET', '/first')],
    });
    renderTraffic();

    const region = screen.getByTestId('traffic-scroll-region');
    // jsdom computes no layout, so give the container a scrollable shape
    // explicitly — otherwise scrollHeight is 0 and the assertion is vacuous
    // (0 === 0 passes with or without the tail pin).
    Object.defineProperty(region, 'scrollHeight', { value: 5000, configurable: true });
    Object.defineProperty(region, 'clientHeight', { value: 500, configurable: true });
    region.scrollTop = 0; // parked at the top: the OLDEST row in console order

    // A live push: a newer request arrives (prepended in the newest-first store).
    act(() => {
      useDashboardStore.setState({
        recordedRequests: [
          req('r3', 'GET', '/third'),
          req('r2', 'GET', '/second'),
          req('r1', 'GET', '/first'),
        ],
      });
    });

    // The tail pin must have driven scrollTop to the bottom, where the newest row
    // is. Without the useLayoutEffect nothing writes scrollTop and it stays 0.
    expect(region.scrollTop).toBe(5000);
  });

  it('does NOT move the scroll container on a push when not following', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      recordedRequests: [req('r2', 'GET', '/second'), req('r1', 'GET', '/first')],
    });
    renderTraffic();

    // Turn following off via the visible control (proves the chip toggles it too).
    await user.click(screen.getByText('Following'));
    expect(screen.getByText('Follow')).toBeInTheDocument();

    const region = screen.getByTestId('traffic-scroll-region');
    Object.defineProperty(region, 'scrollHeight', { value: 5000, configurable: true });
    Object.defineProperty(region, 'clientHeight', { value: 500, configurable: true });
    region.scrollTop = 500; // reading, scrolled away from the tail

    act(() => {
      useDashboardStore.setState({
        recordedRequests: [
          req('r3', 'GET', '/third'),
          req('r2', 'GET', '/second'),
          req('r1', 'GET', '/first'),
        ],
      });
    });

    // Untouched: the reader is reading, nothing is pinned. This is the whole
    // point of the console model.
    expect(region.scrollTop).toBe(500);
  });

  it('renders a Follow control (chip) in the toolbar', () => {
    useDashboardStore.setState({
      recordedRequests: [req('r1', 'GET', '/first')],
    });
    renderTraffic();
    // Lit while following (autoScroll defaults true).
    expect(screen.getByText('Following')).toBeInTheDocument();
  });

  it('shows the entry timestamp, not a live-window ordinal', () => {
    useDashboardStore.setState({
      recordedRequests: [req('r1', 'GET', '/somePath', '2026-09-22 10:57:18.123')],
    });
    renderTraffic();
    // The time-of-day extract lines up with the Log Messages panel. The broken
    // code rendered the row ordinal here instead, so this text was never present.
    expect(screen.getByText('10:57:18.123')).toBeInTheDocument();
  });

  it('keys the compare checkbox aria-label on request identity, not the ordinal', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      recordedRequests: [req('r1', 'GET', '/somePath', '2026-09-22 10:57:18.123')],
    });
    renderTraffic();

    await user.click(screen.getByLabelText('Compare requests'));
    // Identity-keyed (method + path), so the label is stable across live-window
    // renumbering — not "Select request 1 to compare".
    expect(screen.getByLabelText('Select GET /somePath to compare')).toBeInTheDocument();
  });
});
