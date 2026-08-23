'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { Card, CardContent } from '@/components/ui/card';
import { TradeHistoryTable } from '@/components/TradeHistoryTable';
import type { TradeHistoryItem } from '@/lib/types';

export default function TradesPage() {
  const { userId, isLoading: isUserLoading } = useUser();

  const tradesQuery = useQuery({
    queryKey: ['trades', userId],
    queryFn: async () => {
      const { data } = await api.get<TradeHistoryItem[]>(`/${userId}/trades`);
      return data;
    },
    enabled: userId !== null,
  });

  if (isUserLoading || userId === null) {
    return <p className="text-sm text-muted-foreground">Loading trade history…</p>;
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Trade History</h1>

      <Card>
        <CardContent>
          <TradeHistoryTable trades={tradesQuery.data} isLoading={tradesQuery.isLoading} />
        </CardContent>
      </Card>
    </div>
  );
}
