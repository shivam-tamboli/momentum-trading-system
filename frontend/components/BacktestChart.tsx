'use client';

import { useEffect, useRef, useState } from 'react';
import { createChart, LineSeries, ColorType, type IChartApi, type Time } from 'lightweight-charts';
import { useTheme } from 'next-themes';
import { TrendingUp } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import { ErrorState } from '@/components/ErrorState';
import type { BacktestPoint } from '@/lib/types';

interface BacktestChartProps {
  points: BacktestPoint[] | undefined;
  etfSymbol: string | undefined;
  isLoading: boolean;
  isError?: boolean;
}

// Same real compiled hex values IndexPriceChart already uses for mutedForeground/gridLine — the
// benchmark line reuses that exact neutral gray rather than gain/loss green or red, since it isn't
// itself a gain or a loss, just the other series being compared against. The portfolio line uses
// this app's one deliberate accent color instead — real hex, computed once from the same oklch
// values --primary/--chart-1 resolve to, not a fresh color invented for this chart.
const PALETTES = {
  light: {
    mutedForeground: '#737373',
    gridLine: 'rgba(0, 0, 0, 0.06)',
    portfolio: '#007f20',
    benchmark: '#737373',
  },
  dark: {
    mutedForeground: '#a1a1a1',
    gridLine: 'rgba(255, 255, 255, 0.06)',
    portfolio: '#39c34b',
    benchmark: '#a1a1a1',
  },
} as const;

export function BacktestChart({ points, etfSymbol, isLoading, isError }: BacktestChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);
  const { resolvedTheme } = useTheme();
  const colors = resolvedTheme === 'light' ? PALETTES.light : PALETTES.dark;

  useEffect(() => {
    if (!containerRef.current || isLoading || !points || points.length === 0) return;

    // Contained locally, same reasoning as the other charts on this dashboard: a third-party
    // canvas library failing to render should never take down the rest of the page.
    try {
      const sorted = [...points].sort((a, b) => (a.date < b.date ? -1 : 1));

      const chart = createChart(containerRef.current, {
        layout: {
          background: { type: ColorType.Solid, color: 'transparent' },
          textColor: colors.mutedForeground,
          fontFamily: 'var(--font-geist-mono)',
          fontSize: 11,
        },
        grid: {
          vertLines: { visible: false },
          horzLines: { color: colors.gridLine },
        },
        rightPriceScale: { borderVisible: false },
        timeScale: { borderVisible: false },
        height: 280,
        autoSize: true,
      });

      const portfolioSeries = chart.addSeries(LineSeries, {
        color: colors.portfolio,
        lineWidth: 2,
        title: 'Momentum strategy',
        priceFormat: { type: 'custom', formatter: (v: number) => `$${v.toFixed(2)}` },
      });
      portfolioSeries.setData(sorted.map((p) => ({ time: p.date as Time, value: p.portfolio_value })));

      const benchmarkSeries = chart.addSeries(LineSeries, {
        color: colors.benchmark,
        lineWidth: 2,
        lineStyle: 2, // dashed — visually distinct from the portfolio line at a glance
        title: etfSymbol ?? 'Benchmark',
        priceFormat: { type: 'custom', formatter: (v: number) => `$${v.toFixed(2)}` },
      });
      benchmarkSeries.setData(sorted.map((p) => ({ time: p.date as Time, value: p.benchmark_value })));

      chart.timeScale().fitContent();
      chartRef.current = chart;
    } catch (error) {
      console.error('BacktestChart failed to render:', error);
      const message = error instanceof Error ? error.message : 'Unknown chart error';
      queueMicrotask(() => setRenderError(message));
    }

    return () => {
      chartRef.current?.remove();
      chartRef.current = null;
    };
  }, [points, etfSymbol, isLoading, colors]);

  if (isLoading) {
    return <Skeleton className="h-[280px] w-full" />;
  }

  if (isError) {
    return (
      <div className="flex h-[280px] items-center justify-center">
        <ErrorState message="Couldn't load backtest results." />
      </div>
    );
  }

  if (renderError) {
    return (
      <div className="flex h-[280px] flex-col items-center justify-center gap-1 text-sm text-muted-foreground">
        <p>Couldn&apos;t render the backtest chart.</p>
        <p className="font-mono text-xs">{renderError}</p>
      </div>
    );
  }

  if (!points || points.length === 0) {
    return (
      <div className="flex h-[280px] items-center justify-center">
        <EmptyState icon={TrendingUp} message="No backtest data yet — check back after the next daily run." />
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div ref={containerRef} className="h-[280px] w-full" />
      <div className="flex items-center gap-4 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span className="h-0.5 w-3 rounded-full" style={{ backgroundColor: colors.portfolio }} />
          Momentum strategy
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-0.5 w-3 rounded-full border-t border-dashed" style={{ borderColor: colors.benchmark }} />
          {etfSymbol ?? 'Benchmark'}
        </span>
      </div>
    </div>
  );
}
