import type { ReactNode } from 'react';
import { CheckCircle2, Clock, Receipt, XCircle } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import { cn } from '@/lib/utils';
import { formatFullDateTime, formatRelativeDate, formatRelativeLogDate } from '@/lib/freshness';
import type { DailyTradeItem, EngineLogItem } from '@/lib/types';

const ACTION_STYLES: Record<DailyTradeItem['action'], string> = {
  BUY: 'bg-gain text-gain-foreground hover:bg-gain',
  SELL: 'bg-loss text-loss-foreground hover:bg-loss',
};

const STATUS_STYLES: Record<DailyTradeItem['status'], string> = {
  FILLED: 'bg-secondary text-secondary-foreground',
  PENDING: 'bg-pending text-pending-foreground hover:bg-pending',
  FAILED: 'bg-destructive text-destructive-foreground',
};

const STATUS_ICONS: Record<DailyTradeItem['status'], typeof CheckCircle2> = {
  FILLED: CheckCircle2,
  PENDING: Clock,
  FAILED: XCircle,
};

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

function formatCurrency(value: number | null): string {
  return value === null ? '—' : currency.format(value);
}

function formatQuantity(value: number | null): string {
  return value === null ? '—' : String(value);
}

function daySummary(trades: DailyTradeItem[]) {
  const buys = trades.filter((t) => t.action === 'BUY').length;
  const sells = trades.filter((t) => t.action === 'SELL').length;
  // Only FILLED rows have a real, known dollar amount — PENDING/FAILED never contribute a
  // fabricated number to the net figure.
  const net = trades.reduce((sum, t) => {
    if (t.status !== 'FILLED' || t.amount === null) return sum;
    return sum + (t.action === 'SELL' ? t.amount : -t.amount);
  }, 0);
  return { buys, sells, net };
}

type RenderItem =
  | { type: 'day'; day: string; trades: DailyTradeItem[] }
  | { type: 'switch'; from: string; to: string }
  | { type: 'trade'; trade: DailyTradeItem }
  | { type: 'log-summary'; day: string; entry: EngineLogItem }
  | { type: 'calendar-gap'; day: string; kind: 'weekend' | 'weekday' | 'today' };

// log_date / traded_at dates are UTC calendar days (see freshness.ts's parseBackendTimestamp) —
// the gap-fill walk below has to work on that same UTC axis, not the viewer's local calendar day,
// or a viewer far enough ahead of UTC (e.g. IST, +5:30) would see "today" flip over hours before
// the backend's UTC day actually does, mislabeling a day nothing could possibly have run for yet
// as a gap. This is a different concern from formatRelativeLogDate's local-day "Today"/"Yesterday"
// labels below, which are a display nicety for an already-known date, not a range boundary.
function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

