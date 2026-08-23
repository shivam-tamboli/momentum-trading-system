'use client';

import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import { isAxiosError } from 'axios';
import { api } from '@/lib/api';
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

export default function RegisterPage() {
  const router = useRouter();
  const [alpacaApiKey, setAlpacaApiKey] = useState('');
  const [alpacaApiSecret, setAlpacaApiSecret] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);

    try {
      await api.post<MeResponse>('/users/register', {
        alpacaApiKey,
        alpacaApiSecret,
      });
      router.push('/dashboard');
      router.refresh();
    } catch (err) {
      const message = isAxiosError<ErrorResponse>(err)
        ? err.response?.data?.error ?? 'Registration failed.'
        : 'Registration failed.';
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
            account. These are encrypted before they&apos;re stored.
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
              <Input
                id="alpacaApiSecret"
                type="password"
                autoComplete="off"
                required
                value={alpacaApiSecret}
                onChange={(event) => setAlpacaApiSecret(event.target.value)}
              />
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
