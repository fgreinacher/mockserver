import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ExpectationPanel, { duplicateValueWithoutId } from '../components/ExpectationPanel';
import { useDashboardStore } from '../store';
import * as expectationsLib from '../lib/expectations';

describe('ExpectationPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      activeExpectations: [],
      expectationSearch: '',
      notification: null,
      pendingEditExpectation: null,
      view: 'dashboard',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows empty state when no expectations', () => {
    render(<ExpectationPanel />);
    expect(screen.getByText(/No active expectations/)).toBeInTheDocument();
  });

  it('renders expectations with descriptions', () => {
    useDashboardStore.setState({
      activeExpectations: [
        {
          key: 'exp1',
          description: 'GET /api/users',
          value: { httpRequest: { method: 'GET', path: '/api/users' } },
        },
      ],
    });

    render(<ExpectationPanel />);
    // After the layout refactor the method and path render in separate
    // elements so the path can be right-aligned while the method stays left.
    expect(screen.getByText('GET')).toBeInTheDocument();
    expect(screen.getByText('/api/users')).toBeInTheDocument();
  });

  it('filters expectations by search', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'exp1', description: 'GET /users', value: { httpRequest: { method: 'GET', path: '/users' } } },
        { key: 'exp2', description: 'POST /orders', value: { httpRequest: { method: 'POST', path: '/orders' } } },
      ],
    });

    render(<ExpectationPanel />);
    const searchInput = screen.getByLabelText('Search');
    await user.type(searchInput, 'orders');

    expect(screen.queryByText('/users')).not.toBeInTheDocument();
    expect(screen.getByText('/orders')).toBeInTheDocument();
  });

  it('shows count badge with correct number', () => {
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'exp1', value: {} },
        { key: 'exp2', value: {} },
        { key: 'exp3', value: {} },
      ],
    });

    render(<ExpectationPanel />);
    const chip = document.querySelector('.MuiChip-label');
    expect(chip).toHaveTextContent('3');
  });

  it('Edit loads the expectation into the Composer and switches view', async () => {
    const user = userEvent.setup();
    const value = { id: 'exp-edit', httpRequest: { method: 'GET', path: '/edit' }, httpResponse: { statusCode: 200 } };
    useDashboardStore.setState({ activeExpectations: [{ key: 'exp-edit', value }] });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Edit expectation'));

    const state = useDashboardStore.getState();
    expect(state.pendingEditExpectation).toEqual(value);
    expect(state.view).toBe('composer');
  });

  it('Duplicate loads an id-stripped copy into the Composer, preserving priority', async () => {
    const user = userEvent.setup();
    const value = {
      id: 'exp-dup',
      priority: 7,
      httpRequest: { method: 'GET', path: '/dup' },
      httpResponse: { statusCode: 201 },
    };
    useDashboardStore.setState({ activeExpectations: [{ key: 'exp-dup', value }] });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Duplicate expectation'));

    const state = useDashboardStore.getState();
    expect(state.view).toBe('composer');
    // The id is stripped so saving creates a NEW expectation...
    expect(state.pendingEditExpectation).not.toHaveProperty('id');
    // ...but priority and the rest of the expectation are preserved.
    expect(state.pendingEditExpectation).toMatchObject({
      priority: 7,
      httpRequest: { method: 'GET', path: '/dup' },
      httpResponse: { statusCode: 201 },
    });
    // The original row in the store is untouched (deep copy, not a mutation).
    expect(useDashboardStore.getState().activeExpectations[0]!.value).toHaveProperty('id', 'exp-dup');
  });

  it('duplicateValueWithoutId strips the id without mutating the source', () => {
    const src = { id: 'x', priority: 3, httpRequest: { path: '/a' } };
    const dup = duplicateValueWithoutId(src);
    expect(dup).not.toHaveProperty('id');
    expect(dup).toMatchObject({ priority: 3, httpRequest: { path: '/a' } });
    // Source untouched and the nested object is a copy, not shared.
    expect(src).toHaveProperty('id', 'x');
    expect(dup['httpRequest']).not.toBe(src.httpRequest);
  });

  it('sorts rows by priority (descending) when the Priority toggle is on', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'low', value: { id: 'low', priority: 1, httpRequest: { method: 'GET', path: '/low' } } },
        { key: 'high', value: { id: 'high', priority: 50, httpRequest: { method: 'GET', path: '/high' } } },
        { key: 'mid', value: { id: 'mid', priority: 10, httpRequest: { method: 'GET', path: '/mid' } } },
      ],
    });

    render(<ExpectationPanel />);

    // Natural (insertion) order before sorting: low, high, mid.
    const pathsBefore = screen.getAllByText(/^\/(low|high|mid)$/).map((el) => el.textContent);
    expect(pathsBefore).toEqual(['/low', '/high', '/mid']);

    await user.click(screen.getByLabelText('Sort by priority'));

    // Descending by priority: high (50), mid (10), low (1).
    const pathsAfter = screen.getAllByText(/^\/(low|high|mid)$/).map((el) => el.textContent);
    expect(pathsAfter).toEqual(['/high', '/mid', '/low']);
  });

  it('Delete asks for confirmation, calls deleteExpectation, drops the row and notifies', async () => {
    const user = userEvent.setup();
    const del = vi.spyOn(expectationsLib, 'deleteExpectation').mockResolvedValue(undefined);
    const value = { id: 'exp-del', httpRequest: { method: 'GET', path: '/del' } };
    useDashboardStore.setState({ activeExpectations: [{ key: 'exp-del', value }] });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Delete expectation'));

    // Confirmation dialog appears; the lib must not be called until confirmed.
    expect(screen.getByText('Delete this expectation?')).toBeInTheDocument();
    expect(del).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(del).toHaveBeenCalledTimes(1));
    expect(del.mock.calls[0]![1]).toBe('exp-del');

    await waitFor(() => {
      expect(useDashboardStore.getState().activeExpectations).toHaveLength(0);
    });
    const note = useDashboardStore.getState().notification;
    expect(note).toMatchObject({ severity: 'success' });
    expect(note!.message).toContain('exp-del');
  });

  it('Delete can be cancelled without calling the server', async () => {
    const user = userEvent.setup();
    const del = vi.spyOn(expectationsLib, 'deleteExpectation').mockResolvedValue(undefined);
    useDashboardStore.setState({
      activeExpectations: [{ key: 'exp-keep', value: { id: 'exp-keep', httpRequest: { method: 'GET', path: '/keep' } } }],
    });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Delete expectation'));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(del).not.toHaveBeenCalled();
    expect(useDashboardStore.getState().activeExpectations).toHaveLength(1);
  });

  it('surfaces a humanized error notification when the delete fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(expectationsLib, 'deleteExpectation').mockRejectedValue(new Error('boom'));
    useDashboardStore.setState({
      activeExpectations: [{ key: 'exp-err', value: { id: 'exp-err', httpRequest: { method: 'GET', path: '/err' } } }],
    });

    render(<ExpectationPanel />);
    await user.click(screen.getByLabelText('Delete expectation'));
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => {
      expect(useDashboardStore.getState().notification).toMatchObject({ severity: 'error' });
    });
    // The row is kept on failure.
    expect(useDashboardStore.getState().activeExpectations).toHaveLength(1);
  });

  describe('bulk select + delete', () => {
    const twoExpectations = () => [
      { key: 'exp-a', value: { id: 'exp-a', httpRequest: { method: 'GET', path: '/a' } } },
      { key: 'exp-b', value: { id: 'exp-b', httpRequest: { method: 'GET', path: '/b' } } },
    ];

    it('reveals checkboxes and a bulk toolbar when Select mode is enabled', async () => {
      const user = userEvent.setup();
      useDashboardStore.setState({ activeExpectations: twoExpectations() });
      render(<ExpectationPanel />);

      // No per-row select checkboxes until Select mode is on.
      expect(screen.queryByLabelText('Select expectation 1')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Select expectations' }));

      expect(screen.getByTestId('expectation-bulk-toolbar')).toBeInTheDocument();
      expect(screen.getByLabelText('Select expectation 1')).toBeInTheDocument();
      expect(screen.getByLabelText('Select expectation 2')).toBeInTheDocument();
    });

    it('bulk-deletes the selected expectations, drops the rows and notifies', async () => {
      const user = userEvent.setup();
      const del = vi.spyOn(expectationsLib, 'deleteExpectation').mockResolvedValue(undefined);
      useDashboardStore.setState({ activeExpectations: twoExpectations() });
      render(<ExpectationPanel />);

      await user.click(screen.getByRole('button', { name: 'Select expectations' }));
      // Select all via the toolbar checkbox.
      await user.click(screen.getByLabelText('Select all expectations'));
      expect(screen.getByText('2 selected')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /Delete selected/ }));
      // Confirmation gate — nothing deleted yet.
      expect(del).not.toHaveBeenCalled();
      await user.click(screen.getByRole('button', { name: 'Delete selected' }));

      await waitFor(() => expect(del).toHaveBeenCalledTimes(2));
      const ids = del.mock.calls.map((c) => c[1]).sort();
      expect(ids).toEqual(['exp-a', 'exp-b']);

      await waitFor(() =>
        expect(useDashboardStore.getState().activeExpectations).toHaveLength(0),
      );
      expect(useDashboardStore.getState().notification).toMatchObject({ severity: 'success' });
    });

    it('reports a partial failure as a warning and keeps the failed row', async () => {
      const user = userEvent.setup();
      vi.spyOn(expectationsLib, 'deleteExpectation').mockImplementation(async (_p, id) => {
        if (id === 'exp-b') throw new Error('nope');
      });
      useDashboardStore.setState({ activeExpectations: twoExpectations() });
      render(<ExpectationPanel />);

      await user.click(screen.getByRole('button', { name: 'Select expectations' }));
      await user.click(screen.getByLabelText('Select all expectations'));
      await user.click(screen.getByRole('button', { name: /Delete selected/ }));
      await user.click(screen.getByRole('button', { name: 'Delete selected' }));

      await waitFor(() =>
        expect(useDashboardStore.getState().notification).toMatchObject({ severity: 'warning' }),
      );
      // Only the successfully-deleted row is dropped.
      const remaining = useDashboardStore.getState().activeExpectations;
      expect(remaining.map((e) => e.key)).toEqual(['exp-b']);
    });
  });
});
