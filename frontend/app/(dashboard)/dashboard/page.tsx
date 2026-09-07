'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { AccountSummary } from '@/components/AccountSummary';
import { AccountActivityChart } from '@/components/AccountActivityChart';
import { AlgorithmStatusCard } from '@/components/AlgorithmStatusCard';
import { DailyRecommendationsTable } from '@/components/DailyRecommendationsTable';
import { TradeHistoryTable } from '@/components/TradeHistoryTable';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import type {
  AccountResponse,
  DailyRecommendationItem,
  DailyTradeItem,
  SelectableIndex,
} from '@/lib/types';
import { INDEX_RECOMMENDATION_PATH } from '@/lib/types';

export default function DashboardPage() {
  const { userId, selectedIndex, isLoading: isUserLoading } = useUser();

  const accountQuery = useQuery({
    queryKey: ['account', userId],
    queryFn: async () => {
      const { data } = await api.get<AccountResponse>(`/${userId}/account`);
      return data;
    },
    enabled: userId !== null,
  });

  const recommendationsPath = selectedIndex
    ? `/recommendations/${INDEX_RECOMMENDATION_PATH[selectedIndex as SelectableIndex]}`
    : null;

  const dailyRecommendationsQuery = useQuery({
    queryKey: ['daily-recommendations', recommendationsPath],
    queryFn: async () => {
      const { data } = await api.get<DailyRecommendationItem[]>(recommendationsPath!);
      return data;
    },
    enabled: recommendationsPath !== null,
  });

  const dailyTradesQuery = useQuery({
    queryKey: ['daily-trades', userId],
    queryFn: async () => {
      const { data } = await api.get<DailyTradeItem[]>(`/${userId}/daily-trades`);
      return data;
    },
    enabled: userId !== null,
  });

  if (isUserLoading || userId === null) {
    return <p className="text-sm text-muted-foreground">Loading account…</p>;
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Dashboard</h1>

      <AccountSummary account={accountQuery.data} isLoading={accountQuery.isLoading} />

      {selectedIndex && (
        <AlgorithmStatusCard
          recommendations={dailyRecommendationsQuery.data}
          isLoading={dailyRecommendationsQuery.isLoading}
        />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Trading Activity</CardTitle>
        </CardHeader>
        <CardContent>
          <AccountActivityChart
            trades={dailyTradesQuery.data}
            isLoading={dailyTradesQuery.isLoading}
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>
            Today&apos;s Top 5{selectedIndex ? ` — ${selectedIndex}` : ''}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {selectedIndex ? (
            <DailyRecommendationsTable
              recommendations={dailyRecommendationsQuery.data}
              isLoading={dailyRecommendationsQuery.isLoading}
            />
          ) : (
            <p className="text-sm text-muted-foreground">
              Choose an index in Settings to see its daily top 5 here.
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Recent Auto-Trades</CardTitle>
        </CardHeader>
        <CardContent>
          <TradeHistoryTable
            trades={dailyTradesQuery.data?.slice(0, 10)}
            isLoading={dailyTradesQuery.isLoading}
          />
        </CardContent>
      </Card>
    </div>
  );
}
