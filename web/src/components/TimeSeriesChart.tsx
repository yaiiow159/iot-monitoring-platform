import { useEffect, useRef } from 'react';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { TelemetrySeries } from '../api/types';

/**
 * 為什麼是 uPlot 而不是 Chart.js／ECharts：
 * 這是即時時序儀表板，一萬台裝置、每秒更新，圖表可能同時開好幾張、每張數千點。
 * uPlot 是目前渲染時序資料最快的函式庫（canvas、無虛擬 DOM、無動畫層），
 * 打包體積也最小（約 45KB，Chart.js 與 ECharts 都在數百 KB），
 * 對「掛在牆上長時間不重整」的監控大屏來說，記憶體與 GC 壓力比 API 好不好用重要得多。
 */

interface Props {
  series: TelemetrySeries;
  unit: string;
  height?: number;
}

const AVG_COLOR = '#58a6ff';
const BAND_STROKE = 'rgba(88, 166, 255, 0.28)';
const BAND_FILL = 'rgba(88, 166, 255, 0.16)';

export function TimeSeriesChart({ series, unit, height = 260 }: Props) {
  const hostRef = useRef<HTMLDivElement>(null);
  const plotRef = useRef<uPlot | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    const xs = series.points.map((p) => Date.parse(p.t) / 1000);
    const avg = series.points.map((p) => p.avg);
    // 聚合層才有 min/max。只畫 avg 會讓尖峰被平均吃掉，那是監控系統最常見的誤導。
    const hasBand = series.points.length > 0 && series.points[0].min !== undefined;

    const data: uPlot.AlignedData = hasBand
      ? [
          xs,
          series.points.map((p) => p.max ?? p.avg),
          series.points.map((p) => p.min ?? p.avg),
          avg,
        ]
      : [xs, avg];

    const uSeries: uPlot.Series[] = hasBand
      ? [
          {},
          { label: '最高', stroke: BAND_STROKE, width: 1, points: { show: false } },
          { label: '最低', stroke: BAND_STROKE, width: 1, points: { show: false } },
          { label: '平均', stroke: AVG_COLOR, width: 1.6, points: { show: false } },
        ]
      : [{}, { label: '讀數', stroke: AVG_COLOR, width: 1.4, points: { show: false } }];

    const opts: uPlot.Options = {
      width: host.clientWidth || 640,
      height,
      series: uSeries,
      // bands 填滿「最高」與「最低」兩條線之間，形成量程帶。
      bands: hasBand ? [{ series: [1, 2], fill: BAND_FILL }] : undefined,
      axes: [
        {
          stroke: '#8b98a5',
          grid: { stroke: '#212a35', width: 1 },
          ticks: { stroke: '#212a35' },
          font: '11px ui-monospace, monospace',
        },
        {
          stroke: '#8b98a5',
          grid: { stroke: '#212a35', width: 1 },
          ticks: { stroke: '#212a35' },
          font: '11px ui-monospace, monospace',
          label: unit || undefined,
          labelSize: unit ? 24 : 0,
          labelFont: '11px system-ui',
        },
      ],
      cursor: { drag: { x: true, y: false } },
      legend: { show: true },
    };

    const plot = new uPlot(opts, data, host);
    plotRef.current = plot;

    // 大屏會被縮放或側欄收合，寬度得跟著容器走而不是固定值。
    const ro = new ResizeObserver(() => plot.setSize({ width: host.clientWidth || 640, height }));
    ro.observe(host);

    return () => {
      ro.disconnect();
      plot.destroy();
      plotRef.current = null;
    };
  }, [series, unit, height]);

  return <div className="chart-host" ref={hostRef} />;
}
