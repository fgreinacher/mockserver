import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import SessionInspector from '../components/SessionInspector';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

function renderInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <SessionInspector connectionParams={{ host: 'localhost', port: '1080', secure: false }} />
    </ThemeProvider>,
  );
}

function makeAnthropicRequest(
  key: string,
  agentId?: string,
  messages: unknown[] = [{ role: 'user', content: 'Hello' }],
): JsonListItem {
  const headers: Array<{ name: string; values: string[] }> = [
    { name: 'host', values: ['api.anthropic.com'] },
  ];
  if (agentId) {
    headers.push({ name: 'x-agent-id', values: [agentId] });
  }
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            messages,
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            content: [{ type: 'text', text: 'Hi!' }],
            usage: { input_tokens: 10, output_tokens: 5 },
            stop_reason: 'end_turn',
          }),
        },
      },
    },
  };
}

function makeOpenAiRequest(key: string, host = 'api.openai.com'): JsonListItem {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/chat/completions',
        headers: [{ name: 'host', values: [host] }],
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4o',
            messages: [{ role: 'user', content: 'Where is my order?' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'gpt-4o',
            choices: [{ message: { role: 'assistant', content: 'Checking now.' } }],
            usage: { prompt_tokens: 12, completion_tokens: 4 },
          }),
        },
      },
    },
  };
}

function makeIsolatedExpectation(scenarioName: string): JsonListItem {
  return {
    key: `exp-${scenarioName}`,
    value: {
      // scenarioName lives at the top level of the expectation payload,
      // matching the real MockServer active-expectation shape.
      scenarioName,
      scenarioState: 'Started',
      newScenarioState: 'turn_1',
      httpLlmResponse: {
        provider: 'ANTHROPIC',
        model: 'claude-sonnet-4-20250514',
        conversationPredicates: { turnIndex: 0 },
        completion: { text: 'Hello!', stopReason: 'end_turn' },
      },
    },
  };
}

