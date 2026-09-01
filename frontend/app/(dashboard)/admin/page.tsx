'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { Badge } from '@/components/ui/badge';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { AlgorithmStats, MetricsResponse } from '@/lib/types';

const REFRESH_INTERVAL_MS = 30000;

const HEALTH_STYLES: Record<string, string> = {
  UP: 'bg-green-600 text-white hover:bg-green-600',
  DOWN: 'bg-red-600 text-white hover:bg-red-600',
};

const ALGORITHM_STATUS_STYLES: Record<AlgorithmStats['status'], string> = {
  NEVER_RUN: 'bg-muted text-muted-foreground hover:bg-muted',
  RUNNING: 'bg-amber-500 text-white hover:bg-amber-500',
  SUCCESS: 'bg-green-600 text-white hover:bg-green-600',
  FAILED: 'bg-red-600 text-white hover:bg-red-600',
};

function StatCard({
  label,
  value,
  isLoading,
}: {
  label: string;
  value: React.ReactNode;
  isLoading: boolean;
}) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? <Skeleton className="h-8 w-24" /> : <p className="text-2xl font-bold">{value}</p>}
      </CardContent>
    </Card>
  );
}

function formatDuration(durationMs: number | null): string {
  if (durationMs === null) return '—';
  return `${(durationMs / 1000).toFixed(1)}s`;
}

function formatTimestamp(timestamp: string | null): string {
  if (!timestamp) return 'Never';
  return new Date(timestamp).toLocaleString();
}

export default function AdminMetricsPage() {
  const metricsQuery = useQuery({
    queryKey: ['admin-metrics'],
    queryFn: async () => {
      const { data } = await api.get<MetricsResponse>('/admin/metrics');
      return data;
    },
    refetchInterval: REFRESH_INTERVAL_MS,
  });

  const metrics = metricsQuery.data;
  const isLoading = metricsQuery.isLoading;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">System Metrics</h1>
          <p className="text-sm text-muted-foreground">Auto-refreshes every 30 seconds.</p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>System Health</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <Skeleton className="h-6 w-20" />
          ) : (
            <Badge className={cn(HEALTH_STYLES[metrics?.health.status ?? 'DOWN'])}>
              {metrics?.health.status ?? 'UNKNOWN'}
            </Badge>
          )}
        </CardContent>
      </Card>

      <div>
        <h2 className="mb-3 text-lg font-semibold">Algorithm Performance</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">Status</CardTitle>
            </CardHeader>
            <CardContent>
              {isLoading ? (
                <Skeleton className="h-6 w-20" />
              ) : (
                <Badge className={cn(ALGORITHM_STATUS_STYLES[metrics?.algorithm.status ?? 'NEVER_RUN'])}>
                  {metrics?.algorithm.status ?? 'NEVER_RUN'}
                </Badge>
              )}
            </CardContent>
          </Card>
          <StatCard
            label="Last Run"
            value={formatTimestamp(metrics?.algorithm.last_run_at ?? null)}
            isLoading={isLoading}
          />
          <StatCard
            label="Duration"
            value={formatDuration(metrics?.algorithm.duration_ms ?? null)}
            isLoading={isLoading}
          />
          <StatCard
            label="Stocks Scored"
            value={metrics?.algorithm.stocks_scored ?? '—'}
            isLoading={isLoading}
          />
        </div>
        {metrics?.algorithm.status === 'FAILED' && metrics.algorithm.last_error && (
          <p className="mt-3 text-sm text-red-600">Last error: {metrics.algorithm.last_error}</p>
        )}
      </div>

      <div>
        <h2 className="mb-3 text-lg font-semibold">Trading Statistics</h2>
        <div className="grid gap-4 sm:grid-cols-3">
          <StatCard label="Total Trades" value={metrics?.trading.total_trades ?? '—'} isLoading={isLoading} />
          <StatCard label="Buy Count" value={metrics?.trading.buy_count ?? '—'} isLoading={isLoading} />
          <StatCard label="Sell Count" value={metrics?.trading.sell_count ?? '—'} isLoading={isLoading} />
        </div>
      </div>

      <div>
        <h2 className="mb-3 text-lg font-semibold">Database Statistics</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <StatCard label="Stock Count" value={metrics?.database.stock_count ?? '—'} isLoading={isLoading} />
          <StatCard
            label="Recommendation Count"
            value={metrics?.database.recommendation_count ?? '—'}
            isLoading={isLoading}
          />
        </div>
      </div>
    </div>
  );
}
