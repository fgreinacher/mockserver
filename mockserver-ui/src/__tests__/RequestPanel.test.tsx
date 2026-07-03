import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RequestPanel from '../components/RequestPanel';

describe('RequestPanel', () => {
  it('shows empty state when no items', () => {
    render(
      <RequestPanel title="Received Requests" items={[]} searchValue="" onSearchChange={() => {}} />,
    );
    expect(screen.getByText(/No requests/)).toBeInTheDocument();
  });

  it('offers an actionable, copyable curl one-liner (with host:port) in the empty state', () => {
    render(
      <RequestPanel title="Received Requests" items={[]} searchValue="" onSearchChange={() => {}} />,
    );
    const code = document.querySelector('code');
    // The example curl re-issues a proxied request against this MockServer's host:port.
    expect(code?.textContent).toMatch(/curl -x http:\/\/[\w.-]+:\d+ http:\/\/example\.com/);
    // A copy affordance sits alongside it (shared CopyButton renders a button).
    expect(document.querySelectorAll('button').length).toBeGreaterThan(0);
  });

  it('renders requests with reverse index based on filtered count', () => {
    const items = [
      { key: 'r1', value: { method: 'GET', path: '/first' } },
      { key: 'r2', value: { method: 'POST', path: '/second' } },
    ];

    render(
      <RequestPanel
        title="Received Requests"
        items={items}
        searchValue=""
        onSearchChange={() => {}}
      />,
    );

    const chip = document.querySelector('.MuiChip-label');
    expect(chip).toHaveTextContent('2');
  });

  it('filters by search term', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const items = [
      { key: 'r1', description: 'GET /api', value: { method: 'GET', path: '/api' } },
      { key: 'r2', description: 'POST /submit', value: { method: 'POST', path: '/submit' } },
    ];

    render(
      <RequestPanel
        title="Received"
        items={items}
        searchValue=""
        onSearchChange={onChange}
      />,
    );

    const searchInput = screen.getByLabelText('Search');
    await user.type(searchInput, 'POST');

    expect(onChange).toHaveBeenCalled();
  });

  it('shows title and count', () => {
    const items = [
      { key: 'r1', value: { path: '/test' } },
    ];

    render(
      <RequestPanel
        title="Proxied Requests"
        items={items}
        searchValue=""
        onSearchChange={() => {}}
      />,
    );

    expect(screen.getByText('Proxied Requests')).toBeInTheDocument();
    const chip = document.querySelector('.MuiChip-label');
    expect(chip).toHaveTextContent('1');
  });
});
