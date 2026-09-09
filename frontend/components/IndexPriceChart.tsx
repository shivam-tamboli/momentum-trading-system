'use client';

import { useEffect, useRef, useState } from 'react';
import { createChart, LineSeries, ColorType, type IChartApi, type Time } from 'lightweight-charts';
import { useTheme } from 'next-themes';
import { TrendingUp } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import type { IndexPricePoint } from '@/lib/types';

interface IndexPriceChartProps {
  points: IndexPricePoint[] | undefined;
  isLoading: boolean;
}

// Same real compiled hex values used for the rest of the theme-aware chart work — pulled from
// the live CSS bundle, not computed by hand.
const PALETTES = {
  light: {
    mutedForeground: '#737373',
    gridLine: 'rgba(0, 0, 0, 0.06)',
    gain: '#00a544',
    loss: '#e40014',
  },
  dark: {
    mutedForeground: '#a1a1a1',
    gridLine: 'rgba(255, 255, 255, 0.06)',
    gain: '#00c565',
    loss: '#ff6568',
  },
} as const;

export function IndexPriceChart({ points, isLoading }: IndexPriceChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);
  const { resolvedTheme } = useTheme();
  const colors = resolvedTheme === 'light' ? PALETTES.light : PALETTES.dark;

  useEffect(() => {
    if (!containerRef.current || isLoading || !points || points.length === 0) return;

    // Contained locally, same reasoning as the other chart on this dashboard: a third-party
    // canvas library failing to render should never take down the rest of the page.
    try {
      const sorted = [...points].sort((a, b) => (a.date < b.date ? -1 : 1));
      const isUp = sorted[sorted.length - 1].close >= sorted[0].close;
      const lineColor = isUp ? colors.gain : colors.loss;

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
        crosshair: { vertLine: { labelBackgroundColor: lineColor } },
        height: 220,
        autoSize: true,
      });

      const series = chart.addSeries(LineSeries, {
        color: lineColor,
        lineWidth: 2,
        priceFormat: { type: 'custom', formatter: (v: number) => `$${v.toFixed(2)}` },
      });

      series.setData(sorted.map((p) => ({ time: p.date as Time, value: p.close })));
      chart.timeScale().fitContent();
      chartRef.current = chart;
    } catch (error) {
      console.error('IndexPriceChart failed to render:', error);
      const message = error instanceof Error ? error.message : 'Unknown chart error';
      queueMicrotask(() => setRenderError(message));
    }

    return () => {
      chartRef.current?.remove();
      chartRef.current = null;
    };
  }, [points, isLoading, colors]);

  if (isLoading) {
    return <Skeleton className="h-[220px] w-full" />;
  }

  if (renderError) {
    return (
      <div className="flex h-[220px] flex-col items-center justify-center gap-1 text-sm text-muted-foreground">
        <p>Couldn&apos;t render the price chart.</p>
        <p className="font-mono text-xs">{renderError}</p>
      </div>
    );
  }

  if (!points || points.length === 0) {
    return (
      <div className="flex h-[220px] items-center justify-center">
        <EmptyState icon={TrendingUp} message="No price history available yet." />
      </div>
    );
  }

  return <div ref={containerRef} className="h-[220px] w-full" />;
}
