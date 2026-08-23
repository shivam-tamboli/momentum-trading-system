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
import type { TradeHistoryItem } from '@/lib/types';

const ACTION_STYLES: Record<TradeHistoryItem['action'], string> = {
  BUY: 'bg-green-600 text-white hover:bg-green-600',
  SELL: 'bg-red-600 text-white hover:bg-red-600',
};

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

interface TradeHistoryTableProps {
  trades: TradeHistoryItem[] | undefined;
  isLoading: boolean;
}

export function TradeHistoryTable({ trades, isLoading }: TradeHistoryTableProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Symbol</TableHead>
          <TableHead>Action</TableHead>
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
              {Array.from({ length: 6 }).map((__, j) => (
                <TableCell key={j}>
                  <Skeleton className="h-4 w-full" />
                </TableCell>
              ))}
            </TableRow>
          ))}

        {!isLoading && (!trades || trades.length === 0) && (
          <TableRow>
            <TableCell colSpan={6} className="text-center text-muted-foreground">
              No trades yet.
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          trades?.map((trade, index) => (
            <TableRow key={`${trade.symbol}-${trade.traded_at}-${index}`}>
              <TableCell className="font-medium">{trade.symbol}</TableCell>
              <TableCell>
                <Badge className={cn(ACTION_STYLES[trade.action])}>{trade.action}</Badge>
              </TableCell>
              <TableCell className="text-right">{currency.format(trade.amount)}</TableCell>
              <TableCell className="text-right">
                {currency.format(trade.price_per_share)}
              </TableCell>
              <TableCell className="text-right">{trade.quantity}</TableCell>
              <TableCell className="text-muted-foreground">
                {dateTimeFormatter.format(new Date(trade.traded_at))}
              </TableCell>
            </TableRow>
          ))}
      </TableBody>
    </Table>
  );
}
