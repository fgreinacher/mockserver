import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import FilterPanel from '../components/FilterPanel';
import {
  applyClientFilters,
  buildBodyMatcher,
  getActionType,
  getLlmProvider,
  matchesStringBody,
} from '../lib/clientFilters';
import { useDashboardStore } from '../store';

function renderFilterPanel(onFilterChange = vi.fn()) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <FilterPanel onFilterChange={onFilterChange} />
    </ThemeProvider>,
  );
}

describe('FilterPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      filterEnabled: false,
      filterExpanded: false,
      logShowForwarded: true,
    });
  });

  it('renders the filter header', () => {
    renderFilterPanel();
    expect(screen.getByText('Request Filter')).toBeInTheDocument();
  });

  it('expands when header is clicked', async () => {
    const user = userEvent.setup();
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));
    expect(screen.getByText('Enabled')).toBeInTheDocument();
  });

  it('shows method, path, and toggle fields when expanded', async () => {
    const user = userEvent.setup();
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.getByLabelText('Enabled')).toBeInTheDocument();
    expect(screen.getByLabelText('Path')).toBeInTheDocument();
    expect(screen.getByLabelText('Secure')).toBeInTheDocument();
    expect(screen.getByLabelText('Keep-Alive')).toBeInTheDocument();
  });

  it('calls onFilterChange with empty filter when disabled', async () => {
    const onFilterChange = vi.fn();
    renderFilterPanel(onFilterChange);

    await waitFor(() => {
      expect(onFilterChange).toHaveBeenCalledWith({});
    });
  });

  it('includes method and path in filter when enabled', async () => {
    const user = userEvent.setup();
    const onFilterChange = vi.fn();
    useDashboardStore.setState({ filterExpanded: true });
    renderFilterPanel(onFilterChange);

    await user.click(screen.getByLabelText('Enabled'));

    const pathInput = screen.getByLabelText('Path');
    await user.type(pathInput, '/api');

    await waitFor(() => {
      const lastCall = onFilterChange.mock.calls[onFilterChange.mock.calls.length - 1]![0];
      expect(lastCall).toHaveProperty('path', '/api');
    });
  });

  it('emits the body-content filter as a substring STRING matcher (not exact equality)', async () => {
    const user = userEvent.setup();
    const onFilterChange = vi.fn();
    useDashboardStore.setState({ filterExpanded: true });
    renderFilterPanel(onFilterChange);

    await user.click(screen.getByLabelText('Enabled'));

    const bodyInput = screen.getByLabelText('Body contains');
    await user.type(bodyInput, 'order-123');

    await waitFor(() => {
      const lastCall = onFilterChange.mock.calls[onFilterChange.mock.calls.length - 1]![0];
      // The filter must carry substring (contains) semantics on the wire: a bare
      // string would deserialize server-side to subString=false (exact match),
      // contradicting the "Body contains" label. So the body is a STRING matcher
      // DTO with subString:true.
      expect(lastCall.body).toEqual({ type: 'STRING', string: 'order-123', subString: true });
    });
  });

  it('auto-enables filtering the first time a field is edited', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ filterExpanded: true, filterEnabled: false });
    renderFilterPanel();

    // Fields are editable while filtering is off, and the hint invites the user in.
    expect(useDashboardStore.getState().filterEnabled).toBe(false);
    expect(screen.getByText('Type in any field to start filtering.')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Path'), '/api');

    // Editing armed the filter: the switch is now on and the store reflects it.
    await waitFor(() => expect(useDashboardStore.getState().filterEnabled).toBe(true));
    expect(screen.getByLabelText('Enabled')).toBeChecked();
  });

  it('leaves filtering off until a field is actually edited', () => {
    useDashboardStore.setState({ filterExpanded: true, filterEnabled: false });
    renderFilterPanel();
    // No edit yet — the switch must not arm itself just from rendering.
    expect(useDashboardStore.getState().filterEnabled).toBe(false);
  });

  it('toggles the "Show forwarded" log display switch through the store', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ filterExpanded: true, logShowForwarded: true });
    renderFilterPanel();

    const toggle = screen.getByLabelText('Show forwarded');
    expect(toggle).toBeChecked();

    await user.click(toggle);
    expect(useDashboardStore.getState().logShowForwarded).toBe(false);
  });

  it('shows Action Type chip cluster when expanded', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({ filterExpanded: false });
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.getByText('Action Type (expectations only)')).toBeInTheDocument();
    expect(screen.getByText('httpResponse')).toBeInTheDocument();
    expect(screen.getByText('httpLlmResponse')).toBeInTheDocument();
  });

  it('shows LLM Provider filter only when LLM expectations exist', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      filterExpanded: false,
      activeExpectations: [
        { key: 'e1', value: { httpLlmResponse: { provider: 'ANTHROPIC' } } },
      ],
    });
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.getByText('LLM Provider (expectations only)')).toBeInTheDocument();
    expect(screen.getByText('Anthropic')).toBeInTheDocument();
    expect(screen.getByText('Gemini')).toBeInTheDocument();
  });

  it('does not show LLM Provider filter when no LLM expectations exist', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      filterExpanded: false,
      activeExpectations: [
        { key: 'e1', value: { httpResponse: { statusCode: 200 } } },
      ],
    });
    renderFilterPanel();

    await user.click(screen.getByText('Request Filter'));

    expect(screen.queryByText('LLM Provider (expectations only)')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Client-side filter utilities
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Body-contains filter — behavioural (substring, not exact equality)
//
// The bug this guards against: the "Body contains" filter previously emitted a
// bare string, which the server deserializes into a StringBody with
// subString=false (exact full-body equality) — so a request whose body merely
// *contained* the term did not match. These tests drive the real matcher the
// filter ships (buildBodyMatcher) through matchesStringBody, the reference
// implementation of the server's STRING body semantics, and prove containment.
// ---------------------------------------------------------------------------

describe('body-contains filter substring behaviour', () => {
  it('builds a STRING matcher with subString=true (substring semantics on the wire)', () => {
    expect(buildBodyMatcher('order-123')).toEqual({
      type: 'STRING',
      string: 'order-123',
      subString: true,
    });
  });

  it('matches a request body that CONTAINS the term', () => {
    const matcher = buildBodyMatcher('order-123');
    expect(matchesStringBody(matcher, '{"id":"order-123","total":42}')).toBe(true);
    expect(matchesStringBody(matcher, 'order-123')).toBe(true);
  });

  it('does NOT match a request body that omits the term', () => {
    const matcher = buildBodyMatcher('order-123');
    expect(matchesStringBody(matcher, '{"id":"order-999"}')).toBe(false);
    expect(matchesStringBody(matcher, '')).toBe(false);
  });

  it('would have failed under the old exact-equality (bare-string) behaviour', () => {
    // Regression anchor: with subString=false the same containing body would NOT
    // match — proving the subString flag is what makes "contains" work.
    const matcher = buildBodyMatcher('order-123');
    const containingBody = '{"id":"order-123"}';
    expect(matchesStringBody(matcher, containingBody)).toBe(true);
    expect(matchesStringBody({ ...matcher, subString: false }, containingBody)).toBe(false);
  });
});

describe('getActionType', () => {
  it('returns the action type key from an expectation value', () => {
    expect(getActionType({ httpResponse: { statusCode: 200 } })).toBe('httpResponse');
    expect(getActionType({ httpLlmResponse: { provider: 'ANTHROPIC' } })).toBe('httpLlmResponse');
    expect(getActionType({ httpForward: { host: 'example.com' } })).toBe('httpForward');
    expect(getActionType({ httpSseResponse: {} })).toBe('httpSseResponse');
  });

  it('returns null when no action key is found', () => {
    expect(getActionType({ httpRequest: { method: 'GET' } })).toBeNull();
  });
});

describe('getLlmProvider', () => {
  it('returns the provider from httpLlmResponse', () => {
    expect(getLlmProvider({ httpLlmResponse: { provider: 'ANTHROPIC' } })).toBe('ANTHROPIC');
    expect(getLlmProvider({ httpLlmResponse: { provider: 'OPENAI' } })).toBe('OPENAI');
  });

  it('returns null when no httpLlmResponse', () => {
    expect(getLlmProvider({ httpResponse: {} })).toBeNull();
  });
});

describe('applyClientFilters', () => {
  const items = [
    { key: '1', value: { httpResponse: { statusCode: 200 } } },
    { key: '2', value: { httpLlmResponse: { provider: 'ANTHROPIC' } } },
    { key: '3', value: { httpLlmResponse: { provider: 'OPENAI' } } },
    { key: '4', value: { httpForward: { host: 'example.com' } } },
    { key: '5', value: { httpLlmResponse: { provider: 'GEMINI' } } },
  ];

  it('returns all items when no filters are set', () => {
    expect(applyClientFilters(items, [], [])).toEqual(items);
  });

  it('filters by action type (OR within dimension)', () => {
    const result = applyClientFilters(items, ['httpLlmResponse'], []);
    expect(result).toHaveLength(3);
    expect(result.map((r) => r.key)).toEqual(['2', '3', '5']);
  });

  it('filters by multiple action types (OR within)', () => {
    const result = applyClientFilters(items, ['httpResponse', 'httpForward'], []);
    expect(result).toHaveLength(2);
    expect(result.map((r) => r.key)).toEqual(['1', '4']);
  });

  it('filters by LLM provider (OR within dimension)', () => {
    const result = applyClientFilters(items, [], ['ANTHROPIC', 'OPENAI']);
    expect(result).toHaveLength(2);
    expect(result.map((r) => r.key)).toEqual(['2', '3']);
  });

  it('applies AND across dimensions', () => {
    // action type = httpLlmResponse AND provider = ANTHROPIC
    const result = applyClientFilters(items, ['httpLlmResponse'], ['ANTHROPIC']);
    expect(result).toHaveLength(1);
    expect(result[0]!.key).toBe('2');
  });

  it('returns empty when no items match AND condition', () => {
    // action type = httpResponse AND provider = ANTHROPIC => no match
    const result = applyClientFilters(items, ['httpResponse'], ['ANTHROPIC']);
    expect(result).toHaveLength(0);
  });
});