describe('SessionInspector', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      activeExpectations: [],
    });
  });

  it('renders empty state when no proxied LLM requests exist', () => {
    renderInspector();
    expect(screen.getByText('No LLM traffic captured yet')).toBeInTheDocument();
    expect(screen.getByText(/proxy through MockServer/)).toBeInTheDocument();
  });

  it('renders sessions when LLM traffic with isolation is present', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1', 'agent-A'),
        makeAnthropicRequest('req-2', 'agent-B'),
        makeAnthropicRequest('req-3', 'agent-A'),
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // Should show "Active traces: 2"
    expect(screen.getByText('Active traces: 2')).toBeInTheDocument();

    // Session lanes should be visible
    expect(screen.getByText(/chat \/ agent-A/)).toBeInTheDocument();
    expect(screen.getByText(/chat \/ agent-B/)).toBeInTheDocument();
  });

  it('expanding a chip reveals request detail', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1', 'agent-A'),
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // Find the request chip and click it
    const chip = screen.getByText(/\[0\] POST \/v1\/messages/);
    await user.click(chip);

    // After expanding, should see conversation content. The AnthropicConversationView
    // shows user messages as bubbles. Check for the content.
    expect(screen.getByText('Hello')).toBeInTheDocument();
  });

  it('search box filters sessions', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1', 'agent-A'),
        makeAnthropicRequest('req-2', 'agent-B'),
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // Both sessions visible initially
    expect(screen.getByText(/chat \/ agent-A/)).toBeInTheDocument();
    expect(screen.getByText(/chat \/ agent-B/)).toBeInTheDocument();

    // Type in the search box
    const searchInput = screen.getByPlaceholderText('Filter traces...');
    await user.type(searchInput, 'agent-A');

    // Only agent-A session should remain
    expect(screen.getByText(/chat \/ agent-A/)).toBeInTheDocument();
    expect(screen.queryByText(/chat \/ agent-B/)).not.toBeInTheDocument();
  });

  it('exposes a Compare tab that switches to the trace comparison view', async () => {
    const user = userEvent.setup();
    renderInspector();

    // The Trace page now has exactly two tabs: Traces and Compare.
    // (Scenarios moved to the Mocks page.)
    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(2);
    expect(tabs[0]).toHaveTextContent('Traces');
    expect(tabs[1]).toHaveTextContent('Compare');
    expect(screen.queryByRole('tab', { name: 'Scenarios' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Compare' }));

    // The Compare (trace comparison) view exposes Trace A / Trace B selectors.
    expect(screen.getByLabelText('Trace A')).toBeInTheDocument();
    expect(screen.getByLabelText('Trace B')).toBeInTheDocument();
  });

  it('renders unscoped session with upstream host for proxy traffic without isolation', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1'), // no agent id header, but has host: api.anthropic.com
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // The host header provides the upstream host for unscoped proxy traffic
    expect(screen.getByText('Unscoped requests (api.anthropic.com)')).toBeInTheDocument();
  });

  it('renders plain "Unscoped requests" when no host header is present', () => {
    // Build a request with no host header so isolationKey falls back to <unscoped>
    const noHostRequest: JsonListItem = {
      key: 'req-no-host',
      value: {
        httpRequest: {
          method: 'POST',
          path: '/v1/messages',
          headers: [], // no host header
          body: {
            type: 'JSON',
            json: JSON.stringify({
              model: 'claude-sonnet-4-20250514',
              messages: [{ role: 'user', content: 'Hello' }],
            }),
          },
        },
        httpResponse: {
          statusCode: 200,
          body: {
            type: 'JSON',
            json: JSON.stringify({
              model: 'claude-sonnet-4-20250514',
              content: [{ type: 'text', text: 'Hi!' }],
              usage: { input_tokens: 10, output_tokens: 5 },
              stop_reason: 'end_turn',
            }),
          },
        },
      },
    };

    useDashboardStore.setState({
      proxiedRequests: [noHostRequest],
      activeExpectations: [],
    });

    renderInspector();

    // With no host header, isolationKey is the <unscoped> sentinel,
    // so the label should be plain "Unscoped requests" without a parenthetical.
    expect(screen.getByText('Unscoped requests')).toBeInTheDocument();
  });

  it('flags a mixed unscoped lane and hides the agent-run graph', async () => {
    const user = userEvent.setup();
    // Two unrelated providers sharing a host → one <unscoped> lane with a mix.
    const anthropic: JsonListItem = {
      key: 'a1',
      value: {
        httpRequest: {
          method: 'POST',
          path: '/v1/messages',
          headers: [{ name: 'host', values: ['localhost:1080'] }],
          body: {
            type: 'JSON',
            json: JSON.stringify({
              model: 'claude-sonnet-4-20250514',
              messages: [{ role: 'user', content: 'Hi' }],
            }),
          },
        },
        httpResponse: {
          statusCode: 200,
          body: {
            type: 'JSON',
            json: JSON.stringify({
              model: 'claude-sonnet-4-20250514',
              content: [{ type: 'text', text: 'Hello' }],
              usage: { input_tokens: 10, output_tokens: 5 },
              stop_reason: 'end_turn',
            }),
          },
        },
      },
    };
    useDashboardStore.setState({
      proxiedRequests: [anthropic, makeOpenAiRequest('o1', 'localhost:1080')],
      activeExpectations: [],
    });

    renderInspector();

    // No correlated agent-run graph for the heterogeneous catch-all.
    expect(screen.queryByText('Show graph')).not.toBeInTheDocument();
    // Two different providers cannot be a growing prefix of one another, so the
    // grouping yields two separate conversation threads.
    const convBtn = screen.getByText(/Conversations \(2\)/);
    await user.click(convBtn);
    expect(
      screen.getByText(/2 separate conversation threads/),
    ).toBeInTheDocument();
  });

  it('shows the agent-run graph for a scoped single-provider session', () => {
    useDashboardStore.setState({
      proxiedRequests: [makeAnthropicRequest('r1', 'agent-A')],
      activeExpectations: [makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id')],
    });

    renderInspector();

    expect(screen.getByText('Show graph')).toBeInTheDocument();
    // A single-provider scoped lane uses the plain "Conversation" label (no "latest of N").
    expect(screen.getByText('Conversation')).toBeInTheDocument();
    expect(screen.queryByText(/latest of/)).not.toBeInTheDocument();
  });

  it('renders two unrelated same-provider requests as separate threads', async () => {
    const user = userEvent.setup();
    // Two unrelated Anthropic requests (different content), same host, no isolation.
    // Neither is a prefix of the other, so they do NOT collapse into one thread.
    const r1 = makeAnthropicRequest('u1', undefined, [{ role: 'user', content: 'First question' }]);
    const r2 = makeAnthropicRequest('u2', undefined, [{ role: 'user', content: 'Totally separate' }]);
    useDashboardStore.setState({ proxiedRequests: [r1, r2], activeExpectations: [] });

    renderInspector();

    const convBtn = screen.getByText(/Conversations \(2\)/);
    await user.click(convBtn);
    expect(screen.getByText(/2 separate conversation threads/)).toBeInTheDocument();
  });

  it('collapses a growing multi-turn conversation into one thread of turn deltas', async () => {
    const user = userEvent.setup();
    // A stateless CLI resends the whole history each turn — the message list grows
    // as a prefix. Three such requests collapse into one growing thread.
    const turn0 = makeAnthropicRequest('g0', 'agent-A', [
      { role: 'user', content: 'turn-zero-question' },
    ]);
    const turn1 = makeAnthropicRequest('g1', 'agent-A', [
      { role: 'user', content: 'turn-zero-question' },
      { role: 'assistant', content: 'reply-zero' },
      { role: 'user', content: 'turn-one-followup' },
    ]);
    const turn2 = makeAnthropicRequest('g2', 'agent-A', [
      { role: 'user', content: 'turn-zero-question' },
      { role: 'assistant', content: 'reply-zero' },
      { role: 'user', content: 'turn-one-followup' },
      { role: 'assistant', content: 'reply-one' },
      { role: 'user', content: 'turn-two-followup' },
    ]);
    useDashboardStore.setState({
      proxiedRequests: [turn0, turn1, turn2],
      activeExpectations: [makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id')],
    });

    renderInspector();

    // One growing thread, labelled with its turn count (not three full transcripts).
    const convBtn = screen.getByText(/Conversation \(3 turns\)/);
    await user.click(convBtn);

    // The grouped growing-thread view is rendered (not a single flat transcript).
    expect(screen.getByTestId('grouped-conversation')).toBeInTheDocument();
    // Each turn's NEW content is shown exactly once — the later follow-ups appear,
    // and the initial question is not repeated for every turn.
    expect(screen.getByText('turn-one-followup')).toBeInTheDocument();
    expect(screen.getByText('turn-two-followup')).toBeInTheDocument();
    expect(screen.getAllByText('turn-zero-question')).toHaveLength(1);
  });
});
