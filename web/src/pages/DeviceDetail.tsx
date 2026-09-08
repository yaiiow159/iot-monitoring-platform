import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { MetricDefinition } from '../api/types';
import { AlarmTable } from '../components/AlarmTable';
import { TimeSeriesChart } from '../components/TimeSeriesChart';
import { useAsync } from '../hooks/useAsync';
import { useLive, useLiveDevices } from '../live/LiveContext';
import {
  RESOLUTION_HINT,
  RESOLUTION_LABEL,
  STATUS_LABEL,
  formatNumber,
  formatRelative,
} from '../utils/format';

const HOUR = 3_600_000;
const DAY = 86_400_000;

/** 時間範圍決定後端讀哪一層聚合，所以選項直接對齊 README 的查詢路由表。 */
const RANGES = [
  { key: '1h', label: '1 小時', spanMs: HOUR },
  { key: '6h', label: '6 小時', spanMs: 6 * HOUR },
  { key: '24h', label: '24 小時', spanMs: DAY },
  { key: '7d', label: '7 天', spanMs: 7 * DAY },
  { key: '30d', label: '30 天', spanMs: 30 * DAY },
  { key: '1y', label: '1 年', spanMs: 365 * DAY },
] as const;

export function DeviceDetail() {
  const { deviceId = '' } = useParams();
  const [rangeKey, setRangeKey] = useState<string>('6h');
  const [metricKey, setMetricKey] = useState<string>('');

  // 裝置詳情只看一台，訂閱量固定是 1——這頁不會有訂閱爆量的問題。
  useLiveDevices(useMemo(() => (deviceId ? [deviceId] : []), [deviceId]));
  const { readings, statuses } = useLive();

  const device = useAsync(() => api.getDevice(deviceId), [deviceId]);
  const models = useAsync(() => api.listModels(), []);
  const alarms = useAsync(() => api.listAlarms({ deviceId }), [deviceId]);

  const model = useMemo(
    () => (models.data ?? []).find((m) => m.code === device.data?.modelCode) ?? null,
    [models.data, device.data],
  );

  const metrics: MetricDefinition[] = model?.metrics ?? [];
  const activeMetric = metricKey || metrics[0]?.key || '';
  const activeDef = metrics.find((m) => m.key === activeMetric) ?? null;

  const range = RANGES.find((r) => r.key === rangeKey) ?? RANGES[1];

  // to 對齊到分鐘，否則每次 render 的時間戳都不同，會無限重打查詢。
  const { from, to } = useMemo(() => {
    const end = Math.floor(Date.now() / 60_000) * 60_000;
    return { from: new Date(end - range.spanMs).toISOString(), to: new Date(end).toISOString() };
  }, [range.spanMs]);

  const telemetry = useAsync(
    () =>
      activeMetric
        ? api.queryTelemetry({ deviceId, metric: activeMetric, from, to, maxPoints: 800 })
        : Promise.resolve(null),
    [deviceId, activeMetric, from, to],
  );

  const live = readings[deviceId];
  const status = statuses[deviceId] ?? device.data?.status ?? 'UNKNOWN';

  if (device.loading) return <p className="empty">載入中…</p>;
  if (!device.data) return <p className="error">找不到裝置 {deviceId}</p>;

  const d = device.data;

  return (
    <div className="page">
      <section className="panel">
        <header className="panel-head">
          <h2>
            {d.deviceId}
            <span className="sub"> {d.name}</span>
          </h2>
          <div className="panel-tools">
            <span className={`state state-${status}`}>{STATUS_LABEL[status]}</span>
            <span className="sub">
              機型 <span className="mono">{d.modelCode}</span>
            </span>
            <span className="sub">
              機櫃{' '}
              <Link className="link" to="/">
                {d.cabinetId}
              </Link>{' '}
              / 槽位 {d.slot}
            </span>
            <span className="sub">最後回報 {formatRelative(live?.ts ?? d.lastSeenAt)}</span>
          </div>
        </header>

        <div className="metric-row">
          {metrics.map((m) => {
            const value = live?.metrics[m.key];
            const outOfRange = value !== undefined && (value < m.minValue || value > m.maxValue);
            return (
              <button
                key={m.key}
                type="button"
                className={`metric-card${m.key === activeMetric ? ' active' : ''}${outOfRange ? ' out-of-range' : ''}`}
                onClick={() => setMetricKey(m.key)}
              >
                <span className="metric-key">{m.key}</span>
                <span className="metric-value">
                  {value === undefined ? '—' : formatNumber(value, 2)}
                  <span className="metric-unit"> {m.unit}</span>
                </span>
                <span className="metric-range">
                  量程 {m.minValue} ~ {m.maxValue}
                  {outOfRange && <span className="warn"> 超出量程</span>}
                </span>
              </button>
            );
          })}
          {metrics.length === 0 && <p className="empty">機型未定義指標</p>}
        </div>
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>歷史趨勢</h2>
          <div className="panel-tools">
            <div className="segmented">
              {RANGES.map((r) => (
                <button
                  key={r.key}
                  type="button"
                  className={r.key === rangeKey ? 'seg active' : 'seg'}
                  onClick={() => setRangeKey(r.key)}
                >
                  {r.label}
                </button>
              ))}
            </div>
            {/* 契約規定回應一定帶 resolution，使用者必須知道自己看的是原始值還是聚合值。 */}
            {telemetry.data && (
              <span
                className={`res res-${telemetry.data.resolution}`}
                title={RESOLUTION_HINT[telemetry.data.resolution]}
              >
                {RESOLUTION_LABEL[telemetry.data.resolution]}
              </span>
            )}
          </div>
        </header>

        {telemetry.error && <p className="error">查詢失敗：{telemetry.error}</p>}
        {telemetry.loading && <p className="empty">查詢中…</p>}

        {telemetry.data && (
          <>
            <p className="hint">{RESOLUTION_HINT[telemetry.data.resolution]}</p>
            <TimeSeriesChart
              key={`${telemetry.data.deviceId}-${telemetry.data.metric}-${rangeKey}`}
              series={telemetry.data}
              unit={activeDef?.unit ?? ''}
              height={300}
            />
            <p className="sub">
              指標 <span className="mono">{telemetry.data.metric}</span>｜
              {telemetry.data.points.length} 個資料點｜層級{' '}
              <span className="mono">{telemetry.data.resolution}</span>
            </p>
          </>
        )}
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>告警歷史</h2>
          <span className="sub">含已解除</span>
        </header>
        {alarms.loading ? (
          <p className="empty">載入中…</p>
        ) : (
          <AlarmTable alarms={alarms.data ?? []} showDevice={false} emptyText="這台裝置沒有告警紀錄" />
        )}
      </section>
    </div>
  );
}
