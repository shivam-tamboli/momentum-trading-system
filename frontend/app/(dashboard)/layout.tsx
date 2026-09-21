'use client';

import { ReactNode, useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { Menu } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetTrigger, SheetTitle } from '@/components/ui/sheet';
import { AppSidebarContent } from '@/components/AppSidebar';
import { ConnectingIndicator } from '@/components/ConnectingIndicator';
import { useUser } from '@/lib/user-context';

export default function DashboardLayout({ children }: { children: ReactNode }) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const { userId, hasAlpacaKey, isLoading, connectionError, refetch } = useUser();

  // Onboarding only redirects from the dashboard's landing page — it must not block
  // Recommendations, Settings, etc., since users without a key can still browse those
  // (they just can't trade). Only /dashboard itself sends a keyless user to onboarding.
  const needsOnboarding = !hasAlpacaKey && pathname === '/dashboard';

  useEffect(() => {
    if (isLoading || connectionError) {
      // connectionError means /me never got a real answer either way after retrying through a
      // normal cold-start window — userId is null here too, but that's not the same thing as the
      // backend actually saying "you're not logged in." Redirecting to /login on a connection
      // failure would sign out a real session just because the server was still waking up.
      return;
    }
    if (userId === null) {
      router.replace('/login');
    } else if (needsOnboarding) {
      router.replace('/register');
    }
  }, [isLoading, userId, needsOnboarding, router, connectionError]);

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <ConnectingIndicator />
      </div>
    );
  }

  if (connectionError) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-3 bg-background px-4 text-center">
        <p className="text-sm text-muted-foreground">
          Couldn&apos;t reach the server. It may still be waking up — this can take up to a minute.
        </p>
        <Button variant="outline" onClick={() => refetch()}>
          Try again
        </Button>
      </div>
    );
  }

  if (userId === null || needsOnboarding) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <p className="text-sm text-muted-foreground">Loading account…</p>
      </div>
    );
  }

  return (
    <div className="flex h-screen overflow-hidden bg-background">
      <aside className="hidden w-64 shrink-0 border-r border-border md:block">
        <AppSidebarContent />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <header className="flex items-center gap-3 border-b border-border px-4 py-3 md:hidden">
          <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
            <SheetTrigger
              render={
                <Button variant="outline" size="icon">
                  <Menu className="h-4 w-4" />
                </Button>
              }
            />
            <SheetContent side="left" className="w-64 p-0">
              <SheetTitle className="sr-only">Navigation</SheetTitle>
              <AppSidebarContent />
            </SheetContent>
          </Sheet>
          <div className="flex items-center gap-2">
            <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary font-mono text-xs font-bold text-primary-foreground">
              M
            </div>
            <span className="text-sm font-semibold">Momentum</span>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-4 md:p-8">{children}</main>
      </div>
    </div>
  );
}
