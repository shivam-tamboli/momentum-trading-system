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

  return (
    <div className="grid gap-4 lg:grid-cols-3 lg:items-start">
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

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">Cash</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading || account?.cash === undefined ? (
            <Skeleton className="h-8 w-24" />
          ) : (
            <p className="font-mono text-2xl font-bold tabular-nums">
              {currency.format(account.cash)}
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
