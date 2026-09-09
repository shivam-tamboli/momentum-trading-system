'use client';

import { useEffect, useRef, useState } from 'react';
import { AreaSeries, ColorType, createChart, type IChartApi, type Time } from 'lightweight-charts';
import { Activity } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/EmptyState';
import type { DailyTradeItem } from '@/lib/types';

interface AccountActivityChartProps {
  trades: DailyTradeItem[] | undefined;
  isLoading: boolean;
}

// lightweight-charts renders to a plain <canvas> 2D context, which does not accept oklch() color
// strings — unlike Tailwind classes, which get compiled down to hex/lab fallbacks at build time,
// these are passed straight to the charting library's JS API and never go through that
// compilation. Hex values below are the exact ones already live in the deployed CSS for
// --muted-foreground and --primary in dark mode (verified against the real compiled bundle, not
// just computed from scratch), so the chart matches the rest of the app's palette exactly.
const CHART_COLORS = {
  mutedForeground: '#a1a1a1',
  gridLine: 'rgba(255, 255, 255, 0.06)',
  accent: '#3080ff',
  accentFillTop: 'rgba(48, 128, 255, 0.35)',
  accentFillBottom: 'rgba(48, 128, 255, 0)',
} as const;

// There's no backend endpoint that snapshots portfolio value over time, and a buy/sell doesn't
// change total account value at the moment it fills (cash converts to an equal-value position,
// or back) — so trade records can't be used to reconstruct a historical portfolio-value line
// without fabricating the part that actually moves it: market price change between trades. What
// they DO give us honestly is real daily trading volume, so that's what this charts.
function buildDailyVolumeSeries(trades: DailyTradeItem[]) {
  const byDay = new Map<string, number>();

  for (const trade of trades) {
    if (trade.amount === null) continue;
    const day = trade.traded_at.slice(0, 10);
    byDay.set(day, (byDay.get(day) ?? 0) + trade.amount);
  }

  return Array.from(byDay.entries())
    .sort(([a], [b]) => (a < b ? -1 : 1))
    .map(([day, value]) => ({ time: day as Time, value: Math.round(value * 100) / 100 }));
}

export function AccountActivityChart({ trades, isLoading }: AccountActivityChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current || isLoading) return;

    // This is the newest, least-exercised piece of the dashboard — it renders a third-party
    // canvas library against real trade data for the first time only once real trades exist, and
    // that code path was never actually run in production before today. A failure here should
    // never take down the rest of the dashboard, so it's contained locally rather than left to
    // propagate into an uncaught render error.
    try {
      const chart = createChart(containerRef.current, {
        layout: {
          background: { type: ColorType.Solid, color: 'transparent' },
          textColor: CHART_COLORS.mutedForeground,
          fontFamily: 'var(--font-geist-mono)',
          fontSize: 11,
        },
        grid: {
          vertLines: { visible: false },
          horzLines: { color: CHART_COLORS.gridLine },
        },
        rightPriceScale: { borderVisible: false },
        timeScale: { borderVisible: false },
        crosshair: { vertLine: { labelBackgroundColor: CHART_COLORS.accent } },
        height: 220,
        autoSize: true,
      });

      const series = chart.addSeries(AreaSeries, {
        lineColor: CHART_COLORS.accent,
        topColor: CHART_COLORS.accentFillTop,
        bottomColor: CHART_COLORS.accentFillBottom,
        lineWidth: 2,
        priceFormat: { type: 'custom', formatter: (v: number) => `$${v.toLocaleString()}` },
      });

      series.setData(buildDailyVolumeSeries(trades ?? []));
      chart.timeScale().fitContent();
      chartRef.current = chart;
    } catch (error) {
      console.error('AccountActivityChart failed to render:', error);
      const message = error instanceof Error ? error.message : 'Unknown chart error';
      // Deferred rather than called synchronously in the effect body — this only runs on the
      // rare failure path, but setState directly in an effect can trigger cascading renders.
      queueMicrotask(() => setRenderError(message));
    }

    return () => {
      chartRef.current?.remove();
      chartRef.current = null;
    };
  }, [trades, isLoading]);

  if (isLoading) {
    return <Skeleton className="h-[220px] w-full" />;
  }

  if (renderError) {
    return (
      <div className="flex h-[220px] flex-col items-center justify-center gap-1 text-sm text-muted-foreground">
        <p>Couldn&apos;t render the activity chart.</p>
        <p className="font-mono text-xs">{renderError}</p>
      </div>
    );
  }

  if (!trades || trades.filter((t) => t.amount !== null).length === 0) {
    return (
      <div className="flex h-[220px] items-center justify-center">
        <EmptyState
          icon={Activity}
          message="No activity yet. Trading begins automatically at market open."
        />
      </div>
    );
  }

  return <div ref={containerRef} className="h-[220px] w-full" />;
}
