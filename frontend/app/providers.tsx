'use client';

import { ReactNode, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from 'next-themes';
import { Toaster } from '@/components/ui/sonner';
import { UserProvider } from '@/lib/user-context';

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem={false} forcedTheme="dark">
      <QueryClientProvider client={queryClient}>
        <UserProvider>
          {children}
          <Toaster />
        </UserProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
