import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import type { AccountResponse } from '@/lib/types';

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

interface AccountSummaryProps {
  account: AccountResponse | undefined;
  isLoading: boolean;
}

export function AccountSummary({ account, isLoading }: AccountSummaryProps) {
  const stats = [
    { label: 'Cash', value: account?.cash },
    { label: 'Buying Power', value: account?.buying_power },
    { label: 'Portfolio Value', value: account?.portfolio_value },
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-3">
      {stats.map((stat) => (
        <Card key={stat.label}>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">
              {stat.label}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {isLoading || stat.value === undefined ? (
              <Skeleton className="h-8 w-28" />
            ) : (
              <p className="text-2xl font-bold">{currency.format(stat.value)}</p>
            )}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
