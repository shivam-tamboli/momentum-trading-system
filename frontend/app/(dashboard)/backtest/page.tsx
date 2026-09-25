'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { BacktestChart } from '@/components/BacktestChart';
import { InfoTooltip } from '@/components/InfoTooltip';
import { cn } from '@/lib/utils';
import { INDEX_RECOMMENDATION_PATH } from '@/lib/types';
import type { BacktestPoint, BacktestResponse, SelectableIndex } from '@/lib/types';

const INDEX_TABS: { value: SelectableIndex; label: string }[] = [
  { value: 'S&P 500', label: 'S&P 500' },
  { value: 'S&P 400', label: 'S&P 400' },
  { value: 'S&P 600', label: 'S&P 600' },
  { value: 'NASDAQ 100', label: 'Nasdaq 100' },
  { value: 'FULL_MARKET', label: 'Full Market' },
];

const percent = new Intl.NumberFormat('en-US', {
  style: 'percent',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  signDisplay: 'always',
});

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 0,
});

const DEFAULT_AMOUNT = 10000;

// The backend and the daily Python job never compute or store a dollar figure — only the
// normalized (base-100) growth curve. Every dollar amount on this page is this one calculation,
// done client-side, applied fresh whenever the user changes the starting amount.
function scaleToAmount(points: BacktestPoint[] | undefined, amount: number): BacktestPoint[] | undefined {
  const scale = amount / 100;
  return points?.map((p) => ({
    ...p,
    portfolio_value: p.portfolio_value * scale,
    benchmark_value: p.benchmark_value * scale,
  }));
}

interface StatCardProps {
  label: string;
  tooltip: string;
  value: number | null;
  dollarValue?: number | null;
  isLoading: boolean;
  isDiff?: boolean;
}

function StatCard({ label, tooltip, value, dollarValue, isLoading, isDiff }: StatCardProps) {
  const isPositive = value != null && value >= 0;

  return (
    <div
      className={cn(
        'rounded-lg border-t-4 bg-muted/20 px-4 py-4',
        value == null ? 'border-t-muted' : isPositive ? 'border-t-gain' : 'border-t-loss'
      )}
    >
      <div className="flex items-center gap-1.5">
        <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{label}</p>
        <InfoTooltip message={tooltip} />
      </div>
      {isLoading ? (
        <Skeleton className="mt-1.5 h-9 w-24" />
      ) : value == null ? (
        <p className="mt-1.5 text-sm text-muted-foreground">Not enough data yet</p>
      ) : (
        <>
          <p
            className={cn(
              'mt-1.5 font-mono text-3xl font-extrabold tabular-nums',
              isPositive ? 'text-gain' : 'text-loss'
            )}
          >
            {percent.format(value)}
          </p>
          {dollarValue != null && !isDiff && (
            <p className="mt-0.5 font-mono text-xs text-muted-foreground tabular-nums">
              {currency.format(dollarValue)}
            </p>
          )}
        </>
      )}
    </div>
  );
}

