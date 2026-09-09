import type { ReactNode } from 'react';

interface AuthLayoutProps {
  eyebrow: string;
  title: string;
  children: ReactNode;
}

// Shared chrome for login/register — previously each page was just a bare card on a flat
// background, the biggest visual gap in the app relative to the redesigned dashboard. The right
// panel is pure CSS (a grid pattern + gradient over existing tokens) — no image assets, so it
// costs nothing and can't go stale.
export function AuthLayout({ eyebrow, title, children }: AuthLayoutProps) {
  return (
    <div className="flex min-h-screen bg-background">
      <div className="flex flex-1 items-center justify-center px-4 py-12">
        <div className="w-full max-w-sm space-y-6">
          <div className="flex flex-col items-center gap-3 text-center">
            <div className="flex h-11 w-11 items-center justify-center rounded-md bg-primary font-mono text-lg font-bold text-primary-foreground">
              M
            </div>
            <div>
              <p className="font-mono text-[11px] tracking-widest text-muted-foreground uppercase">
                {eyebrow}
              </p>
              <h1 className="mt-1 text-xl font-semibold">{title}</h1>
            </div>
          </div>
          {children}
        </div>
      </div>

      <div className="relative hidden flex-1 items-center justify-center overflow-hidden border-l border-border bg-card lg:flex">
        <div
          className="absolute inset-0 opacity-[0.12]"
          style={{
            backgroundImage:
              'linear-gradient(var(--border) 1px, transparent 1px), linear-gradient(90deg, var(--border) 1px, transparent 1px)',
            backgroundSize: '32px 32px',
          }}
        />
        <div className="absolute inset-0 bg-gradient-to-t from-background via-transparent to-transparent" />
        <div className="relative max-w-sm space-y-4 px-8 text-center">
          <p className="font-mono text-xs tracking-widest text-primary uppercase">
            Momentum Trading System
          </p>
          <p className="text-2xl leading-snug font-semibold text-foreground">
            Momentum-ranked. Rebalanced daily. Zero manual clicks.
          </p>
          <p className="text-sm text-muted-foreground">
            Scores roughly 1,500 stocks every morning, picks the top 5 for your index, and trades
            your paper account automatically at market open.
          </p>
        </div>
      </div>
    </div>
  );
}
