'use client';

import { FormEvent, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import type { BuyResponse, ErrorResponse } from '@/lib/types';

interface BuyDialogProps {
  userId: number;
}

export function BuyDialog({ userId }: BuyDialogProps) {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState('');
  const queryClient = useQueryClient();

  const buyMutation = useMutation({
    mutationFn: async (amountValue: number) => {
      const { data } = await api.post<BuyResponse>(`/${userId}/trade/buy`, {
        amount: amountValue,
      });
      return data;
    },
    onSuccess: (data) => {
      toast.success(`Placed ${data.trades.length} buy order(s).`);
      queryClient.invalidateQueries({ queryKey: ['account', userId] });
      queryClient.invalidateQueries({ queryKey: ['positions', userId] });
      queryClient.invalidateQueries({ queryKey: ['trades', userId] });
      setOpen(false);
      setAmount('');
    },
    onError: (error) => {
      const message = isAxiosError<ErrorResponse>(error)
        ? error.response?.data?.error ?? 'Failed to place buy order.'
        : 'Failed to place buy order.';
      toast.error(message);
    },
  });

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const amountValue = Number(amount);
    if (!amountValue || amountValue <= 0) {
      toast.error('Enter a valid amount.');
      return;
    }
    buyMutation.mutate(amountValue);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        render={<Button className="bg-green-600 text-white hover:bg-green-700">Buy</Button>}
      />
      <DialogContent>
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Buy this week&apos;s recommendations</DialogTitle>
            <DialogDescription>
              The amount is split equally across all of this week&apos;s BUY-rated stocks.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2 py-4">
            <Label htmlFor="amount">Amount (USD)</Label>
            <Input
              id="amount"
              type="number"
              min="0"
              step="0.01"
              placeholder="500"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              required
            />
          </div>
          <DialogFooter>
            <Button type="submit" disabled={buyMutation.isPending}>
              {buyMutation.isPending ? 'Placing order…' : 'Confirm Buy'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
