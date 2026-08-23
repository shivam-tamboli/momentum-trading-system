'use client';

import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api } from '@/lib/api';
import { createClient } from '@/lib/supabase/client';
import type { MeResponse } from '@/lib/types';

interface UserContextValue {
  userId: number | null;
  email: string | null;
  isLoading: boolean;
}

const UserContext = createContext<UserContextValue>({
  userId: null,
  email: null,
  isLoading: true,
});

export function UserProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<number | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const supabase = createClient();

    const resolveUser = async () => {
      const {
        data: { session },
      } = await supabase.auth.getSession();

      if (!session) {
        setUserId(null);
        setEmail(null);
        setIsLoading(false);
        return;
      }

      try {
        const { data } = await api.get<MeResponse>('/me');
        setUserId(data.id);
        setEmail(data.email);
      } catch {
        setUserId(null);
        setEmail(null);
      } finally {
        setIsLoading(false);
      }
    };

    resolveUser();

    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange(() => {
      resolveUser();
    });

    return () => subscription.unsubscribe();
  }, []);

  return (
    <UserContext.Provider value={{ userId, email, isLoading }}>{children}</UserContext.Provider>
  );
}

export function useUser() {
  return useContext(UserContext);
}
