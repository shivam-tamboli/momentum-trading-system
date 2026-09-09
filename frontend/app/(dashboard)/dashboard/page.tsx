'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { AccountSummary } from '@/components/AccountSummary';
import { PortfolioComposition } from '@/components/PortfolioComposition';
import { BenchmarkComparison } from '@/components/BenchmarkComparison';
import { IndexPriceChart } from '@/components/IndexPriceChart';
import { AlgorithmStatusCard } from '@/components/AlgorithmStatusCard';
import { DailyRecommendationsTable } from '@/components/DailyRecommendationsTable';
import { PositionsTable } from '@/components/PositionsTable';
import { TradeHistoryTable } from '@/components/TradeHistoryTable';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import type {
  AccountResponse,
  BenchmarkResponse,
  DailyRecommendationItem,
  DailyTradeItem,
  IndexPriceHistoryResponse,
  PositionItem,
  SelectableIndex,
} from '@/lib/types';
import { INDEX_RECOMMENDATION_PATH } from '@/lib/types';

const TRADE_HISTORY_PAGE_SIZE = 20;

export default function DashboardPage() {
  const {
    userId,
    selectedIndex,
    hasAlpacaKey,
    investmentAmount,
    isLoading: isUserLoading,
  } = useUser();

  const [tradeHistoryLimit, setTradeHistoryLimit] = useState(TRADE_HISTORY_PAGE_SIZE);

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

  const positionsQuery = useQuery({
    queryKey: ['positions', userId],
    queryFn: async () => {
      const { data } = await api.get<PositionItem[]>(`/${userId}/positions`);
      return data;
    },
    enabled: userId !== null,
  });

  const indexPriceQuery = useQuery({
    queryKey: ['index-price-history', selectedIndex],
    queryFn: async () => {
      const { data } = await api.get<IndexPriceHistoryResponse>('/index-price-history', {
        params: { index: selectedIndex },
      });
      return data;
    },
    enabled: selectedIndex !== null,
  });

  const benchmarkQuery = useQuery({
    queryKey: ['benchmark', userId],
    queryFn: async () => {
      const { data } = await api.get<BenchmarkResponse>(`/${userId}/benchmark`);
      return data;
    },
    enabled: userId !== null && hasAlpacaKey,
  });

  const heldSymbols = new Set(positionsQuery.data?.map((p) => p.symbol));

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

      <Card>
        <CardHeader>
          <CardTitle>Benchmark Comparison</CardTitle>
          <CardDescription>How your portfolio has performed against your tracked index.</CardDescription>
        </CardHeader>
        <CardContent>
          <BenchmarkComparison benchmark={benchmarkQuery.data} isLoading={benchmarkQuery.isLoading} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Portfolio Composition</CardTitle>
          <CardDescription>How your investment is split across your current holdings.</CardDescription>
        </CardHeader>
        <CardContent>
          <PortfolioComposition positions={positionsQuery.data} isLoading={positionsQuery.isLoading} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Your Positions</CardTitle>
        </CardHeader>
        <CardContent>
          <PositionsTable positions={positionsQuery.data} isLoading={positionsQuery.isLoading} />
        </CardContent>
      </Card>

      {selectedIndex && (
        <AlgorithmStatusCard
          recommendations={dailyRecommendationsQuery.data}
          isLoading={dailyRecommendationsQuery.isLoading}
        />
      )}

      {selectedIndex && (
        <Card>
          <CardHeader>
            <CardTitle>{selectedIndex} — 30 Day Price</CardTitle>
            <CardDescription>
              {indexPriceQuery.data?.etf_symbol ?? '—'}, the ETF tracking {selectedIndex}.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <IndexPriceChart
              points={indexPriceQuery.data?.points}
              isLoading={indexPriceQuery.isLoading}
            />
          </CardContent>
        </Card>
      )}

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
              heldSymbols={heldSymbols}
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
          <CardTitle>Trade History</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <TradeHistoryTable
            trades={dailyTradesQuery.data?.slice(0, tradeHistoryLimit)}
            isLoading={dailyTradesQuery.isLoading}
          />
          {dailyTradesQuery.data && dailyTradesQuery.data.length > tradeHistoryLimit && (
            <div className="flex justify-center">
              <Button
                type="button"
                variant="outline"
                onClick={() => setTradeHistoryLimit((n) => n + TRADE_HISTORY_PAGE_SIZE)}
              >
                Load more
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
