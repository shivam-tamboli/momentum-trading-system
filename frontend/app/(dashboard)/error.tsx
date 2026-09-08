'use client';

import { useEffect } from 'react';
import { Button } from '@/components/ui/button';

// Next.js route-level error boundary — the last-resort net for anything on this route group that
// throws during render and isn't already contained locally (see AccountActivityChart for an
// example of local containment). Without this, an uncaught error here falls through to Next's
// generic default crash UI instead of something a user can actually recover from.
export default function DashboardError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('Dashboard route error:', error);
  }, [error]);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-background px-4 text-center">
      <div>
        <p className="text-lg font-semibold">Something went wrong loading this page.</p>
        <p className="mt-1 text-sm text-muted-foreground">
          The rest of your account data is unaffected — this was a display error.
        </p>
        {error.message && (
          <p className="mt-3 font-mono text-xs text-muted-foreground">{error.message}</p>
        )}
      </div>
      <Button onClick={reset}>Try again</Button>
    </div>
  );
}