function IndexBacktestContent({ index }: { index: SelectableIndex }) {
  const [amount, setAmount] = useState(DEFAULT_AMOUNT);

  const path = `/backtest/${INDEX_RECOMMENDATION_PATH[index]}`;
  const query = useQuery({
    queryKey: ['backtest', path],
    queryFn: async () => {
      const { data } = await api.get<BacktestResponse>(path);
      return data;
    },
  });

  const points = query.data?.points;
  const first = points && points.length > 0 ? points[0] : undefined;
  const last = points && points.length > 0 ? points[points.length - 1] : undefined;

  const portfolioReturn = first && last ? last.portfolio_value / first.portfolio_value - 1 : null;
  const benchmarkReturn = first && last ? last.benchmark_value / first.benchmark_value - 1 : null;
  const outperformance =
    portfolioReturn != null && benchmarkReturn != null ? portfolioReturn - benchmarkReturn : null;

  const scale = amount / 100;
  const portfolioDollarValue = last ? last.portfolio_value * scale : null;
  const benchmarkDollarValue = last ? last.benchmark_value * scale : null;

  const isBeating = outperformance != null && outperformance >= 0;
  const indexLabel = index === 'FULL_MARKET' ? 'Full Market' : index;

  return (
    <div className="space-y-6">
      <Card>
        <CardContent className="pt-6">
          <Label htmlFor="backtest-amount" className="flex items-center gap-1.5">
            Starting amount
            <InfoTooltip message="Pick any amount — it only changes the dollar figures shown here, on your screen. Nothing is stored or sent anywhere; the underlying simulation is the same regardless of what you enter." />
          </Label>
          <div className="mt-2 flex items-center gap-2">
            <span className="font-mono text-sm text-muted-foreground">$</span>
            <Input
              id="backtest-amount"
              type="number"
              min={0}
              step={100}
              value={amount}
              onChange={(e) => setAmount(Math.max(0, Number(e.target.value) || 0))}
              className="max-w-[160px] font-mono"
            />
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-4 sm:grid-cols-3">
        <StatCard
          label="Total Return"
          tooltip="How much a portfolio that rebalanced to this index's top 5 momentum stocks every day would have grown, as a percentage, over the whole backtested period."
          value={portfolioReturn}
          dollarValue={portfolioDollarValue}
          isLoading={query.isLoading}
        />
        <StatCard
          label={`Benchmark Return${query.data?.etf_symbol ? ` (${query.data.etf_symbol})` : ''}`}
          tooltip="How much simply holding this index's tracking ETF the whole time would have grown, as a percentage, over the same period — the baseline the strategy is compared against."
          value={benchmarkReturn}
          dollarValue={benchmarkDollarValue}
          isLoading={query.isLoading}
        />
        <StatCard
          label={isBeating ? 'Outperformance' : 'Underperformance'}
          tooltip="The difference between the strategy's return and the benchmark's return, in percentage points. Positive means the momentum strategy beat just holding the index; negative means it trailed."
          value={outperformance}
          isLoading={query.isLoading}
          isDiff
        />
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-1.5">
            <CardTitle>Portfolio Value Over Time</CardTitle>
            <InfoTooltip message="Both lines start at your chosen amount and show what it would have grown to — the solid line is the momentum strategy rebalancing daily, the dashed line is just holding the benchmark ETF the whole time. This is a simulation based on historical prices, not a record of real trades." />
          </div>
          <CardDescription>
            {query.data?.etf_symbol ? `${indexLabel} top 5 vs. ${query.data.etf_symbol}` : 'Loading…'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <BacktestChart
            points={scaleToAmount(points, amount)}
            etfSymbol={query.data?.etf_symbol}
            isLoading={query.isLoading}
            isError={query.isError}
          />
        </CardContent>
      </Card>
    </div>
  );
}

export default function BacktestPage() {
  const { selectedIndex } = useUser();

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <h1 className="text-2xl font-bold">Backtest</h1>
        <InfoTooltip message="A simulation of how this app's momentum strategy would have performed over the last 2 years, computed daily from real historical prices — not a live account and not a guarantee of future results." />
      </div>

      <Tabs defaultValue={selectedIndex ?? 'S&P 500'}>
        <TabsList>
          {INDEX_TABS.map((tab) => {
            const isTracked = selectedIndex === tab.value;
            return (
              <TabsTrigger key={tab.value} value={tab.value} className="gap-1.5">
                {isTracked && <span className="h-1.5 w-1.5 rounded-full bg-primary" />}
                {tab.label}
              </TabsTrigger>
            );
          })}
        </TabsList>

        {INDEX_TABS.map((tab) => (
          <TabsContent key={tab.value} value={tab.value} className="mt-4">
            <IndexBacktestContent index={tab.value} />
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
