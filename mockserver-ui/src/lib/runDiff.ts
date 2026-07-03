/**
 * Client for the server-side prompt-level agent-run diff
 * (`PUT /mockserver/llm/diffRuns`).
 *
 * Unlike the client-side structural comparison in `trajectoryDiff.ts` — which
 * only inspects tool-call skeletons and token trajectories — this endpoint
 * decodes the captured prompts server-side (after redaction) and returns a
 * message-level diff with the actual before/after prompt text.
 *
 * The server groups captured traffic by upstream host, so each side is
 * identified by a `{ session | host | provider }` filter. The dashboard passes
 * the upstream host of each selected trace.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** One side's selection filter (all fields optional; blanks are ignored server-side). */
export interface RunDiffFilter {
  session?: string | null;
  host?: string | null;
  provider?: string | null;
}

/** A single message-level change in the decoded diff. */
export interface RunMessageDiff {
  /** ADDED | REMOVED | MODIFIED | UNCHANGED (server enum name). */
  changeType: string;
  role: string | null;
  beforeText: string | null;
  afterText: string | null;
}

/** Token/cost deltas between the two runs (present when both sides carry usage). */
export interface RunTokenDelta {
  inputTokensBefore: number | null;
  inputTokensAfter: number | null;
  inputTokensDelta: number | null;
  outputTokensBefore: number | null;
  outputTokensAfter: number | null;
  outputTokensDelta: number | null;
  costUsdBefore: number | null;
  costUsdAfter: number | null;
  costUsdDelta: number | null;
}

/** The decoded, prompt-level diff returned by the server. */
export interface RunDiffResult {
  promptChanged: boolean;
  messageCountBefore: number;
  messageCountAfter: number;
  messageDiffs: RunMessageDiff[];
  toolCallsAdded: string[];
  toolCallsRemoved: string[];
  tokenDelta?: RunTokenDelta | null;
}

/**
 * Compute the server-side decoded-prompt diff of two captured runs
 * (`PUT /mockserver/llm/diffRuns`).
 *
 * @throws Error with the server's message on a non-2xx response.
 */
export async function diffRuns(
  params: ConnectionParams,
  before: RunDiffFilter,
  after: RunDiffFilter,
  signal?: AbortSignal,
): Promise<RunDiffResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/llm/diffRuns`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ before, after }),
    signal,
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  const data = (await res.json()) as Partial<RunDiffResult> | null;
  return {
    promptChanged: data?.promptChanged === true,
    messageCountBefore: typeof data?.messageCountBefore === 'number' ? data.messageCountBefore : 0,
    messageCountAfter: typeof data?.messageCountAfter === 'number' ? data.messageCountAfter : 0,
    messageDiffs: Array.isArray(data?.messageDiffs) ? data!.messageDiffs : [],
    toolCallsAdded: Array.isArray(data?.toolCallsAdded) ? data!.toolCallsAdded : [],
    toolCallsRemoved: Array.isArray(data?.toolCallsRemoved) ? data!.toolCallsRemoved : [],
    tokenDelta: data?.tokenDelta ?? null,
  };
}
