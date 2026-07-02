/**
 * Humanises server / control-plane errors for the dashboard.
 *
 * The app historically dumped raw `MockServer returned ${status}: ${rawText}`
 * strings — often a wall of Java / Jackson / JSON-schema validation text —
 * straight into an Alert. This module maps the common cases to a short,
 * actionable, user-facing `message` and keeps the raw text in `details` so a
 * "Details" expander can still surface it.
 */

export interface HumanError {
  /** Short, user-facing, actionable summary. */
  message: string;
  /** Raw underlying text (server body / exception message) for a Details expander. */
  details?: string;
}

/** Matches the `MockServer returned <status>: <body>` thrown shape (also `Replay failed (<status>): <body>`). */
const SERVER_ERROR_SHAPE = /^MockServer returned (\d{3}):\s*([\s\S]*)$/;
const REPLAY_ERROR_SHAPE = /^Replay failed \((\d{3})\):\s*([\s\S]*)$/;

/**
 * Try to pull a concise reason out of a *structured* server error body. Handles
 * a JSON `{ "error": ... }` envelope and JSON-schema validation output (which
 * the server emits as a multi-line list prefixed by an error count). Returns
 * undefined for unstructured prose so the caller falls back to the generic
 * message and the raw text stays in `details`.
 */
function extractReason(rawBody: string): string | undefined {
  const trimmed = rawBody.trim();
  if (!trimmed) return undefined;

  // JSON envelope: { "error": "..." } or { "error": { "message": "..." } }
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (parsed && typeof parsed === 'object') {
      const err = (parsed as Record<string, unknown>).error;
      if (typeof err === 'string' && err.trim()) return firstLine(err);
      if (err && typeof err === 'object') {
        const msg = (err as Record<string, unknown>).message;
        if (typeof msg === 'string' && msg.trim()) return firstLine(msg);
      }
      const message = (parsed as Record<string, unknown>).message;
      if (typeof message === 'string' && message.trim()) return firstLine(message);
    }
    // Parsed as JSON but no recognised field — nothing concise to surface.
    return undefined;
  } catch {
    // not JSON — fall through to schema-validation heuristics
  }

  // JSON-schema validation bodies start with a line like "N error:" / "1 error:".
  // Surface that summary line; otherwise leave the raw body to the Details pane.
  const line = firstLine(trimmed);
  if (/^\d+\s+errors?:/i.test(line) && line.length <= 200) return line;
  return undefined;
}

/** First non-empty line of a (possibly multi-line) string, trimmed. */
function firstLine(text: string): string {
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (line) return line;
  }
  return text.trim();
}

/**
 * Map an HTTP status + raw body to a short, actionable message, keeping the raw
 * body in `details`.
 */
export function humanizeServerError(status: number, rawBody: string): HumanError {
  const details = rawBody && rawBody.trim() ? rawBody : undefined;

  if (status === 400) {
    const reason = extractReason(rawBody);
    return {
      message: reason
        ? `The request was rejected as invalid: ${reason}`
        : 'The request was rejected as invalid.',
      details,
    };
  }
  if (status === 401 || status === 403) {
    return { message: 'Not authorised — the server rejected the credentials.', details };
  }
  if (status === 404) {
    return {
      message:
        'This feature isn’t available on the connected MockServer (it may be an older version).',
      details,
    };
  }
  if (status === 409) {
    const reason = extractReason(rawBody);
    return {
      message: reason
        ? `The request conflicts with the current state: ${reason}`
        : 'The request conflicts with the current state.',
      details,
    };
  }
  if (status >= 500) {
    return { message: 'The MockServer encountered an internal error.', details };
  }
  return { message: `The MockServer returned an unexpected status (${status}).`, details };
}

/**
 * Humanise a caught exception. Detects network / connection-refused failures
 * (`TypeError: Failed to fetch` and friends) and, when the message carries the
 * `MockServer returned <status>: <body>` shape, parses it and delegates to
 * {@link humanizeServerError}. Otherwise passes the message through.
 */
export function humanizeError(e: unknown): HumanError {
  const message = e instanceof Error ? e.message : String(e);

  const serverMatch = SERVER_ERROR_SHAPE.exec(message) ?? REPLAY_ERROR_SHAPE.exec(message);
  if (serverMatch) {
    const status = Number(serverMatch[1]);
    const body = serverMatch[2] ?? '';
    return humanizeServerError(status, body);
  }

  if (isNetworkError(message)) {
    return {
      message: 'Couldn’t reach the MockServer — is it still running?',
      details: message || undefined,
    };
  }

  return { message: message || 'An unexpected error occurred.' };
}

/**
 * Heuristic detection of fetch network / connection failures across browsers.
 *
 * Matches on the error *message* rather than on the type. Browsers throw a
 * `TypeError` for a fetch network failure, but they also throw `TypeError` for
 * ordinary programming bugs (e.g. reading a property of `undefined`) inside a
 * fetch `.then` chain — so classifying *any* `TypeError` as a network error
 * masked real bugs behind "Couldn't reach the MockServer". We therefore require
 * a known fetch/connection-failure message shape:
 *   - Chrome:  "Failed to fetch"
 *   - Safari:  "Load failed"
 *   - Firefox: "NetworkError when attempting to fetch resource"
 *   - node/undici and connection refusals: "ECONNREFUSED" / "connection refused"
 */
function isNetworkError(message: string): boolean {
  const lower = message.toLowerCase();
  return (
    /failed to fetch|load failed|networkerror/i.test(message) ||
    lower.includes('network error') ||
    lower.includes('econnrefused') ||
    lower.includes('connection refused')
  );
}
