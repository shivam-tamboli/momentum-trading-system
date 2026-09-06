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
import { cn } from '@/lib/utils';
import type { DailyTradeItem } from '@/lib/types';

const ACTION_STYLES: Record<DailyTradeItem['action'], string> = {
  BUY: 'bg-green-600 text-white hover:bg-green-600',
  SELL: 'bg-red-600 text-white hover:bg-red-600',
};

const STATUS_STYLES: Record<DailyTradeItem['status'], string> = {
  FILLED: 'bg-secondary text-secondary-foreground',
  PENDING: 'bg-amber-500 text-white hover:bg-amber-500',
  FAILED: 'bg-destructive text-destructive-foreground',
};

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

function formatCurrency(value: number | null): string {
  return value === null ? '—' : currency.format(value);
}

function formatQuantity(value: number | null): string {
  return value === null ? '—' : String(value);
}

interface TradeHistoryTableProps {
  trades: DailyTradeItem[] | undefined;
  isLoading: boolean;
}

export function TradeHistoryTable({ trades, isLoading }: TradeHistoryTableProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Symbol</TableHead>
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
              {Array.from({ length: 7 }).map((__, j) => (
                <TableCell key={j}>
                  <Skeleton className="h-4 w-full" />
                </TableCell>
              ))}
            </TableRow>
          ))}

        {!isLoading && (!trades || trades.length === 0) && (
          <TableRow>
            <TableCell colSpan={7} className="text-center text-muted-foreground">
              No trades yet.
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          trades?.map((trade, index) => (
            <TableRow
              key={`${trade.symbol}-${trade.traded_at}-${index}`}
              className={cn(trade.status === 'PENDING' && 'bg-amber-500/10')}
            >
              <TableCell className="font-medium">{trade.symbol}</TableCell>
              <TableCell>
                <Badge className={cn(ACTION_STYLES[trade.action])}>{trade.action}</Badge>
              </TableCell>
              <TableCell>
                <Badge className={cn(STATUS_STYLES[trade.status])}>
                  {trade.status === 'PENDING' ? 'Pending — still processing' : trade.status}
                </Badge>
              </TableCell>
              <TableCell className="text-right">{formatCurrency(trade.amount)}</TableCell>
              <TableCell className="text-right">{formatCurrency(trade.price_per_share)}</TableCell>
              <TableCell className="text-right">{formatQuantity(trade.quantity)}</TableCell>
              <TableCell className="text-muted-foreground">
                {dateTimeFormatter.format(new Date(trade.traded_at))}
              </TableCell>
            </TableRow>
          ))}
      </TableBody>
    </Table>
  );
}
