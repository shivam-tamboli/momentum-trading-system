'use client';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useCountUp } from '@/lib/useCountUp';
import { cn } from '@/lib/utils';
import type { AccountResponse } from '@/lib/types';

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

const percent = new Intl.NumberFormat('en-US', {
  style: 'percent',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

interface AccountSummaryProps {
  account: AccountResponse | undefined;
  isLoading: boolean;
}

export function AccountSummary({ account, isLoading }: AccountSummaryProps) {
  const animatedPortfolioValue = useCountUp(account?.portfolio_value);

  const hasDelta =
    account !== undefined && account.last_equity !== undefined && account.last_equity !== 0;
  const dayDelta = hasDelta ? account.portfolio_value - account.last_equity : 0;
  const dayDeltaPercent = hasDelta ? dayDelta / account.last_equity : 0;
  const isPositiveDelta = dayDelta >= 0;

  const secondaryStats = [
    { label: 'Cash', value: account?.cash },
    { label: 'Buying Power', value: account?.buying_power },
  ];

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-2">
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">
            Portfolio Value
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading || account?.portfolio_value === undefined ? (
            <Skeleton className="h-12 w-48" />
          ) : (
            <p className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
              <span className="font-mono text-4xl font-bold tracking-tight tabular-nums sm:text-5xl">
                {currency.format(animatedPortfolioValue ?? account.portfolio_value)}
              </span>
              {hasDelta && (
                <span
                  className={cn(
                    'font-mono text-lg font-semibold tabular-nums sm:text-xl',
                    isPositiveDelta ? 'text-gain' : 'text-loss'
                  )}
                >
                  ({isPositiveDelta ? '+' : '−'}
                  {currency.format(Math.abs(dayDelta))} · {isPositiveDelta ? '+' : '−'}
                  {percent.format(Math.abs(dayDeltaPercent))} today)
                </span>
              )}
            </p>
          )}
        </CardContent>
      </Card>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-1">
        {secondaryStats.map((stat) => (
          <Card key={stat.label}>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                {stat.label}
              </CardTitle>
            </CardHeader>
            <CardContent>
              {isLoading || stat.value === undefined ? (
                <Skeleton className="h-7 w-24" />
              ) : (
                <p className="font-mono text-xl font-semibold tabular-nums">
                  {currency.format(stat.value)}
                </p>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
