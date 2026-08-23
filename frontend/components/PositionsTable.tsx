import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { Position } from '@/lib/types';

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

interface PositionsTableProps {
  positions: Position[] | undefined;
  isLoading: boolean;
}

export function PositionsTable({ positions, isLoading }: PositionsTableProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Symbol</TableHead>
          <TableHead className="text-right">Qty</TableHead>
          <TableHead className="text-right">Avg Entry Price</TableHead>
          <TableHead className="text-right">Current Price</TableHead>
          <TableHead className="text-right">Unrealized P/L</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {isLoading &&
          Array.from({ length: 3 }).map((_, i) => (
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
            <TableCell colSpan={5} className="text-center text-muted-foreground">
              No open positions.
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          positions?.map((position) => (
            <TableRow key={position.symbol}>
              <TableCell className="font-medium">{position.symbol}</TableCell>
              <TableCell className="text-right">{position.qty}</TableCell>
              <TableCell className="text-right">
                {currency.format(position.avg_entry_price)}
              </TableCell>
              <TableCell className="text-right">
                {currency.format(position.current_price)}
              </TableCell>
              <TableCell
                className={cn(
                  'text-right font-medium',
                  position.unrealized_pl >= 0 ? 'text-green-500' : 'text-red-500'
                )}
              >
                {currency.format(position.unrealized_pl)}
              </TableCell>
            </TableRow>
          ))}
      </TableBody>
    </Table>
  );
}
