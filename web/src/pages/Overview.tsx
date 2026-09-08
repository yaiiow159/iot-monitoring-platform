import { useMemo, useState } from 'react';
import { api } from '../api';
import type { Alarm, Cabinet, Device, DeviceStatus } from '../api/types';
import { AlarmTable } from '../components/AlarmTable';
import { CabinetGrid } from '../components/CabinetGrid';
import { useAsync } from '../hooks/useAsync';
import { useLive, useLiveDevices } from '../live/LiveContext';
import { SEVERITY_ORDER, STATUS_LABEL, formatInt, formatTime } from '../utils/format';

/**
 * 一頁塞不下 250 個機櫃，也不該塞——畫面上看不到的機櫃不必訂閱它的裝置。
 * 分頁的大小同時就是「這一刻要訂閱多少台裝置」的上限。
 */
const CABINETS_PER_PAGE = 12;

const STATUS_ORDER: DeviceStatus[] = ['ONLINE', 'DEGRADED', 'OFFLINE', 'UNKNOWN'];

export function Overview() {
  const [page, setPage] = useState(0);
  const [typeFilter, setTypeFilter] = useState<string>('');

  const overview = useAsync(() => api.getOverview(), []);
  const cabinets = useAsync(() => api.listCabinets(), []);
  const firingAlarms = useAsync(() => api.listAlarms({ state: 'FIRING', limit: 100 }), []);

  const filtered = useMemo(
    () => (cabinets.data ?? []).filter((c) => !typeFilter || c.type === typeFilter),
    [cabinets.data, typeFilter],
  );

  const pageCount = Math.max(1, Math.ceil(filtered.length / CABINETS_PER_PAGE));
  const safePage = Math.min(page, pageCount - 1);
  const visibleCabinets: Cabinet[] = filtered.slice(
    safePage * CABINETS_PER_PAGE,
    safePage * CABINETS_PER_PAGE + CABINETS_PER_PAGE,
  );

  const visibleIds = visibleCabinets.map((c) => c.id).join(',');

  // 契約沒有「一次查多個機櫃」的端點，所以照可見機櫃逐一查再併起來；
  // 一頁 12 個請求可以接受，一次撈一萬台裝置不行。
  const deviceGroups = useAsync(async () => {
    if (!visibleIds) return [] as Device[][];
    return Promise.all(visibleIds.split(',').map((cabinetId) => api.listDevices({ cabinetId })));
  }, [visibleIds]);

  const devicesByCabinet = useMemo(() => {
    const map = new Map<string, Device[]>();
    for (const group of deviceGroups.data ?? []) {
      if (group.length > 0) map.set(group[0].cabinetId, group);
    }
    return map;
  }, [deviceGroups.data]);

  const visibleDeviceIds = useMemo(
    () => [...devicesByCabinet.values()].flat().map((d) => d.deviceId),
    [devicesByCabinet],
  );

  // 這裡是「只訂閱看得見的裝置」實際生效的地方：翻頁或改篩選就換一批訂閱。
  useLiveDevices(visibleDeviceIds);

  const { statuses, alarms: liveAlarms, connection } = useLive();

  const firingDeviceIds = useMemo(() => {
    const set = new Set((firingAlarms.data ?? []).map((a) => a.deviceId));
    for (const a of liveAlarms) {
      if (a.state === 'FIRING') set.add(a.deviceId);
      else set.delete(a.deviceId);
    }
    return set;
  }, [firingAlarms.data, liveAlarms]);

  /** REST 快照打底，推播進來的新告警插到最前面，大屏才不必等下一次輪詢。 */
  const alarmRows: Alarm[] = useMemo(() => {
    const rows = [...(firingAlarms.data ?? [])];
    return rows.sort(
      (a, b) =>
        SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] ||
        Date.parse(b.firedAt) - Date.parse(a.firedAt),
    );
  }, [firingAlarms.data]);

  const counts = overview.data?.deviceCounts;

  return (
    <div className="page">
      <section className="stat-row">
        {STATUS_ORDER.map((status) => (
          <div key={status} className={`stat stat-${status}`}>
            <span className="stat-label">{STATUS_LABEL[status]}裝置</span>
            <span className="stat-value">{counts ? formatInt(counts[status]) : '—'}</span>
          </div>
        ))}
        <div className="stat stat-alarm">
          <span className="stat-label">未解除告警</span>
          <span className="stat-value">
            {overview.data ? formatInt(overview.data.unresolvedAlarms) : '—'}
          </span>
        </div>
        <div className="stat stat-rate">
          <span className="stat-label">目前寫入速率</span>
          <span className="stat-value">
            {overview.data ? formatInt(overview.data.ingestRatePerSecond) : '—'}
            <span className="stat-unit"> 點/秒</span>
          </span>
        </div>
      </section>

      {overview.error && <p className="error">摘要載入失敗：{overview.error}</p>}

      <div className="split">
        <section className="panel">
          <header className="panel-head">
            <h2>機櫃檢視</h2>
            <div className="panel-tools">
              <select
                className="input"
                value={typeFilter}
                onChange={(e) => {
                  setTypeFilter(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部類型</option>
                <option value="POWER">配電櫃</option>
                <option value="SERVER">伺服器櫃</option>
                <option value="SENSOR">感測櫃</option>
              </select>
              <span className="sub">
                第 {safePage + 1}/{pageCount} 頁（共 {formatInt(filtered.length)} 櫃）
              </span>
              <button className="btn" disabled={safePage === 0} onClick={() => setPage(safePage - 1)}>
                上一頁
              </button>
              <button
                className="btn"
                disabled={safePage >= pageCount - 1}
                onClick={() => setPage(safePage + 1)}
              >
                下一頁
              </button>
            </div>
          </header>

          <div className="legend">
            {STATUS_ORDER.map((s) => (
              <span key={s} className="legend-item">
                <span className={`dot dot-${s}`} />
                {STATUS_LABEL[s]}
              </span>
            ))}
            <span className="legend-item">
              <span className="dot dot-empty" />空槽
            </span>
            <span className="legend-item">
              <span className="dot dot-alarming" />有未解除告警
            </span>
          </div>

          {cabinets.loading || deviceGroups.loading ? (
            <p className="empty">載入中…</p>
          ) : (
            <CabinetGrid
              cabinets={visibleCabinets}
              devicesByCabinet={devicesByCabinet}
              liveStatuses={statuses}
              firingDeviceIds={firingDeviceIds}
            />
          )}
        </section>

        <section className="panel">
          <header className="panel-head">
            <h2>即時告警</h2>
            <span className="sub">
              {connection === 'open' ? '推播中' : '等待連線'}
              {overview.data && `｜更新於 ${formatTime(overview.data.generatedAt)}`}
            </span>
          </header>
          {firingAlarms.loading ? (
            <p className="empty">載入中…</p>
          ) : (
            <AlarmTable alarms={alarmRows} emptyText="目前沒有未解除的告警" />
          )}
        </section>
      </div>
    </div>
  );
}
