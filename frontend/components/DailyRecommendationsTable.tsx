import { Check, TrendingUp } from 'lucide-react';
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
import { formatRelativeDate, formatScoredAt, isStale } from '@/lib/freshness';
import type { DailyRecommendationItem } from '@/lib/types';

interface DailyRecommendationsTableProps {
  recommendations: DailyRecommendationItem[] | undefined;
  isLoading: boolean;
  // Symbols the user currently holds — lets a row show "already held" next to the recommendation
  // instead of leaving it ambiguous whether this stock is about to be bought or already is one.
  // Optional: the standalone Recommendations page doesn't have positions data to pass in.
  heldSymbols?: Set<string>;
}

export function DailyRecommendationsTable({
  recommendations,
  isLoading,
  heldSymbols,
}: DailyRecommendationsTableProps) {
  // All rows for a given filter come from the same scoring run, so the first row's timestamp
  // represents the whole set — if scoring failed today, the safe-wipe guard on the backend keeps
  // yesterday's rows rather than showing nothing, so this is the only signal the frontend has that
  // what's on screen isn't actually today's data.
  const scoredAt = recommendations && recommendations.length > 0 ? recommendations[0].scored_at : null;
  const stale = scoredAt !== null && isStale(scoredAt);

  return (
    <div className="space-y-3">
      {!isLoading && scoredAt && (
        <p className="font-mono text-xs text-muted-foreground tabular-nums">
          Last scored: {formatScoredAt(scoredAt)}
        </p>
      )}

      {!isLoading && stale && scoredAt && (
        <p className="rounded-md border border-pending/50 bg-pending/10 px-3 py-2 text-sm font-medium text-pending">
          ⚠️ Recommendations are from {formatRelativeDate(scoredAt)}. Today&apos;s scoring did
          not complete. No trades will execute today.
        </p>
      )}

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Symbol</TableHead>
            <TableHead>Name</TableHead>
            <TableHead>Momentum Score</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading &&
            Array.from({ length: 5 }).map((_, i) => (
              <TableRow key={i}>
                {Array.from({ length: 3 }).map((__, j) => (
                  <TableCell key={j}>
                    <Skeleton className="h-4 w-full" />
                  </TableCell>
                ))}
              </TableRow>
            ))}

          {!isLoading && (!recommendations || recommendations.length === 0) && (
            <TableRow>
              <TableCell colSpan={3}>
                <EmptyState icon={TrendingUp} message="No recommendations for today yet." />
              </TableCell>
            </TableRow>
          )}

          {!isLoading &&
            recommendations?.map((rec) => {
              const isPositive = rec.momentum_score >= 0;
              return (
                <TableRow key={rec.symbol} className="transition-colors hover:bg-primary/5">
                  <TableCell className="font-medium">
                    <span className="flex items-center gap-2">
                      {rec.symbol}
                      {heldSymbols?.has(rec.symbol) && (
                        <Badge variant="outline" className="gap-1 border-gain/30 bg-gain/10 text-gain">
                          <Check className="h-3 w-3" />
                          Held
                        </Badge>
                      )}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{rec.name}</TableCell>
                  <TableCell>
                    <span
                      className={cn(
                        'font-mono text-sm font-semibold tabular-nums',
                        isPositive ? 'text-gain' : 'text-loss'
                      )}
                    >
                      {isPositive ? '▲ ' : '▼ '}
                      {rec.momentum_score.toFixed(4)}
                    </span>
                  </TableCell>
                </TableRow>
              );
            })}
        </TableBody>
      </Table>
    </div>
  );
}
