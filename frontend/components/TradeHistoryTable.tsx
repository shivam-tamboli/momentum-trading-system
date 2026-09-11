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
import { formatFullDateTime, formatRelativeDate, parseBackendTimestamp } from '@/lib/freshness';
import type { DailyTradeItem } from '@/lib/types';

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
  | { type: 'trade'; trade: DailyTradeItem };

// Builds one flat list mixing day separators, index-switch markers, and trade rows — trades
// arrive sorted traded_at desc (newest first), so walking forward through the array walks
// backward through time. A switch marker appears between two consecutive trades whenever their
// index_filter differs — "from" is the older trade's index, "to" is the newer one's, since that's
// the direction the switch actually happened in.
function buildRenderItems(trades: DailyTradeItem[]): RenderItem[] {
  const items: RenderItem[] = [];
  let currentDay: string | null = null;

  for (let i = 0; i < trades.length; i++) {
    const trade = trades[i];
    const day = trade.traded_at.slice(0, 10);

    if (day !== currentDay) {
      const dayTrades = trades.filter((t) => t.traded_at.slice(0, 10) === day);
      items.push({ type: 'day', day, trades: dayTrades });
      currentDay = day;
    }

    items.push({ type: 'trade', trade });

    const older = trades[i + 1];
    if (
      older &&
      trade.index_filter !== null &&
      older.index_filter !== null &&
      trade.index_filter !== older.index_filter
    ) {
      items.push({ type: 'switch', from: older.index_filter, to: trade.index_filter });
    }
  }

  return items;
}

function isToday(tradedAt: string): boolean {
  return parseBackendTimestamp(tradedAt).toDateString() === new Date().toDateString();
}

interface TradeHistoryTableProps {
  trades: DailyTradeItem[] | undefined;
  isLoading: boolean;
}

export function TradeHistoryTable({ trades, isLoading }: TradeHistoryTableProps) {
  const items = trades ? buildRenderItems(trades) : [];
  // Only relevant once there's history at all — a brand-new account with zero trades ever gets
  // the generic empty state below instead, not a claim that holdings already match anything.
  const hasHistory = !!trades && trades.length > 0;
  const hasTradeToday = hasHistory && trades!.some((t) => isToday(t.traded_at));

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

        {!isLoading && (!trades || trades.length === 0) && (
          <TableRow>
            <TableCell colSpan={8}>
              <EmptyState
                icon={Receipt}
                message="No trades yet. Your auto-trades will appear here after market open."
              />
            </TableCell>
          </TableRow>
        )}

        {!isLoading && hasHistory && !hasTradeToday && (
          <TableRow className="hover:bg-transparent">
            <TableCell colSpan={8} className="py-3">
              <div className="rounded-md border border-gain/50 bg-gain/10 px-3 py-2 text-center text-sm font-medium text-gain">
                No rebalancing today — your holdings already match today&apos;s top 5.
              </div>
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
