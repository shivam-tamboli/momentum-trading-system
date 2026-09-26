import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { ErrorState } from '@/components/ErrorState';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import {
  formatFullDateTime,
  formatNextRun,
  formatRelativeDate,
  getNextScoringRun,
  isStale,
} from '@/lib/freshness';
import { getScoringState } from '@/lib/engine-status';
import type { DailyRecommendationItem, EngineStatus } from '@/lib/types';

interface AlgorithmStatusCardProps {
  recommendations: DailyRecommendationItem[] | undefined;
  engineStatus: EngineStatus | undefined;
  isLoading: boolean;
  isError?: boolean;
}

const STATUS_STYLES = {
  live: 'border-gain/50 bg-gain/10 text-gain',
  pending: 'border-pending/50 bg-pending/10 text-pending',
  'market-closed': 'border-muted-foreground/30 bg-muted/30 text-muted-foreground',
};

export function AlgorithmStatusCard({ recommendations, engineStatus, isLoading, isError }: AlgorithmStatusCardProps) {
  const scoredAt = recommendations && recommendations.length > 0 ? recommendations[0].scored_at : null;
  // engine-status is the source of truth; if it's ever unreachable, fall back to the old
  // elapsed-time heuristic rather than showing nothing.
  const stale = scoredAt !== null && isStale(scoredAt);
  const scoringState = engineStatus ? getScoringState(engineStatus) : stale ? 'pending' : 'live';

  return (
    <Card>
      <CardHeader>
        <CardTitle>Algorithm Status</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading ? (
          <Skeleton className="h-20 w-full" />
        ) : isError ? (
          <ErrorState message="Couldn't load algorithm status." />
        ) : !scoredAt ? (
          <p className="text-sm text-muted-foreground">The algorithm hasn&apos;t run yet.</p>
        ) : (
          <>
            <div
              className={cn(
                'rounded-lg border px-4 py-3 text-sm font-semibold',
                STATUS_STYLES[scoringState]
              )}
            >
              {scoringState === 'live' ? (
                <span className="flex items-center gap-2.5">
                  <span className="relative flex h-2.5 w-2.5">
                    <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-gain opacity-75" />
                    <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-gain" />
                  </span>
                  Today&apos;s scores are live
                </span>
              ) : scoringState === 'market-closed' ? (
                <>🌙 Market is closed today — showing scores from {formatRelativeDate(scoredAt)}.</>
              ) : (
                <>
                  ⚠️ Scores are from {formatRelativeDate(scoredAt)} — today&apos;s algorithm has
                  not run yet.
                </>
              )}
            </div>

            <div className="flex flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between">
              <p className="text-sm text-muted-foreground">
                Last ran:{' '}
                <span className="font-mono text-sm font-semibold text-foreground tabular-nums">
                  {formatFullDateTime(scoredAt)}
                </span>
              </p>
              <p className="text-xs text-muted-foreground/80">
                Next run:{' '}
                <span className="font-mono text-xs font-medium text-muted-foreground tabular-nums">
                  {formatNextRun(getNextScoringRun())}
                </span>
              </p>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
