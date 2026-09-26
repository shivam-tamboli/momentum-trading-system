'use client';

import { Card, CardContent } from '@/components/ui/card';
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
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-5">
      <Card className="sm:col-span-2">
        <CardContent>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
            Portfolio Value
          </p>
          {isLoading || account?.portfolio_value === undefined ? (
            <Skeleton className="mt-2 h-10 w-40" />
          ) : (
            <p className="mt-2 font-mono text-4xl font-bold tracking-tight tabular-nums sm:text-5xl">
              {currency.format(animatedPortfolioValue ?? account.portfolio_value)}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Cash</p>
          {isLoading || account?.cash === undefined ? (
            <Skeleton className="mt-2 h-8 w-20" />
          ) : (
            <p className="mt-2 font-mono text-2xl font-bold tabular-nums">
              {currency.format(account.cash)}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
            Today&apos;s P&amp;L
          </p>
          {isLoading ? (
            <Skeleton className="mt-2 h-8 w-20" />
          ) : hasDelta ? (
            <>
              <p
                className={cn(
                  'mt-2 font-mono text-2xl font-bold tabular-nums',
                  isPositiveDelta ? 'text-gain' : 'text-loss'
                )}
              >
                {isPositiveDelta ? '+' : '−'}
                {currency.format(Math.abs(dayDelta))}
              </p>
              <p
                className={cn(
                  'font-mono text-xs font-medium tabular-nums',
                  isPositiveDelta ? 'text-gain' : 'text-loss'
                )}
              >
                {isPositiveDelta ? '+' : '−'}
                {percent.format(Math.abs(dayDeltaPercent))}
              </p>
            </>
          ) : (
            <p className="mt-2 font-mono text-2xl font-bold text-muted-foreground">—</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
            Buying Power
          </p>
          {isLoading || account?.buying_power === undefined ? (
            <Skeleton className="mt-2 h-8 w-20" />
          ) : (
            <p className="mt-2 font-mono text-2xl font-bold tabular-nums">
              {currency.format(account.buying_power)}
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
