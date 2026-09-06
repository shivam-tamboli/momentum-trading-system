'use client';

import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
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
  refetch: () => Promise<void>;
}

const UserContext = createContext<UserContextValue>({
  userId: null,
  email: null,
  hasAlpacaKey: false,
  selectedIndex: null,
  investmentAmount: null,
  isLoading: true,
  refetch: async () => {},
});

export function UserProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<number | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [hasAlpacaKey, setHasAlpacaKey] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState<string | null>(null);
  const [investmentAmount, setInvestmentAmount] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(true);

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
      setIsLoading(false);
      return;
    }

    try {
      const { data } = await api.get<MeResponse>('/me');
      applyMeResponse(data);
    } catch {
      clearUser();
    } finally {
      setIsLoading(false);
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
      value={{ userId, email, hasAlpacaKey, selectedIndex, investmentAmount, isLoading, refetch: resolveUser }}
    >
      {children}
    </UserContext.Provider>
  );
}

export function useUser() {
  return useContext(UserContext);
}