function addUtcDays(dateStr: string, delta: number): string {
  const date = new Date(`${dateStr}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + delta);
  return date.toISOString().slice(0, 10);
}

function isWeekendUtc(dateStr: string): boolean {
  const day = new Date(`${dateStr}T00:00:00Z`).getUTCDay();
  return day === 0 || day === 6;
}

// Builds one flat list mixing day separators, index-switch markers, trade rows, and (new)
// engine-log summary rows — one per calendar day that has zero daily_trade rows but does have a
// daily_engine_log entry, i.e. a day the system ran and made a conscious decision (no rebalance
// needed, market closed, or a failure) rather than a day nothing is known about at all.
//
// Trades arrive traded_at desc (newest first); engineLog arrives log_date desc. Both are merged
// into a single chronological list, newest day first, so a genuinely empty gap in history (an old
// day predating this feature, or a day the system never ran at all) still shows nothing — the
// original problem this closes is a real run producing no visible trace, not every possible gap.
function buildRenderItems(trades: DailyTradeItem[], engineLog: EngineLogItem[]): RenderItem[] {
  // Pass 1: reuse the original flat trade/day/switch walk exactly as before, then split it back
  // into per-day chunks so summary-only days can be spliced in at the right sorted position.
  const tradeItems: RenderItem[] = [];
  let currentDay: string | null = null;

  for (let i = 0; i < trades.length; i++) {
    const trade = trades[i];
    const day = trade.traded_at.slice(0, 10);

    if (day !== currentDay) {
      const dayTrades = trades.filter((t) => t.traded_at.slice(0, 10) === day);
      tradeItems.push({ type: 'day', day, trades: dayTrades });
      currentDay = day;
    }

    tradeItems.push({ type: 'trade', trade });

    const older = trades[i + 1];
    if (
      older &&
      trade.index_filter !== null &&
      older.index_filter !== null &&
      trade.index_filter !== older.index_filter
    ) {
      tradeItems.push({ type: 'switch', from: older.index_filter, to: trade.index_filter });
    }
  }

  const chunksByDay = new Map<string, RenderItem[]>();
  let chunkKey: string | null = null;
  for (const item of tradeItems) {
    if (item.type === 'day') {
      chunkKey = item.day;
      chunksByDay.set(chunkKey, [item]);
    } else if (chunkKey) {
      chunksByDay.get(chunkKey)!.push(item);
    }
  }

  // NOT_RUN means Job 1 created today's row but Job 2 hasn't had its turn yet — there's nothing
  // true to say about that day yet, so it gets no summary row (same principle the old pending
  // state followed, just per-day now instead of only for today).
  const summaryDays = new Map<string, EngineLogItem>();
  for (const entry of engineLog) {
    if (!chunksByDay.has(entry.log_date) && entry.job2_status !== 'NOT_RUN') {
      summaryDays.set(entry.log_date, entry);
    }
  }

  const knownDays = new Set([...chunksByDay.keys(), ...summaryDays.keys()]);
  if (knownDays.size === 0) {
    // Nothing to anchor a calendar range to — a brand-new account with zero history gets the
    // generic empty state in the component below, not a wall of "no data" placeholders back to
    // some arbitrary start date.
    return [];
  }

  const earliestKnownDay = Array.from(knownDays).sort()[0];
  const today = todayUtc();
  // Guards the (normal) case where today is after every known day, and the edge case where the
  // most recent thing on record somehow is today itself — either way, walk from whichever is
  // later down to the earliest known day.
  const rangeEnd = today > earliestKnownDay ? today : earliestKnownDay;

  const items: RenderItem[] = [];
  let cursor = rangeEnd;
  while (cursor >= earliestKnownDay) {
    const chunk = chunksByDay.get(cursor);
    if (chunk) {
      items.push(...chunk);
    } else if (summaryDays.has(cursor)) {
      items.push({ type: 'log-summary', day: cursor, entry: summaryDays.get(cursor)! });
    } else if (cursor === today) {
      items.push({ type: 'calendar-gap', day: cursor, kind: 'today' });
    } else if (isWeekendUtc(cursor)) {
      items.push({ type: 'calendar-gap', day: cursor, kind: 'weekend' });
    } else {
      items.push({ type: 'calendar-gap', day: cursor, kind: 'weekday' });
    }
    cursor = addUtcDays(cursor, -1);
  }

  return items;
}

interface TradeHistoryTableProps {
  trades: DailyTradeItem[] | undefined;
  engineLog: EngineLogItem[] | undefined;
  isLoading: boolean;
}

export function TradeHistoryTable({ trades, engineLog, isLoading }: TradeHistoryTableProps) {
  const items = trades ? buildRenderItems(trades, engineLog ?? []) : [];
  const hasNothingAtAll = (!trades || trades.length === 0) && items.length === 0;

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Symbol</TableHead>
          <TableHead>Index</TableHead>
          <TableHead>Action</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="text-right">Amount</TableHead>
          <TableHead className="text-right">Price / Share</TableHead>
          <TableHead className="text-right">Quantity</TableHead>
          <TableHead>Traded At</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {isLoading &&
          Array.from({ length: 5 }).map((_, i) => (
            <TableRow key={i}>
              {Array.from({ length: 8 }).map((__, j) => (
                <TableCell key={j}>
                  <Skeleton className="h-4 w-full" />
                </TableCell>
              ))}
            </TableRow>
          ))}

        {!isLoading && hasNothingAtAll && (
          <TableRow>
            <TableCell colSpan={8}>
              <EmptyState
                icon={Receipt}
                message="No trades yet. Your auto-trades will appear here after market open."
              />
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          items.map((item, i) => {
            if (item.type === 'day') {
              const { buys, sells, net } = daySummary(item.trades);
              return (
                <TableRow key={`day-${item.day}`} className="hover:bg-transparent">
                  <TableCell colSpan={8} className="bg-muted/30 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-xs">
                      <span className="font-semibold text-foreground">
                        {formatRelativeDate(item.trades[0].traded_at)}
                      </span>
                      <span className="font-mono text-muted-foreground tabular-nums">
                        {buys} buy{buys === 1 ? '' : 's'}, {sells} sell{sells === 1 ? '' : 's'}
                        {' · '}
                        Net:{' '}
                        <span className={cn('font-semibold', net >= 0 ? 'text-gain' : 'text-loss')}>
                          {net >= 0 ? '+' : '−'}
                          {currency.format(Math.abs(net))}
                        </span>
                      </span>
                    </div>
                  </TableCell>
                </TableRow>
              );
            }

            if (item.type === 'switch') {
              return (
                <TableRow key={`switch-${i}`} className="hover:bg-transparent">
                  <TableCell colSpan={8} className="py-2 text-center">
                    <span className="font-mono text-xs text-primary">
                      ── Switched from {item.from} to {item.to} ──
                    </span>
                  </TableCell>
                </TableRow>
              );
            }

            if (item.type === 'log-summary') {
              return <LogSummaryRow key={`log-${item.day}`} day={item.day} entry={item.entry} />;
            }

            if (item.type === 'calendar-gap') {
              return <CalendarGapRow key={`gap-${item.day}`} day={item.day} kind={item.kind} />;
            }

            const trade = item.trade;
            const StatusIcon = STATUS_ICONS[trade.status];
            return (
              <TableRow
                key={`${trade.symbol}-${trade.traded_at}-${i}`}
                className={cn(trade.status === 'PENDING' && 'bg-pending/10')}
              >
                <TableCell className="font-medium">{trade.symbol}</TableCell>
                <TableCell>
                  {trade.index_filter ? (
                    <Badge variant="outline" className="border-primary/30 bg-primary/10 text-primary">
                      {trade.index_filter}
                    </Badge>
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </TableCell>
                <TableCell>
                  <Badge className={cn(ACTION_STYLES[trade.action])}>{trade.action}</Badge>
                </TableCell>
                <TableCell>
                  <Badge className={cn('h-6 gap-1.5 px-2.5 text-sm', STATUS_STYLES[trade.status])}>
                    <StatusIcon className="h-3.5 w-3.5" />
                    {trade.status === 'PENDING' ? 'Pending — still processing' : trade.status}
                  </Badge>
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums">
                  {formatCurrency(trade.amount)}
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums">
                  {formatCurrency(trade.price_per_share)}
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums">
                  {formatQuantity(trade.quantity)}
                </TableCell>
                <TableCell className="font-mono text-muted-foreground tabular-nums">
                  {formatFullDateTime(trade.traded_at)}
                </TableCell>
              </TableRow>
            );
          })}
      </TableBody>
    </Table>
  );
}

// One row per day with an engine-log entry but no trades — the exact gap that used to render as
// nothing at all. Three job2_status outcomes get their own message; everything else (COMPLETED
// with no trades, which shouldn't normally happen since a real buy/sell always writes a
// daily_trade row, or a NOT_RUN that slipped through) falls back to the raw rebalance_summary text
// rather than silently dropping the day.
function LogSummaryRow({ day, entry }: { day: string; entry: EngineLogItem }) {
  const top5List = entry.top5_symbols ? entry.top5_symbols.split(',').join(', ') : null;

  let label: string;
  let detail: ReactNode;
  let styles: string;

  if (entry.job2_status === 'NO_REBALANCE_NEEDED') {
    label = 'No rebalancing needed';
    styles = 'border-gain/50 bg-gain/10 text-gain';
    detail = (
      <>
        <span>✓ Holdings already match today&apos;s top 5</span>
        {top5List && <span className="font-mono text-xs opacity-90">{top5List}</span>}
      </>
    );
  } else if (entry.job2_status === 'MARKET_CLOSED') {
    label = 'Market closed';
    styles = 'border-muted-foreground/30 bg-muted/30 text-muted-foreground';
    detail = <span>Market closed — no trades executed</span>;
  } else if (entry.job2_status === 'FAILED') {
    label = 'Trading failed';
    styles = 'border-destructive/50 bg-destructive/10 text-destructive';
    detail = <span>Trading failed — check system logs</span>;
  } else {
    label = entry.job2_status;
    styles = 'border-muted-foreground/30 bg-muted/30 text-muted-foreground';
    detail = <span>{entry.rebalance_summary ?? 'No further detail recorded.'}</span>;
  }

  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={8} className="py-3">
        <div className={cn('space-y-1 rounded-md border px-3 py-2 text-sm', styles)}>
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
            <span className="font-semibold">{formatRelativeLogDate(day)}</span>
            <span className="font-medium">{label}</span>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">{detail}</div>
        </div>
      </TableCell>
    </TableRow>
  );
}

// A calendar day with no daily_trade rows AND no daily_engine_log entry at all — the system may
// genuinely not have run (a real gap, worth flagging) or the day may just be a weekend (expected,
// not worth flagging the same way). "today" is its own case: rather than "no data" (which reads as
// something having gone wrong), it's just not this day's turn yet.
function CalendarGapRow({ day, kind }: { day: string; kind: 'weekend' | 'weekday' | 'today' }) {
  if (kind === 'today') {
    return (
      <TableRow className="hover:bg-transparent">
        <TableCell colSpan={8} className="py-3">
          <div className="rounded-md border border-pending/50 bg-pending/10 px-3 py-2 text-center text-sm font-medium text-pending">
            Waiting for today&apos;s algorithm run
          </div>
        </TableCell>
      </TableRow>
    );
  }

  const isWeekend = kind === 'weekend';
  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={8} className="py-3">
        <div
          className={cn(
            'flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-md border px-3 py-2 text-sm',
            isWeekend
              ? 'border-muted-foreground/30 bg-muted/30 text-muted-foreground'
              : 'border-pending/50 bg-pending/10 text-pending',
          )}
        >
          <span className="font-semibold">{formatRelativeLogDate(day)}</span>
          <span>{isWeekend ? 'Market closed — weekend' : 'No data — algorithm may not have run'}</span>
        </div>
      </TableCell>
    </TableRow>
  );
}
