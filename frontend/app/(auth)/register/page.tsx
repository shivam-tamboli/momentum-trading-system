'use client';

import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import { isAxiosError } from 'axios';
import { Eye, EyeOff } from 'lucide-react';
import { api } from '@/lib/api';
import { useUser } from '@/lib/user-context';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import type { ErrorResponse, MeResponse } from '@/lib/types';

// This is the one-time onboarding screen: shown when a logged-in user has no Alpaca key saved
// yet. Saves the key once via PUT /users/me/alpaca-key — the system uses it for every future
// trade after that, the user is never asked again (Settings is where they'd update it later).
export default function RegisterPage() {
  const router = useRouter();
  const { refetch } = useUser();
  const [alpacaApiKey, setAlpacaApiKey] = useState('');
  const [alpacaApiSecret, setAlpacaApiSecret] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);

    try {
      await api.put<MeResponse>('/users/me/alpaca-key', {
        alpacaApiKey,
        alpacaApiSecret,
      });
      await refetch();
      router.push('/dashboard');
      router.refresh();
    } catch (err) {
      const message = isAxiosError<ErrorResponse>(err)
        ? err.response?.data?.error ?? 'Could not save your Alpaca key.'
        : 'Could not save your Alpaca key.';
      setError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Connect your Alpaca account</CardTitle>
          <CardDescription>
            Enter your Alpaca paper trading API key and secret to finish setting up your
            account. You&apos;ll only need to do this once — the system uses it automatically
            for every future trade.
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
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="off"
                  required
                  value={alpacaApiSecret}
                  onChange={(event) => setAlpacaApiSecret(event.target.value)}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}
            <Button type="submit" className="w-full" disabled={isSubmitting}>
              {isSubmitting ? 'Connecting…' : 'Connect Account'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
