'use client';

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardContent } from '@/components/ui/card';
import { RecommendationsTable } from '@/components/RecommendationsTable';
import type { Recommendation } from '@/lib/types';

const INDEXES = [
  { value: 'snp500', label: 'S&P 500', path: '/recommendations/snp500' },
  { value: 'snp400', label: 'S&P 400', path: '/recommendations/snp400' },
  { value: 'snp600', label: 'S&P 600', path: '/recommendations/snp600' },
  { value: 'nasdaq100', label: 'Nasdaq 100', path: '/recommendations/nasdaq100' },
] as const;

function IndexTabContent({ path }: { path: string }) {
  const query = useQuery({
    queryKey: ['recommendations', path],
    queryFn: async () => {
      const { data } = await api.get<Recommendation[]>(path);
      return data;
    },
  });

  return (
    <Card>
      <CardContent>
        <RecommendationsTable recommendations={query.data} isLoading={query.isLoading} />
      </CardContent>
    </Card>
  );
}

export default function RecommendationsPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Recommendations</h1>

      <Tabs defaultValue="snp500">
        <TabsList>
          {INDEXES.map((index) => (
            <TabsTrigger key={index.value} value={index.value}>
              {index.label}
            </TabsTrigger>
          ))}
        </TabsList>

        {INDEXES.map((index) => (
          <TabsContent key={index.value} value={index.value} className="mt-4">
            <IndexTabContent path={index.path} />
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
