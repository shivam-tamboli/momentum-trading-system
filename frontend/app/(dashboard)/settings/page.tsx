'use client';

import { FormEvent, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Eye, EyeOff } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import type { ErrorResponse, MeResponse, SelectableIndex } from '@/lib/types';
import { SELECTABLE_INDEXES } from '@/lib/types';

const INDEX_LABELS: Record<SelectableIndex, string> = {
  'S&P 500': 'S&P 500',
  'S&P 400': 'S&P 400',
  'S&P 600': 'S&P 600',
  'NASDAQ 100': 'Nasdaq 100',
  FULL_MARKET: 'Full Market',
};

export default function SettingsPage() {
  const { hasAlpacaKey, selectedIndex, investmentAmount, refetch } = useUser();

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Settings</h1>
        <p className="text-sm text-muted-foreground">
          Manage your Alpaca connection, investment amount, and index selection.
        </p>
      </div>

      <AlpacaKeyCard hasAlpacaKey={hasAlpacaKey} refetch={refetch} />
      <InvestmentAmountCard investmentAmount={investmentAmount} refetch={refetch} />
      <IndexPickerCard
        selectedIndex={selectedIndex}
        investmentAmount={investmentAmount}
        refetch={refetch}
      />
    </div>
  );
}

function AlpacaKeyCard({
  hasAlpacaKey,
  refetch,
}: {
  hasAlpacaKey: boolean;
  refetch: () => Promise<void>;
}) {
  const [alpacaApiKey, setAlpacaApiKey] = useState('');
  const [alpacaApiSecret, setAlpacaApiSecret] = useState('');
  const [showSecret, setShowSecret] = useState(false);

  const mutation = useMutation({
    mutationFn: async () => {
      const { data } = await api.put<MeResponse>('/users/me/alpaca-key', {
        alpacaApiKey,
        alpacaApiSecret,
      });
      return data;
    },
    onSuccess: async () => {
      toast.success('Alpaca key saved.');
      setAlpacaApiKey('');
      setAlpacaApiSecret('');
      await refetch();
    },
    onError: (error) => {
      const message = isAxiosError<ErrorResponse>(error)
        ? error.response?.data?.error ?? 'Could not save your Alpaca key.'
        : 'Could not save your Alpaca key.';
      toast.error(message);
    },
  });

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    mutation.mutate();
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Alpaca Account</CardTitle>
        <CardDescription>
          {hasAlpacaKey
            ? 'A key is already connected. Enter new values below to replace it.'
            : 'Connect your Alpaca paper trading API key and secret.'}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="alpacaApiKey">Alpaca API Key</Label>
            <Input
              id="alpacaApiKey"
              type="text"
              autoComplete="off"
              required
              value={alpacaApiKey}
              onChange={(event) => setAlpacaApiKey(event.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="alpacaApiSecret">Alpaca API Secret</Label>
            <div className="relative">
              <Input
                id="alpacaApiSecret"
                type={showSecret ? 'text' : 'password'}
                autoComplete="off"
                required
                value={alpacaApiSecret}
                onChange={(event) => setAlpacaApiSecret(event.target.value)}
              />
              <button
                type="button"
                onClick={() => setShowSecret(!showSecret)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {showSecret ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </div>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : hasAlpacaKey ? 'Update Key' : 'Connect Account'}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function InvestmentAmountCard({
  investmentAmount,
  refetch,
}: {
  investmentAmount: number | null;
  refetch: () => Promise<void>;
}) {
  const [amount, setAmount] = useState(investmentAmount != null ? String(investmentAmount) : '');

  const mutation = useMutation({
    mutationFn: async (amountValue: number) => {
      const { data } = await api.put<MeResponse>('/users/me/investment-amount', {
        investmentAmount: amountValue,
      });
      return data;
    },
    onSuccess: async () => {
      toast.success('Investment amount saved.');
      await refetch();
    },
    onError: (error) => {
      const message = isAxiosError<ErrorResponse>(error)
        ? error.response?.data?.error ?? 'Could not save your investment amount.'
        : 'Could not save your investment amount.';
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
    mutation.mutate(amountValue);
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Investment Amount</CardTitle>
        <CardDescription>
          The system rebalances your account to this amount every trading day. This must be set
          before auto-trading or index switching can happen.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="flex items-end gap-3">
          <div className="flex-1 space-y-2">
            <Label htmlFor="investmentAmount">Amount (USD)</Label>
            <Input
              id="investmentAmount"
              type="number"
              min="0"
              step="0.01"
              placeholder="1000"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              required
            />
          </div>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : 'Save'}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function IndexPickerCard({
  selectedIndex,
  investmentAmount,
  refetch,
}: {
  selectedIndex: string | null;
  investmentAmount: number | null;
  refetch: () => Promise<void>;
}) {
  const mutation = useMutation({
    mutationFn: async (index: SelectableIndex) => {
      const { data } = await api.post<MeResponse>('/users/me/selected-index', {
        selectedIndex: index,
      });
      return data;
    },
    onSuccess: async () => {
      toast.success('Index updated. Rebalancing your holdings now.');
      await refetch();
    },
    onError: (error) => {
      const message = isAxiosError<ErrorResponse>(error)
        ? error.response?.data?.error ?? 'Could not switch index.'
        : 'Could not switch index.';
      toast.error(message);
    },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Index Selection</CardTitle>
        <CardDescription>
          Choose which index the daily engine tracks for you. Switching sells everything you
          currently hold, then buys that index&apos;s new top 5.
          {investmentAmount == null && (
            <span className="mt-1 block text-destructive">
              Set your investment amount above before switching index.
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="flex flex-wrap gap-2">
          {SELECTABLE_INDEXES.map((index) => {
            const isActive = selectedIndex === index;
            return (
              <Button
                key={index}
                type="button"
                variant={isActive ? 'default' : 'outline'}
                disabled={mutation.isPending || investmentAmount == null}
                className={cn(isActive && 'pointer-events-none')}
                onClick={() => mutation.mutate(index)}
              >
                {INDEX_LABELS[index]}
                {isActive && ' (current)'}
              </Button>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}
