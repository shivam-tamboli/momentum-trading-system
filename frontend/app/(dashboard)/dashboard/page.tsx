'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { AccountSummary } from '@/components/AccountSummary';
import { PositionsTable } from '@/components/PositionsTable';
import { BuyDialog } from '@/components/BuyDialog';
import { SellButton } from '@/components/SellButton';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import type { AccountResponse, Position } from '@/lib/types';

export default function DashboardPage() {
  const { userId, isLoading: isUserLoading } = useUser();

  const accountQuery = useQuery({
    queryKey: ['account', userId],
    queryFn: async () => {
      const { data } = await api.get<AccountResponse>(`/${userId}/account`);
      return data;
    },
    enabled: userId !== null,
  });

  const positionsQuery = useQuery({
    queryKey: ['positions', userId],
    queryFn: async () => {
      const { data } = await api.get<Position[]>(`/${userId}/positions`);
      return data;
    },
    enabled: userId !== null,
  });

  if (isUserLoading || userId === null) {
    return <p className="text-sm text-muted-foreground">Loading account…</p>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <div className="flex gap-2">
          <BuyDialog userId={userId} />
          <SellButton userId={userId} />
        </div>
      </div>

      <AccountSummary account={accountQuery.data} isLoading={accountQuery.isLoading} />

      <Card>
        <CardHeader>
          <CardTitle>Positions</CardTitle>
        </CardHeader>
        <CardContent>
          <PositionsTable positions={positionsQuery.data} isLoading={positionsQuery.isLoading} />
        </CardContent>
      </Card>
    </div>
  );
}
