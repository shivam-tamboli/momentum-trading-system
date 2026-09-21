'use client';

import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
import axios from 'axios';
import { api } from '@/lib/api';
import { createClient } from '@/lib/supabase/client';
import type { MeResponse } from '@/lib/types';

interface UserContextValue {
  userId: number | null;
  email: string | null;
  hasAlpacaKey: boolean;
  selectedIndex: string | null;
  investmentAmount: number | null;
  isLoading: boolean;
  // True only once every retry has been exhausted without a real answer either way (never
  // logged out, never confirmed logged in) — see resolveUser below. Distinct from userId===null,
  // which means the backend actually said "not logged in."
  connectionError: boolean;
  refetch: () => Promise<void>;
}

const UserContext = createContext<UserContextValue>({
  userId: null,
  email: null,
  hasAlpacaKey: false,
  selectedIndex: null,
  investmentAmount: null,
  isLoading: true,
  connectionError: false,
  refetch: async () => {},
});

// Render's free tier sleeps after ~15 minutes idle and takes anywhere from 56 to 100+ seconds to
// wake (see ConnectingIndicator / keep-alive.yml). A single failed /me call used to be treated as
// "not logged in" regardless of *why* it failed — meaning any real, already-logged-in user who
// opened the app after a period of inactivity had a good chance of getting silently bounced to
// /login by a cold backend, indistinguishable from an actual logout. These retries are sized to
// ride out a normal cold start before ever concluding anything about the user's auth state.
const MAX_ATTEMPTS = 5;
const RETRY_DELAY_MS = 4000;
const REQUEST_TIMEOUT_MS = 15000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function UserProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<number | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [hasAlpacaKey, setHasAlpacaKey] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState<string | null>(null);
  const [investmentAmount, setInvestmentAmount] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [connectionError, setConnectionError] = useState(false);

  const applyMeResponse = (data: MeResponse) => {
    setUserId(data.id);
    setEmail(data.email);
    setHasAlpacaKey(data.has_alpaca_key);
    setSelectedIndex(data.selected_index);
    setInvestmentAmount(data.investment_amount);
  };

  const clearUser = () => {
    setUserId(null);
    setEmail(null);
    setHasAlpacaKey(false);
    setSelectedIndex(null);
    setInvestmentAmount(null);
  };

  const resolveUser = useCallback(async () => {
    const supabase = createClient();
    const {
      data: { session },
    } = await supabase.auth.getSession();

    if (!session) {
      clearUser();
      setConnectionError(false);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setConnectionError(false);

    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        const { data } = await api.get<MeResponse>('/me', { timeout: REQUEST_TIMEOUT_MS });
        applyMeResponse(data);
        setIsLoading(false);
        return;
      } catch (error) {
        const status = axios.isAxiosError(error) ? error.response?.status : undefined;

        // The backend actually answered "this token is bad" — genuinely not logged in, and no
        // amount of retrying changes that verdict.
        if (status === 401 || status === 403) {
          clearUser();
          setIsLoading(false);
          return;
        }

        const isLastAttempt = attempt === MAX_ATTEMPTS;
        if (isLastAttempt) {
          // Every attempt failed without ever getting a real answer either way — don't guess
          // "logged out" here. Surface it plainly and let the user retry once the server (or
          // their network) is actually back, rather than silently signing out a real session.
          setConnectionError(true);
          setIsLoading(false);
          return;
        }

        await sleep(RETRY_DELAY_MS);
      }
    }
  }, []);

  useEffect(() => {
    resolveUser();

    const supabase = createClient();
    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange(() => {
      resolveUser();
    });

    return () => subscription.unsubscribe();
  }, [resolveUser]);

  return (
    <UserContext.Provider
      value={{
        userId,
        email,
        hasAlpacaKey,
        selectedIndex,
        investmentAmount,
        isLoading,
        connectionError,
        refetch: resolveUser,
      }}
    >
      {children}
    </UserContext.Provider>
  );
}

export function useUser() {
  return useContext(UserContext);
}
