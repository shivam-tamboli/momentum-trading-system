'use client';

import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
  const queryClient = useQueryClient();
  const [isRunningAlgorithm, setIsRunningAlgorithm] = useState(false);
  const [algorithmMessage, setAlgorithmMessage] = useState<{
    type: 'success' | 'error';
    text: string;
  } | null>(null);
  const clearMessageTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (clearMessageTimeoutRef.current) {
        clearTimeout(clearMessageTimeoutRef.current);
      }
    };
  }, []);

  const handleRunAlgorithm = async () => {
    if (clearMessageTimeoutRef.current) {
      clearTimeout(clearMessageTimeoutRef.current);
      clearMessageTimeoutRef.current = null;
    }
    setAlgorithmMessage(null);
    setIsRunningAlgorithm(true);
    try {
      await axios.post(`${process.env.NEXT_PUBLIC_API_BASE_URL}/admin/run-algorithm`);
      toast.success('Algorithm completed! Refresh recommendations to see results.');
      setAlgorithmMessage({
        type: 'success',
        text: '✅ Algorithm completed! New recommendations are ready. Check the Recommendations page.',
      });
      await queryClient.invalidateQueries({ queryKey: ['recommendations'] });
      clearMessageTimeoutRef.current = setTimeout(() => {
        setAlgorithmMessage(null);
        clearMessageTimeoutRef.current = null;
      }, 10000);
    } catch (error) {
      toast.error('Failed to run algorithm.');
      setAlgorithmMessage({ type: 'error', text: '❌ Algorithm failed. Please try again.' });
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
        <div className="flex items-start gap-2">
          <BuyDialog userId={userId} />
          <SellButton userId={userId} />
          <div className="flex flex-col items-end gap-1">
            <button
              onClick={handleRunAlgorithm}
              disabled={isRunningAlgorithm}
              className="inline-flex h-9 items-center justify-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:pointer-events-none disabled:opacity-50"
            >
              {isRunningAlgorithm ? 'Running...' : 'Run Algorithm'}
            </button>
            {isRunningAlgorithm && (
              <p className="max-w-xs text-right text-xs text-muted-foreground">
                Fetching market data and calculating momentum scores... This takes 2-3 minutes.
              </p>
            )}
            {!isRunningAlgorithm && algorithmMessage && (
              <p
                className={`max-w-xs text-right text-xs ${
                  algorithmMessage.type === 'success' ? 'text-green-600' : 'text-red-600'
                }`}
              >
                {algorithmMessage.text}
              </p>
            )}
          </div>
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
