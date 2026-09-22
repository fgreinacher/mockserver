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

  // Console order: the store's arrays are newest-first, and the panel renders them
  // oldest-first so new rows append at the BOTTOM. An arriving row then lands
  // below the viewport and nothing being read moves.
  it('renders oldest first, so the newest is at the bottom', () => {
    const items = [
      // Newest first, as the store holds them. `id` is rendered by the row, so it
      // is what the assertion can see.
      { key: 'r2', value: { id: 'SECOND', method: 'POST', path: '/second' } },
      { key: 'r1', value: { id: 'FIRST', method: 'GET', path: '/first' } },
    ];

    render(
      <RequestPanel
        title="Received Requests"
        items={items}
        searchValue=""
        onSearchChange={() => {}}
      />,
    );

    const text = document.body.textContent ?? '';
    expect(text).toContain('FIRST');
    expect(text).toContain('SECOND');
    // The OLDEST (r1/FIRST) renders above the newest, so arrivals append below.
    expect(text.indexOf('FIRST')).toBeLessThan(text.indexOf('SECOND'));
  });

  // No count chip: the server sends a capped window, so items.length pins at the
  // cap and silently stops being a count.
  it('shows no count, because the feed is a capped window', () => {
    const items = [{ key: 'r1', value: { method: 'GET', path: '/first' } }];

    render(
      <RequestPanel
        title="Received Requests"
        items={items}
        searchValue=""
        onSearchChange={() => {}}
      />,
    );

    expect(screen.getByText('Received Requests')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\b1 \/ 1\b/);
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

  it('shows its title', () => {
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
  });
});
