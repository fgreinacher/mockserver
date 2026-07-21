import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LogPanel from '../components/LogPanel';
import { useDashboardStore } from '../store';

describe('LogPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      logMessages: [],
      logSearch: '',
      autoScroll: true,
      logShowForwarded: true,
    });
  });

  it('shows empty state when no log messages', () => {
    render(<LogPanel />);
    expect(screen.getByText(/No log messages/)).toBeInTheDocument();
  });

  it('renders log entries', () => {
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg', value: 'test log entry' }] } },
      ],
    });
    render(<LogPanel />);
    expect(screen.getByText('test log entry')).toBeInTheDocument();
  });

  it('filters log messages by search term', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg1', value: 'error occurred' }] } },
        { key: 'log2', value: { messageParts: [{ key: 'msg2', value: 'request received' }] } },
      ],
    });

    render(<LogPanel />);

    const searchInput = screen.getByLabelText('Search');
    await user.type(searchInput, 'error');

    expect(screen.getByText('error occurred')).toBeInTheDocument();
    expect(screen.queryByText('request received')).not.toBeInTheDocument();
  });

  it('shows "no matching" message when search matches nothing', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg1', value: 'test entry' }] } },
      ],
    });

    render(<LogPanel />);
    const searchInput = screen.getByLabelText('Search');
    await user.type(searchInput, 'xyz-nonexistent');

    expect(screen.getByText('No matching log messages')).toBeInTheDocument();
  });

  it('explains an operator it cannot satisfy instead of silently showing nothing', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg1', value: 'error occurred' }] } },
      ],
    });

    render(<LogPanel />);
    // Log rows have no request/response, so `status:` can never match. The panel
    // must say so rather than leaving a bare "No matching log messages".
    await user.type(screen.getByLabelText('Search'), 'status:>=400 error');

    expect(screen.getByText(/No field operators are supported here/)).toBeInTheDocument();
    expect(screen.getByLabelText('Search')).toHaveAttribute('aria-invalid', 'true');
  });

  it('does not advertise field operators it cannot satisfy', () => {
    render(<LogPanel />);
    const placeholder = screen.getByLabelText('Search').getAttribute('placeholder') ?? '';
    expect(placeholder).toContain('/regex/');
    for (const operator of ['status:', 'method:', 'path:', 'host:', 'operation:']) {
      expect(placeholder).not.toContain(operator);
    }
  });

  it('hides forwarded request entries when "Show forwarded" is off', () => {
    useDashboardStore.setState({
      logShowForwarded: false,
      logMessages: [
        {
          key: 'fwd',
          value: {
            style: { color: 'rgb(152, 208, 255)' },
            messageParts: [{ key: 'm', value: 'forwarded entry' }],
          },
        },
        { key: 'normal', value: { messageParts: [{ key: 'm', value: 'received entry' }] } },
      ],
    });

    render(<LogPanel />);
    expect(screen.queryByText('forwarded entry')).not.toBeInTheDocument();
    expect(screen.getByText('received entry')).toBeInTheDocument();
  });

  it('shows forwarded entries when "Show forwarded" is on', () => {
    useDashboardStore.setState({
      logShowForwarded: true,
      logMessages: [
        {
          key: 'fwd',
          value: {
            style: { color: 'rgb(152, 208, 255)' },
            messageParts: [{ key: 'm', value: 'forwarded entry' }],
          },
        },
      ],
    });

    render(<LogPanel />);
    expect(screen.getByText('forwarded entry')).toBeInTheDocument();
  });

  it('renders log groups', () => {
    useDashboardStore.setState({
      logMessages: [
        {
          key: 'group1_log_group',
          group: {
            key: 'group1_summary',
            value: { messageParts: [{ key: 'summary', value: 'group summary' }] },
          },
          value: [
            { key: 'child1', value: { messageParts: [{ key: 'c1', value: 'child entry' }] } },
          ],
        },
      ],
    });

    render(<LogPanel />);
    expect(screen.getByText('group summary')).toBeInTheDocument();
  });
});
