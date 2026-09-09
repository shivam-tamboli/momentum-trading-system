'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { AccountSummary } from '@/components/AccountSummary';
import { AccountActivityChart } from '@/components/AccountActivityChart';
import { AlgorithmStatusCard } from '@/components/AlgorithmStatusCard';
import { DailyRecommendationsTable } from '@/components/DailyRecommendationsTable';
import { TradeHistoryTable } from '@/components/TradeHistoryTable';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import type {
  AccountResponse,
  DailyRecommendationItem,
  DailyTradeItem,
  SelectableIndex,
} from '@/lib/types';
import { INDEX_RECOMMENDATION_PATH } from '@/lib/types';

export default function DashboardPage() {
  const {
    userId,
    selectedIndex,
    hasAlpacaKey,
    investmentAmount,
    isLoading: isUserLoading,
  } = useUser();

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

  const missingSetup = [
    !hasAlpacaKey && 'connect your Alpaca account',
    investmentAmount == null && 'set an investment amount',
    !selectedIndex && 'choose an index',
  ].filter((item): item is string => Boolean(item));

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Dashboard</h1>

      {missingSetup.length > 0 && (
        <div className="rounded-md border border-pending/50 bg-pending/10 px-4 py-3 text-sm">
          <p className="font-medium text-pending">Nothing will trade yet</p>
          <p className="mt-1 text-muted-foreground">
            You still need to {missingSetup.join(', ')}.{' '}
            <Link href="/settings" className="font-medium text-primary hover:underline">
              Finish setup in Settings →
            </Link>
          </p>
        </div>
      )}

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
          <CardDescription>
            Daily buy + sell dollar volume — not portfolio value over time. There&apos;s no
            historical value snapshot to chart yet.
          </CardDescription>
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
