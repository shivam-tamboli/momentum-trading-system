'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { PositionsTable } from '@/components/PositionsTable';
import { Card, CardContent } from '@/components/ui/card';
import type { PositionItem } from '@/lib/types';

export default function PositionsPage() {
  const { userId } = useUser();

  const positionsQuery = useQuery({
    queryKey: ['positions', userId],
    queryFn: async () => {
      const { data } = await api.get<PositionItem[]>(`/${userId}/positions`);
      return data;
    },
    enabled: userId !== null,
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Positions</h1>

      <Card>
        <CardContent>
          <PositionsTable positions={positionsQuery.data} isLoading={positionsQuery.isLoading} />
        </CardContent>
      </Card>
    </div>
  );
}
