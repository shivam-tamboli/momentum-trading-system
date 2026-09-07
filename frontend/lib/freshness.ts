// Shared freshness logic for the daily scoring algorithm's output, used by both the dashboard's
// Algorithm Status card and the recommendations table's "Last scored" line — one place computes
// "stale" and formats these timestamps, so the two can't silently disagree.

export function isStale(scoredAt: string): boolean {
  return new Date(scoredAt).toDateString() !== new Date().toDateString();
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

const timeFormatter = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });
const exactFormatter = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' });

// "Today at 2:20 PM" / "Yesterday at 2:20 PM" / "Sep 3 at 2:20 PM" — all in the browser's local
// timezone, since Intl.DateTimeFormat with no explicit timeZone uses the runtime's local zone.
export function formatScoredAt(scoredAt: string): string {
  const date = new Date(scoredAt);
  return `${relativeDayLabel(date)} at ${timeFormatter.format(date)}`;
}

// "Sep 3" / "Yesterday" — used in the stale-warning sentence, where the time isn't needed.
export function formatRelativeDate(scoredAt: string): string {
  return relativeDayLabel(new Date(scoredAt));
}

// "Sep 7, 2026, 2:20 PM" — the exact last-ran timestamp shown on the Algorithm Status card.
export function formatExactDateTime(scoredAt: string): string {
  return exactFormatter.format(new Date(scoredAt));
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

// "Today at 6:30 AM" / "Tomorrow at 6:30 AM" / "Sep 8 at 6:30 AM"
export function formatNextRun(date: Date): string {
  return `${relativeDayLabel(date)} at ${timeFormatter.format(date)}`;
}
