import { TrendingUp } from 'lucide-react';
import { EmptyState } from '@/components/EmptyState';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { BenchmarkResponse } from '@/lib/types';

interface BenchmarkComparisonProps {
  benchmark: BenchmarkResponse | undefined;
  isLoading: boolean;
}

const percent = new Intl.NumberFormat('en-US', {
  style: 'percent',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  signDisplay: 'always',
});

const points = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const periodStartFormatter = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' });

// period_start is a plain calendar date ("2026-08-01"), not a UTC instant — parsing it with
// `new Date(string)` treats it as UTC midnight, which can roll back a day in the formatter's
// local timezone. Building the Date from explicit Y/M/D parts instead sidesteps that entirely.
function formatPeriodStart(periodStart: string): string {
  const [year, month, day] = periodStart.split('-').map(Number);
  return periodStartFormatter.format(new Date(year, month - 1, day));
}

export function BenchmarkComparison({ benchmark, isLoading }: BenchmarkComparisonProps) {
  if (isLoading) {
    return <Skeleton className="h-32 w-full" />;
  }

  if (!benchmark || !benchmark.selected_index) {
    return (
      <div className="flex h-32 items-center justify-center">
        <EmptyState icon={TrendingUp} message="Choose an index in Settings to see how you compare." />
      </div>
    );
  }

  if (!benchmark.index_symbol) {
    return (
      <div className="flex h-32 items-center justify-center">
        <EmptyState
          icon={TrendingUp}
          message={`No single benchmark ETF exists for ${benchmark.selected_index}.`}
        />
      </div>
    );
  }

  if (benchmark.portfolio_return_percent == null || benchmark.index_return_percent == null) {
    return (
      <div className="flex h-32 items-center justify-center">
        <EmptyState icon={TrendingUp} message="Not enough portfolio history yet to compare." />
      </div>
    );
  }

  const { portfolio_return_percent: portfolioPct, index_return_percent: indexPct } = benchmark;
  const diffPts = (portfolioPct - indexPct) * 100;
  const isBeating = diffPts >= 0;

  return (
    <div className="space-y-4">
      <p
        className={cn(
          'rounded-md border px-3 py-2 text-sm font-medium',
          isBeating
            ? 'border-gain/50 bg-gain/10 text-gain'
            : 'border-loss/50 bg-loss/10 text-loss'
        )}
      >
        {isBeating ? '▲ Beating' : '▼ Trailing'} {benchmark.selected_index} ({benchmark.index_symbol}) by{' '}
        {points.format(Math.abs(diffPts))} points
        {benchmark.period_start && ` since ${formatPeriodStart(benchmark.period_start)}`}
      </p>

      <div className="grid grid-cols-2 gap-4">
        <div className="rounded-md border border-border bg-card px-4 py-3">
          <p className="text-xs text-muted-foreground uppercase tracking-wide">Your Portfolio</p>
          <p
            className={cn(
              'mt-1 font-mono text-2xl font-bold tabular-nums',
              portfolioPct >= 0 ? 'text-gain' : 'text-loss'
            )}
          >
            {percent.format(portfolioPct)}
          </p>
        </div>
        <div className="rounded-md border border-border bg-card px-4 py-3">
          <p className="text-xs text-muted-foreground uppercase tracking-wide">
            {benchmark.selected_index} ({benchmark.index_symbol})
          </p>
          <p
            className={cn(
              'mt-1 font-mono text-2xl font-bold tabular-nums',
              indexPct >= 0 ? 'text-gain' : 'text-loss'
            )}
          >
            {percent.format(indexPct)}
          </p>
        </div>
      </div>
    </div>
  );
}
