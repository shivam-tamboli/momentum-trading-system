import { Fragment } from 'react';
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
import { formatRelativeDate } from '@/lib/freshness';
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

const timeFormatter = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });

function formatCurrency(value: number | null): string {
  return value === null ? '—' : currency.format(value);
}

function formatQuantity(value: number | null): string {
  return value === null ? '—' : String(value);
}

interface DayGroup {
  day: string;
  trades: DailyTradeItem[];
}

// Trades already arrive sorted by traded_at desc, so grouping preserves that order without
// needing to re-sort — each day's trades stay together, most recent day first.
function groupByDay(trades: DailyTradeItem[]): DayGroup[] {
  const groups = new Map<string, DailyTradeItem[]>();
  for (const trade of trades) {
    const day = trade.traded_at.slice(0, 10);
    if (!groups.has(day)) {
      groups.set(day, []);
    }
    groups.get(day)!.push(trade);
  }
  return Array.from(groups.entries()).map(([day, dayTrades]) => ({ day, trades: dayTrades }));
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

interface TradeHistoryTableProps {
  trades: DailyTradeItem[] | undefined;
  isLoading: boolean;
}

export function TradeHistoryTable({ trades, isLoading }: TradeHistoryTableProps) {
  const groups = trades ? groupByDay(trades) : [];

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
              <EmptyState icon={Receipt} message="No trades yet." />
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          groups.map((group) => {
            const { buys, sells, net } = daySummary(group.trades);
            return (
              <Fragment key={group.day}>
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={8} className="bg-muted/30 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-xs">
                      <span className="font-semibold text-foreground">
                        {formatRelativeDate(group.trades[0].traded_at)}
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

                {group.trades.map((trade, index) => {
                  const StatusIcon = STATUS_ICONS[trade.status];
                  return (
                    <TableRow
                      key={`${trade.symbol}-${trade.traded_at}-${index}`}
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
                        <Badge className={cn('gap-1', STATUS_STYLES[trade.status])}>
                          <StatusIcon className="h-3 w-3" />
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
                        {timeFormatter.format(new Date(trade.traded_at))}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </Fragment>
            );
          })}
      </TableBody>
    </Table>
  );
}
