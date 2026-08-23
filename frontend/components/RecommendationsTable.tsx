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
import type { Recommendation } from '@/lib/types';

const ACTION_STYLES: Record<Recommendation['action'], string> = {
  BUY: 'bg-green-600 text-white hover:bg-green-600',
  SELL: 'bg-red-600 text-white hover:bg-red-600',
  HOLD: 'bg-amber-500 text-white hover:bg-amber-500',
};

interface RecommendationsTableProps {
  recommendations: Recommendation[] | undefined;
  isLoading: boolean;
}

export function RecommendationsTable({ recommendations, isLoading }: RecommendationsTableProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Symbol</TableHead>
          <TableHead>Name</TableHead>
          <TableHead>Momentum Score</TableHead>
          <TableHead>Action</TableHead>
          <TableHead>Week</TableHead>
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

        {!isLoading && (!recommendations || recommendations.length === 0) && (
          <TableRow>
            <TableCell colSpan={5} className="text-center text-muted-foreground">
              No recommendations for this week yet.
            </TableCell>
          </TableRow>
        )}

        {!isLoading &&
          recommendations?.map((rec) => (
            <TableRow key={rec.symbol}>
              <TableCell className="font-medium">{rec.symbol}</TableCell>
              <TableCell className="text-muted-foreground">{rec.name}</TableCell>
              <TableCell>
                <Badge variant="secondary" className="font-mono">
                  {rec.momentum_score.toFixed(4)}
                </Badge>
              </TableCell>
              <TableCell>
                <Badge className={cn(ACTION_STYLES[rec.action])}>{rec.action}</Badge>
              </TableCell>
              <TableCell className="text-muted-foreground">{rec.week_date}</TableCell>
            </TableRow>
          ))}
      </TableBody>
    </Table>
  );
}
