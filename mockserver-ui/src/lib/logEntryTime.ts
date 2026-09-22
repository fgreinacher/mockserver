/**
 * Helpers for rendering a log entry's server-side timestamp.
 *
 * MockServer formats log timestamps as `yyyy-MM-dd HH:mm:ss.SSS` in the
 * server's local time, with no timezone marker (see
 * `LogEntry.LOG_DATE_FORMAT`). That space-separated, zoneless form is not
 * reliably parseable by `new Date(...)` across JS engines, so we parse it
 * explicitly into the viewer's local interpretation and keep the raw string
 * around for the absolute-time tooltip.
 */

export interface ParsedLogTime {
  /** The original server string, shown verbatim on hover. */
  raw: string;
  /** Parsed Date, or null when the string is not in the expected format. */
  date: Date | null;
}

const LOG_TIMESTAMP_REGEX =
  /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?$/;

/**
 * Parse a MockServer log timestamp string. Returns the raw string always, and a
 * Date when the string matches the expected format. Treats the value as local
 * time (matching how the server formats it).
 */
export function parseLogTimestamp(timestamp: string): ParsedLogTime {
  const raw = timestamp.trim();
  const match = LOG_TIMESTAMP_REGEX.exec(raw);
  if (!match) {
    return { raw, date: null };
  }
  const [, year, month, day, hour, minute, second, millis] = match;
  const date = new Date(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second),
    millis ? Number(millis.padEnd(3, '0')) : 0,
  );
  return { raw, date: Number.isNaN(date.getTime()) ? null : date };
}

/**
 * Compact, inline time-of-day label (e.g. `10:57:18`). Falls back to the raw
 * string when it cannot be parsed so the user still sees something meaningful.
 */
export function formatCompactTime(parsed: ParsedLogTime): string {
  if (!parsed.date) return parsed.raw;
  return parsed.date.toLocaleTimeString();
}

/**
 * Absolute, human-readable timestamp for the hover tooltip (e.g.
 * `5/5/2025, 10:57:18 AM`). Falls back to the raw string when unparseable.
 */
export function formatAbsoluteTime(parsed: ParsedLogTime): string {
  if (!parsed.date) return parsed.raw;
  return parsed.date.toLocaleString();
}

// hh:mm:ss.mmm pulled straight out of the server's log timestamp string, matching
// the Log Messages panel — a purely lexical extract (no Date parsing, no timezone
// conversion) so what a request row shows lines up character-for-character with
// the log. Returns the raw value unchanged if it is not in the expected shape:
// better an odd-looking string than a row that silently shows nothing. Shared by
// the dashboard request/expectation rows (JsonListItem) and the Traffic list, so
// the "time it happened, not a position in the list" column is identical on both.
export function formatRowTime(timestamp: string): string {
  const m = /(\d{2}:\d{2}:\d{2}\.\d{3})/.exec(timestamp);
  return m ? m[1]! : timestamp;
}
