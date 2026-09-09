import { Briefcase } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import { cn } from '@/lib/utils';
import type { PositionItem } from '@/lib/types';

const currency = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const percent = new Intl.NumberFormat('en-US', {
  style: 'percent',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

interface PositionsTableProps {
  positions: PositionItem[] | undefined;
  isLoading: boolean;
}

export function PositionsTable({ positions, isLoading }: PositionsTableProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Symbol</TableHead>
          <TableHead className="text-right">Quantity</TableHead>
          <TableHead className="text-right">Avg Entry</TableHead>
          <TableHead className="text-right">Current Price</TableHead>
          <TableHead className="text-right">Unrealized P&amp;L</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {isLoading &&
          Array.from({ length: 5 }).map((_, i) => (
            <TableRow key={i}>
              {Array.from({ length: 5 }).map((__, j) => (
                <TableCell key={j}>
                  <Skeleton className="h-4 w-full" />
                </TableCell>
              ))}
            </TableRow>
          ))}

        {!isLoading && (!positions || positions.length === 0) && (
          <TableRow>
            <TableCell colSpan={5}>
              <EmptyState icon={Briefcase} message="No open positions." />
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          positions?.map((position) => {
            const isPositive = position.unrealized_pl >= 0;
            return (
              <TableRow key={position.symbol}>
                <TableCell className="font-medium">{position.symbol}</TableCell>
                <TableCell className="text-right font-mono tabular-nums">
                  {position.qty}
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums">
                  {currency.format(position.avg_entry_price)}
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums">
                  {currency.format(position.current_price)}
                </TableCell>
                <TableCell
                  className={cn(
                    'text-right font-mono font-semibold tabular-nums',
                    isPositive ? 'text-gain' : 'text-loss'
                  )}
                >
                  {isPositive ? '▲ ' : '▼ '}
                  {currency.format(Math.abs(position.unrealized_pl))} (
                  {percent.format(Math.abs(position.unrealized_pl_percent))})
                </TableCell>
              </TableRow>
            );
          })}
      </TableBody>
    </Table>
  );
}
