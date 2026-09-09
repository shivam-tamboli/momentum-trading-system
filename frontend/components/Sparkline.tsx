'use client';

import { useEffect, useRef } from 'react';
import { createChart, AreaSeries, ColorType, type IChartApi, type Time } from 'lightweight-charts';
import { useTheme } from 'next-themes';
import type { IndexPricePoint } from '@/lib/types';

interface SparklineProps {
  points: IndexPricePoint[] | undefined;
  width?: number;
  height?: number;
}

// Same real compiled hex values used for every other chart on this dashboard — pulled from the
// live CSS bundle, not computed by hand (canvas can't parse oklch()).
const PALETTES = {
  light: { gain: '#00a544', loss: '#e40014' },
  dark: { gain: '#00c565', loss: '#ff6568' },
} as const;

export function Sparkline({ points, width = 72, height = 24 }: SparklineProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const { resolvedTheme } = useTheme();
  const colors = resolvedTheme === 'light' ? PALETTES.light : PALETTES.dark;

  useEffect(() => {
    if (!containerRef.current || !points || points.length < 2) return;

    try {
      const sorted = [...points].sort((a, b) => (a.date < b.date ? -1 : 1));
      const isUp = sorted[sorted.length - 1].close >= sorted[0].close;
      const color = isUp ? colors.gain : colors.loss;

      const chart = createChart(containerRef.current, {
        width,
        height,
        layout: { background: { type: ColorType.Solid, color: 'transparent' }, attributionLogo: false },
        grid: { vertLines: { visible: false }, horzLines: { visible: false } },
        rightPriceScale: { visible: false },
        leftPriceScale: { visible: false },
        timeScale: { visible: false },
        crosshair: {
          vertLine: { visible: false, labelVisible: false },
          horzLine: { visible: false, labelVisible: false },
        },
        handleScroll: false,
        handleScale: false,
      });

      const series = chart.addSeries(AreaSeries, {
        lineColor: color,
        lineWidth: 1,
        topColor: `${color}33`,
        bottomColor: `${color}00`,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
      });

      series.setData(sorted.map((p) => ({ time: p.date as Time, value: p.close })));
      chart.timeScale().fitContent();
      chartRef.current = chart;
    } catch (error) {
      // A sparkline failing to render is decorative, not load-bearing — fail silently rather than
      // taking up space with an error message in a dense table row.
      console.error('Sparkline failed to render:', error);
    }

    return () => {
      chartRef.current?.remove();
      chartRef.current = null;
    };
  }, [points, colors, width, height]);

  if (!points || points.length < 2) {
    return <div style={{ width, height }} />;
  }

  return <div ref={containerRef} style={{ width, height }} />;
}
