'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardContent } from '@/components/ui/card';
import { DailyRecommendationsTable } from '@/components/DailyRecommendationsTable';
import type { DailyRecommendationItem, SelectableIndex } from '@/lib/types';
import { INDEX_RECOMMENDATION_PATH } from '@/lib/types';

const INDEX_TABS: { value: SelectableIndex; label: string }[] = [
  { value: 'S&P 500', label: 'S&P 500' },
  { value: 'S&P 400', label: 'S&P 400' },
  { value: 'S&P 600', label: 'S&P 600' },
  { value: 'NASDAQ 100', label: 'Nasdaq 100' },
  { value: 'FULL_MARKET', label: 'Full Market' },
];

function IndexTabContent({ index }: { index: SelectableIndex }) {
  const path = `/recommendations/${INDEX_RECOMMENDATION_PATH[index]}`;
  const query = useQuery({
    queryKey: ['recommendations', path],
    queryFn: async () => {
      const { data } = await api.get<DailyRecommendationItem[]>(path);
      return data;
    },
  });

  return (
    <Card>
      <CardContent>
        <DailyRecommendationsTable recommendations={query.data} isLoading={query.isLoading} />
      </CardContent>
    </Card>
  );
}

export default function RecommendationsPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Recommendations</h1>

      <Tabs defaultValue="S&P 500">
        <TabsList>
          {INDEX_TABS.map((tab) => (
            <TabsTrigger key={tab.value} value={tab.value}>
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>

        {INDEX_TABS.map((tab) => (
          <TabsContent key={tab.value} value={tab.value} className="mt-4">
            <IndexTabContent index={tab.value} />
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
