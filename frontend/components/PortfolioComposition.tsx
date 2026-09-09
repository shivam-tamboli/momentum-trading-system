import { PieChart } from 'lucide-react';
import { EmptyState } from '@/components/EmptyState';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { PositionItem } from '@/lib/types';

interface PortfolioCompositionProps {
  positions: PositionItem[] | undefined;
  isLoading: boolean;
}

interface Slice {
  position: PositionItem;
  share: number;
  dash: number;
  offset: number;
  colorIndex: number;
}

// The five --chart-N tokens in globals.css exist exactly for this — distinct, theme-aware hues
// already wired through Tailwind as bg-chart-N/stroke-chart-N. Unlike the lightweight-charts
// components elsewhere in this app, this is plain SVG rendered by the browser's own CSS engine,
// not a canvas 2D context, so oklch() here works natively — no hardcoded hex needed.
const SLICE_STROKE = ['stroke-chart-1', 'stroke-chart-2', 'stroke-chart-3', 'stroke-chart-4', 'stroke-chart-5'] as const;
const SLICE_DOT = ['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4', 'bg-chart-5'] as const;

const currency = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const percent = new Intl.NumberFormat('en-US', {
  style: 'percent',
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

const SIZE = 160;
const STROKE = 28;
const RADIUS = (SIZE - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

export function PortfolioComposition({ positions, isLoading }: PortfolioCompositionProps) {
  if (isLoading) {
    return <Skeleton className="h-40 w-full" />;
  }

  if (!positions || positions.length === 0) {
    return (
      <div className="flex h-40 items-center justify-center">
        <EmptyState icon={PieChart} message="No positions to break down yet." />
      </div>
    );
  }

  const total = positions.reduce((sum, p) => sum + p.market_value, 0);
  const sorted = [...positions].sort((a, b) => b.market_value - a.market_value);

  // Each slice's starting offset is the sum of every prior slice's arc length — the standard
  // stroke-dasharray/-dashoffset technique for laying consecutive donut segments with no gaps.
  // Built via reduce (not a mutated loop variable) so this stays a pure render.
  const { slices } = sorted.reduce<{ slices: Slice[]; cumulative: number }>(
    (acc, position, i) => {
      const share = total > 0 ? position.market_value / total : 0;
      const dash = share * CIRCUMFERENCE;
      return {
        slices: [
          ...acc.slices,
          { position, share, dash, offset: acc.cumulative, colorIndex: i % SLICE_STROKE.length },
        ],
        cumulative: acc.cumulative + dash,
      };
    },
    { slices: [], cumulative: 0 }
  );

  return (
    <div className="flex flex-col items-center gap-6 sm:flex-row">
      <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} className="shrink-0 -rotate-90">
        <circle
          cx={SIZE / 2}
          cy={SIZE / 2}
          r={RADIUS}
          fill="none"
          strokeWidth={STROKE}
          className="stroke-muted"
        />
        {slices.map(({ position, dash, offset, colorIndex }) => (
          <circle
            key={position.symbol}
            cx={SIZE / 2}
            cy={SIZE / 2}
            r={RADIUS}
            fill="none"
            strokeWidth={STROKE}
            strokeDasharray={`${dash} ${CIRCUMFERENCE - dash}`}
            strokeDashoffset={-offset}
            className={SLICE_STROKE[colorIndex]}
          />
        ))}
      </svg>
      <div className="w-full min-w-0 flex-1 space-y-2">
        {slices.map(({ position, share, colorIndex }) => (
          <div key={position.symbol} className="flex items-center justify-between gap-3 text-sm">
            <div className="flex min-w-0 items-center gap-2">
              <span className={cn('h-2.5 w-2.5 shrink-0 rounded-full', SLICE_DOT[colorIndex])} />
              <span className="font-medium">{position.symbol}</span>
              <span className="truncate text-muted-foreground">{position.name}</span>
            </div>
            <div className="flex shrink-0 items-center gap-3 font-mono tabular-nums">
              <span>{currency.format(position.market_value)}</span>
              <span className="w-12 text-right text-muted-foreground">{percent.format(share)}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
