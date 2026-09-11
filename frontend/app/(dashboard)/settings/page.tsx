'use client';

import { FormEvent, ReactNode, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Eye, EyeOff, History } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { formatFullDateTime } from '@/lib/freshness';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogFooter,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogClose,
} from '@/components/ui/alert-dialog';
import type { ErrorResponse, IndexSwitchHistoryItem, MeResponse, SelectableIndex } from '@/lib/types';
import { SELECTABLE_INDEXES } from '@/lib/types';

const INDEX_LABELS: Record<SelectableIndex, string> = {
  'S&P 500': 'S&P 500',
  'S&P 400': 'S&P 400',
  'S&P 600': 'S&P 600',
  'NASDAQ 100': 'Nasdaq 100',
  FULL_MARKET: 'Full Market',
};

// The index picker only ever offers these 4 — Full Market is a valid selectedIndex value
// elsewhere in the app (recommendations, dashboard), but isn't one of the choices presented here.
const PICKER_INDEXES = SELECTABLE_INDEXES.filter(
  (index): index is Exclude<SelectableIndex, 'FULL_MARKET'> => index !== 'FULL_MARKET'
);

const currency = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
});

export default function SettingsPage() {
  const { hasAlpacaKey, selectedIndex, investmentAmount, refetch } = useUser();

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Strategy</h1>
        <p className="text-sm text-muted-foreground">
          Connect an account, set an amount, and pick an index — the daily engine handles the
          rest.
        </p>
      </div>

      <div className="grid grid-cols-3 divide-x divide-border overflow-hidden rounded-lg border border-border">
        <StrategyStat
          label="Account"
          value={hasAlpacaKey ? 'Connected' : 'Not connected'}
          active={hasAlpacaKey}
        />
        <StrategyStat
          label="Investment"
          value={investmentAmount != null ? currency.format(investmentAmount) : 'Not set'}
          active={investmentAmount != null}
        />
        <StrategyStat
          label="Index"
          value={selectedIndex ?? 'Not selected'}
          active={selectedIndex != null}
        />
      </div>

      <StrategyStep number={1} label="Connect account">
        <AlpacaKeyCard hasAlpacaKey={hasAlpacaKey} refetch={refetch} />
      </StrategyStep>
      <StrategyStep number={2} label="Set investment amount">
        <InvestmentAmountCard investmentAmount={investmentAmount} refetch={refetch} />
      </StrategyStep>
      <StrategyStep number={3} label="Choose index">
        <IndexPickerCard
          selectedIndex={selectedIndex}
          investmentAmount={investmentAmount}
          refetch={refetch}
        />
      </StrategyStep>

      <SwitchHistoryCard />
    </div>
  );
}

function StrategyStat({ label, value, active }: { label: string; value: string; active: boolean }) {
  return (
    <div className="bg-card px-4 py-3">
      <p className="text-xs text-muted-foreground uppercase tracking-wide">{label}</p>
      <p className={cn('mt-1 truncate text-sm font-semibold', active ? 'text-gain' : 'text-muted-foreground')}>
        {value}
      </p>
    </div>
  );
}

function StrategyStep({
  number,
  label,
  children,
}: {
  number: number;
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="flex gap-3">
      <div className="flex flex-col items-center pt-1">
        <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-secondary font-mono text-xs font-semibold text-secondary-foreground">
          {number}
        </div>
        <div className="mt-1 w-px flex-1 bg-border" />
      </div>
      <div className="min-w-0 flex-1 pb-2">
        <p className="mb-2 text-xs font-medium text-muted-foreground uppercase tracking-wide">
          {label}
        </p>
        {children}
      </div>
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
  const [pendingIndex, setPendingIndex] = useState<SelectableIndex | null>(null);

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
    onSettled: () => setPendingIndex(null),
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
              Please set your investment amount first
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="flex flex-wrap gap-2">
          {PICKER_INDEXES.map((index) => {
            const isActive = selectedIndex === index;
            return (
              <Button
                key={index}
                type="button"
                variant={isActive ? 'default' : 'outline'}
                disabled={mutation.isPending || investmentAmount == null}
                className={cn(isActive && 'pointer-events-none')}
                onClick={() => setPendingIndex(index)}
              >
                {INDEX_LABELS[index]}
                {isActive && ' (current)'}
              </Button>
            );
          })}
        </div>
      </CardContent>

      <AlertDialog open={pendingIndex !== null} onOpenChange={(open) => !open && setPendingIndex(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Switch to {pendingIndex ? INDEX_LABELS[pendingIndex] : ''}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              {selectedIndex ? (
                <>
                  Switching to {pendingIndex ? INDEX_LABELS[pendingIndex] : ''} will sell all
                  your current {INDEX_LABELS[selectedIndex as SelectableIndex] ?? selectedIndex}{' '}
                  holdings and buy the top 5 {pendingIndex ? INDEX_LABELS[pendingIndex] : ''}{' '}
                  stocks —
                </>
              ) : (
                <>
                  This will buy the top 5 {pendingIndex ? INDEX_LABELS[pendingIndex] : ''}{' '}
                  stocks —
                </>
              )}{' '}
              immediately if the market is open right now, or at the next market open otherwise.
              Are you sure?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogClose
              render={
                <Button type="button" variant="outline">
                  Cancel
                </Button>
              }
            />
            <Button
              type="button"
              disabled={mutation.isPending}
              onClick={() => pendingIndex && mutation.mutate(pendingIndex)}
            >
              {mutation.isPending ? 'Switching…' : 'Confirm'}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

const COLUMN_COUNT = 3;

function SwitchHistoryCard() {
  const query = useQuery({
    queryKey: ['index-switch-history'],
    queryFn: async () => {
      const { data } = await api.get<IndexSwitchHistoryItem[]>('/users/me/index-switch-history');
      return data;
    },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Switch History</CardTitle>
        <CardDescription>Every time you&apos;ve changed your tracked index, most recent first.</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Switched</TableHead>
              <TableHead>Change</TableHead>
              <TableHead className="text-right">Investment Amount</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {query.isLoading &&
              Array.from({ length: 3 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: COLUMN_COUNT }).map((__, j) => (
                    <TableCell key={j}>
                      <Skeleton className="h-4 w-full" />
                    </TableCell>
                  ))}
                </TableRow>
              ))}

            {!query.isLoading && (!query.data || query.data.length === 0) && (
              <TableRow>
                <TableCell colSpan={COLUMN_COUNT}>
                  <EmptyState icon={History} message="No index switches yet." />
                </TableCell>
              </TableRow>
            )}

            {!query.isLoading &&
              query.data?.map((entry, i) => (
                <TableRow key={i}>
                  <TableCell className="text-muted-foreground">
                    {formatFullDateTime(entry.switched_at)}
                  </TableCell>
                  <TableCell className="font-medium">
                    {entry.previous_index ? (
                      <>
                        {entry.previous_index} <span className="text-muted-foreground">→</span>{' '}
                        {entry.new_index}
                      </>
                    ) : (
                      <>
                        {entry.new_index} <span className="text-muted-foreground">(first pick)</span>
                      </>
                    )}
                  </TableCell>
                  <TableCell className="text-right font-mono tabular-nums">
                    {entry.investment_amount != null ? currency.format(entry.investment_amount) : '—'}
                  </TableCell>
                </TableRow>
              ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
