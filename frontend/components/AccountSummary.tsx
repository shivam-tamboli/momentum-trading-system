'use client';

import { Card, CardContent } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { ErrorState } from '@/components/ErrorState';
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
  isError?: boolean;
}

export function AccountSummary({ account, isLoading, isError }: AccountSummaryProps) {
  const animatedPortfolioValue = useCountUp(account?.portfolio_value);

  const hasDelta =
    account !== undefined && account.last_equity !== undefined && account.last_equity !== 0;
  const dayDelta = hasDelta ? account.portfolio_value - account.last_equity : 0;
  const dayDeltaPercent = hasDelta ? dayDelta / account.last_equity : 0;
  const isPositiveDelta = dayDelta >= 0;

  if (isError) {
    return (
      <Card>
        <CardContent className="pt-6">
          <ErrorState message="Couldn't load your account summary." />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-5 sm:flex-row sm:items-center sm:gap-6">
        <div className="flex-1">
          <p className="text-sm font-medium text-muted-foreground">Portfolio Value</p>
          {isLoading || account?.portfolio_value === undefined ? (
            <Skeleton className="mt-2 h-12 w-48" />
          ) : (
            <p className="mt-1 flex flex-wrap items-baseline gap-x-2 gap-y-1">
              <span className="font-mono text-5xl font-bold tracking-tight tabular-nums sm:text-6xl">
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
        </div>

        <Separator className="sm:hidden" />
        <Separator orientation="vertical" className="hidden self-stretch sm:block" />

        <div className="sm:w-40 sm:shrink-0">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Cash</p>
          {isLoading || account?.cash === undefined ? (
            <Skeleton className="mt-2 h-6 w-20" />
          ) : (
            <p className="mt-1 font-mono text-xl font-medium text-muted-foreground tabular-nums">
              {currency.format(account.cash)}
            </p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
