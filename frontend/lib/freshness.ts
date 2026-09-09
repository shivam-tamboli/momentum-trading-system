// Shared freshness logic for the daily scoring algorithm's output, used by both the dashboard's
// Algorithm Status card and the recommendations table's "Last scored" line — one place computes
// "stale" and formats these timestamps, so the two can't silently disagree.

// Backend LocalDateTime values (scored_at, traded_at, last_run_at) serialize with no timezone
// suffix, e.g. "2026-09-09T10:30:59.439754" — but represent real UTC wall-clock time, since the
// JVM runs in UTC. Without an explicit Z, JS's Date parser treats a timezone-less date-time
// string as the browser's own local time instead, which silently mis-renders every timestamp in
// the app for any viewer not physically in UTC. Every raw backend timestamp must go through this
// before becoming a Date.
export function parseBackendTimestamp(value: string): Date {
  const hasTimezone = /[Zz]|[+-]\d\d:\d\d$/.test(value);
  return new Date(hasTimezone ? value : `${value}Z`);
}

export function isStale(scoredAt: string): boolean {
  return parseBackendTimestamp(scoredAt).toDateString() !== new Date().toDateString();
}

function isSameLocalDay(a: Date, b: Date): boolean {
  return a.toDateString() === b.toDateString();
}

function relativeDayLabel(date: Date, now: Date = new Date()): string {
  if (isSameLocalDay(date, now)) return 'Today';

  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (isSameLocalDay(date, yesterday)) return 'Yesterday';

  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);
  if (isSameLocalDay(date, tomorrow)) return 'Tomorrow';

  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(date);
}

const IST_TIME_FORMATTER = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Kolkata',
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
});

const ET_TIME_FORMATTER = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
});

// "4:01 PM IST (10:31 AM ET)" — always both zones, regardless of the viewer's own browser
// timezone. America/New_York resolves EDT vs EST automatically via the real IANA tz database,
// not a fixed offset, so this is correct year-round without any manual DST handling.
export function formatDualTimezone(date: Date): string {
  return `${IST_TIME_FORMATTER.format(date)} IST (${ET_TIME_FORMATTER.format(date)} ET)`;
}

// "Today at 4:01 PM IST (10:31 AM ET)" / "Yesterday at ..." / "Sep 3 at ..."
export function formatScoredAt(scoredAt: string): string {
  const date = parseBackendTimestamp(scoredAt);
  return `${relativeDayLabel(date)} at ${formatDualTimezone(date)}`;
}

// "Sep 3" / "Yesterday" — used in the stale-warning sentence, where the time isn't needed.
export function formatRelativeDate(scoredAt: string): string {
  return relativeDayLabel(parseBackendTimestamp(scoredAt));
}

// "Sep 7, 2026, 4:01 PM IST (10:31 AM ET)"
export function formatExactDateTime(scoredAt: string): string {
  const date = parseBackendTimestamp(scoredAt);
  const dateLabel = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(date);
  return `${dateLabel}, ${formatDualTimezone(date)}`;
}

// Scoring runs weekdays at 10:30 UTC (6:30 AM ET, 3 hours before the regular 9:30 AM market
// open) via .github/workflows/daily-trading-cron.yml. This is a UI estimate only — it doesn't
// know about market holidays or early closes, so treat it as "roughly when," not a guarantee.
const SCORING_HOUR_UTC = 10;
const SCORING_MINUTE_UTC = 30;

export function getNextScoringRun(from: Date = new Date()): Date {
  const candidate = new Date(from);
  candidate.setUTCHours(SCORING_HOUR_UTC, SCORING_MINUTE_UTC, 0, 0);

  while (candidate <= from || candidate.getUTCDay() === 0 || candidate.getUTCDay() === 6) {
    candidate.setUTCDate(candidate.getUTCDate() + 1);
    candidate.setUTCHours(SCORING_HOUR_UTC, SCORING_MINUTE_UTC, 0, 0);
  }

  return candidate;
}

// "Today at 4:01 PM IST (10:31 AM ET)" / "Tomorrow at ..." / "Sep 8 at ..."
export function formatNextRun(date: Date): string {
  return `${relativeDayLabel(date)} at ${formatDualTimezone(date)}`;
}
