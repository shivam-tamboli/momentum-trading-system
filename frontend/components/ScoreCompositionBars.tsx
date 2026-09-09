import { cn } from '@/lib/utils';

interface ScoreCompositionBarsProps {
  ret6m: number;
  ret3m: number;
  ret1m: number;
  vol3m: number;
}

const ROWS = [
  { key: 'ret6m', label: '6m return', weight: '50%', isPenalty: false },
  { key: 'ret3m', label: '3m return', weight: '30%', isPenalty: false },
  { key: 'ret1m', label: '1m return', weight: '20%', isPenalty: false },
  { key: 'vol3m', label: '3m volatility', weight: '−10%', isPenalty: true },
] as const;

// Real breakdown of the 4 inputs that produced the momentum score — score = 0.5*ret6m +
// 0.3*ret3m + 0.2*ret1m − 0.1*vol3m. Bars are scaled relative to this stock's own largest
// component, not a fixed axis, so each row shows real relative contribution regardless of how
// large or small the stock's absolute numbers are.
export function ScoreCompositionBars({ ret6m, ret3m, ret1m, vol3m }: ScoreCompositionBarsProps) {
  const values = { ret6m, ret3m, ret1m, vol3m };
  const maxAbs = Math.max(Math.abs(ret6m), Math.abs(ret3m), Math.abs(ret1m), Math.abs(vol3m), 0.0001);

  return (
    <div className="space-y-1.5 py-2">
      {ROWS.map((row) => {
        const value = values[row.key];
        // Volatility is always subtracted — a cost, never a gain — regardless of its own sign.
        const isPositive = row.isPenalty ? false : value >= 0;
        const widthPercent = (Math.abs(value) / maxAbs) * 100;

        return (
          <div key={row.key} className="flex items-center gap-2 text-xs">
            <span className="w-28 shrink-0 text-muted-foreground">
              {row.label} <span className="text-muted-foreground/60">({row.weight})</span>
            </span>
            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
              <div
                className={cn('h-full rounded-full', isPositive ? 'bg-gain' : 'bg-loss')}
                style={{ width: `${widthPercent}%` }}
              />
            </div>
            <span
              className={cn(
                'w-16 shrink-0 text-right font-mono tabular-nums',
                isPositive ? 'text-gain' : 'text-loss'
              )}
            >
              {(value * 100).toFixed(1)}%
            </span>
          </div>
        );
      })}
    </div>
  );
}
