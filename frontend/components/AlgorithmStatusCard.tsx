import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
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
}

const STATUS_STYLES = {
  live: 'border-gain/50 bg-gain/10 text-gain',
  pending: 'border-pending/50 bg-pending/10 text-pending',
  'market-closed': 'border-muted-foreground/30 bg-muted/30 text-muted-foreground',
};

export function AlgorithmStatusCard({ recommendations, engineStatus, isLoading }: AlgorithmStatusCardProps) {
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
        ) : !scoredAt ? (
          <p className="text-sm text-muted-foreground">The algorithm hasn&apos;t run yet.</p>
        ) : (
          <>
            <p className="text-sm text-muted-foreground">
              Last ran:{' '}
              <span className="font-mono font-medium text-foreground tabular-nums">
                {formatFullDateTime(scoredAt)}
              </span>
            </p>

            <div className={cn('rounded-md border px-3 py-2 text-sm font-medium', STATUS_STYLES[scoringState])}>
              {scoringState === 'live' ? (
                <span className="flex items-center gap-2">
                  <span className="relative flex h-2 w-2">
                    <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-gain opacity-75" />
                    <span className="relative inline-flex h-2 w-2 rounded-full bg-gain" />
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

            <p className="text-xs text-muted-foreground">
              Next run:{' '}
              <span className="font-mono tabular-nums">{formatNextRun(getNextScoringRun())}</span>
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}
