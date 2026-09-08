'use client';

import { useEffect, useRef, useState } from 'react';
import { AreaSeries, ColorType, createChart, type IChartApi, type Time } from 'lightweight-charts';
import { Skeleton } from '@/components/ui/skeleton';
import type { DailyTradeItem } from '@/lib/types';

interface AccountActivityChartProps {
  trades: DailyTradeItem[] | undefined;
  isLoading: boolean;
}

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
          textColor: 'oklch(0.708 0 0)',
          fontFamily: 'var(--font-geist-mono)',
          fontSize: 11,
        },
        grid: {
          vertLines: { visible: false },
          horzLines: { color: 'oklch(1 0 0 / 6%)' },
        },
        rightPriceScale: { borderVisible: false },
        timeScale: { borderVisible: false },
        crosshair: { vertLine: { labelBackgroundColor: 'oklch(0.623 0.214 259.815)' } },
        height: 220,
        autoSize: true,
      });

      const series = chart.addSeries(AreaSeries, {
        lineColor: 'oklch(0.623 0.214 259.815)',
        topColor: 'oklch(0.623 0.214 259.815 / 35%)',
        bottomColor: 'oklch(0.623 0.214 259.815 / 0%)',
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
      <div className="flex h-[220px] items-center justify-center text-sm text-muted-foreground">
        No trading activity yet.
      </div>
    );
  }

  return <div ref={containerRef} className="h-[220px] w-full" />;
}
