'use client';

import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';

// Render's free tier can take 50-100+ seconds to wake from a cold start. A static "Loading…"
// gives no signal that anything unusual is happening, so this upgrades the message after a
// threshold long enough that a normal warm request would never hit it.
const SLOW_CONNECTION_TIMEOUT_MS = 10000;

export function ConnectingIndicator() {
  const [isSlow, setIsSlow] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setIsSlow(true), SLOW_CONNECTION_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, []);

  return (
    <div className="flex flex-col items-center gap-3">
      <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      <p className="text-sm text-muted-foreground">
        {isSlow ? 'Server is starting up, please wait a moment…' : 'Connecting to server…'}
      </p>
    </div>
  );
}
