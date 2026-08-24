'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import { toast } from 'sonner';
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
  const [isRunningAlgorithm, setIsRunningAlgorithm] = useState(false);

  const handleRunAlgorithm = async () => {
    setIsRunningAlgorithm(true);
    try {
      await axios.post(`${process.env.NEXT_PUBLIC_API_BASE_URL}/admin/run-algorithm`);
      toast.success('Algorithm completed! Refresh recommendations to see results.');
    } catch (error) {
      toast.error('Failed to run algorithm.');
    } finally {
      setIsRunningAlgorithm(false);
    }
  };

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
          <button
            onClick={handleRunAlgorithm}
            disabled={isRunningAlgorithm}
            className="inline-flex h-9 items-center justify-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:pointer-events-none disabled:opacity-50"
          >
            {isRunningAlgorithm ? 'Running...' : 'Run Algorithm'}
          </button>
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
