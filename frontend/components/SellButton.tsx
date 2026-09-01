'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/button';
import type { ErrorResponse, SellResponse } from '@/lib/types';

interface SellButtonProps {
  userId: number;
}

export function SellButton({ userId }: SellButtonProps) {
  const queryClient = useQueryClient();

  const sellMutation = useMutation({
    mutationFn: async () => {
      const { data } = await api.post<SellResponse>(`/${userId}/trade/sell`);
      return data;
    },
    onSuccess: (data) => {
      const failures = data.failures ?? [];

      if (data.trades.length === 0 && failures.length === 0) {
        toast.info(data.message ?? 'No positions match this week’s sell recommendations.');
        return;
      }

      if (data.trades.length > 0) {
        toast.success(`Placed ${data.trades.length} sell order(s).`);
        queryClient.invalidateQueries({ queryKey: ['account', userId] });
        queryClient.invalidateQueries({ queryKey: ['positions', userId] });
        queryClient.invalidateQueries({ queryKey: ['trades', userId] });
      }

      failures.forEach((failure) => {
        toast.warning(`Sell order failed for ${failure.symbol}: ${failure.reason}`);
      });
    },
    onError: (error) => {
      if (isAxiosError<ErrorResponse>(error)) {
        if (error.response) {
          toast.error(error.response.data?.error ?? 'Server returned an unexpected error.');
        } else {
          toast.error(
            'Could not reach the server. It may be waking up from being idle — wait a few seconds and try again.'
          );
        }
      } else {
        toast.error('Failed to place sell order.');
      }
    },
  });

  return (
    <Button
      variant="destructive"
      onClick={() => sellMutation.mutate()}
      disabled={sellMutation.isPending}
    >
      {sellMutation.isPending ? 'Selling…' : 'Sell'}
    </Button>
  );
}
