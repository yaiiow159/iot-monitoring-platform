import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { AlarmSeverity, ReplaySnapshot, ReplayTimeline } from '../api/types';
import { useAsync } from '../hooks/useAsync';
import { CABINET_TYPE_LABEL, RESOLUTION_LABEL, SEVERITY_LABEL, formatInt, formatNumber, formatTime } from '../utils/format';

/*
 * 歷史回放：拖動時間軸，看某個機櫃在那一刻長什麼樣。
 * 這一頁存在的理由是把「兩年資料一秒內」變成看得到的東西：
 * 每次拖動都真的打後端、後端真的讀三層中的一層、回應帶查詢耗時，畫面把它印出來。
 */

const RANGES: { key: string; label: string; ms: number }[] = [
  { key: '1h', label: '1 小時', ms: 3_600_000 },
  { key: '24h', label: '24 小時', ms: 86_400_000 },
  { key: '7d', label: '7 天', ms: 7 * 86_400_000 },
  { key: '30d', label: '30 天', ms: 30 * 86_400_000 },
  { key: '1y', label: '1 年', ms: 365 * 86_400_000 },
  { key: '2y', label: '2 年', ms: 2 * 365 * 86_400_000 },
];

/** 滑桿的刻度數。一千格在 2 年的範圍下每格約 17 小時，剛好是小時層能區分的量級。 */
const STEPS = 1000;
const DEBOUNCE_MS = 120;
const PLAY_INTERVAL_MS = 350;

const SEV_RANK: Record<AlarmSeverity, number> = { INFO: 1, WARNING: 2, CRITICAL: 3 };

function worst(alarms: { severity: AlarmSeverity }[]): AlarmSeverity | null {
  let w: AlarmSeverity | null = null;
  for (const a of alarms) if (!w || SEV_RANK[a.severity] > SEV_RANK[w]) w = a.severity;
  return w;
}

export function Replay() {
  const cabinets = useAsync(() => api.listCabinets(), []);
  const [cabinetId, setCabinetId] = useState('');
  const [rangeKey, setRangeKey] = useState('24h');
  const [position, setPosition] = useState(STEPS); // 1000 = 現在
  const [playing, setPlaying] = useState(false);

  // 範圍的終點固定在進入頁面（或切換範圍）的那一刻，滑桿才不會自己往右漂
  const [anchor, setAnchor] = useState(() => Date.now());
  const range = RANGES.find((r) => r.key === rangeKey) ?? RANGES[1];
  const from = anchor - range.ms;
  const atMs = from + (range.ms * position) / STEPS;

  useEffect(() => {
    if (!cabinetId && cabinets.data && cabinets.data.length > 0) setCabinetId(cabinets.data[0].id);
  }, [cabinets.data, cabinetId]);

  // 時間軸上的告警標記：換機櫃或範圍才重抓，拖動不重抓
  const timeline = useAsync<ReplayTimeline | null>(
    () => (cabinetId ? api.getReplayTimeline(cabinetId, new Date(from).toISOString(), new Date(anchor).toISOString()) : Promise.resolve(null)),
    [cabinetId, from, anchor],
  );

  // 快照：拖動時去抖動，只送最後一個位置；回應若比目前位置舊就丟掉
  const [snapshot, setSnapshot] = useState<ReplaySnapshot | null>(null);
  const [roundTripMs, setRoundTripMs] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const latest = useRef(0);
  useEffect(() => {
    if (!cabinetId) return;
    const seq = ++latest.current;
    const timer = window.setTimeout(async () => {
      const started = performance.now();
      try {
        const s = await api.getReplay(cabinetId, new Date(atMs).toISOString());
        if (seq !== latest.current) return;
        setSnapshot(s);
        setRoundTripMs(Math.round(performance.now() - started));
        setError(null);
      } catch (err) {
        if (seq !== latest.current) return;
        setError(err instanceof Error ? err.message : String(err));
      }
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [cabinetId, atMs]);

  // 播放：每 350ms 往右一格，到底就停
  useEffect(() => {
    if (!playing) return;
    const id = window.setInterval(() => {
      setPosition((p) => {
        if (p >= STEPS) {
          setPlaying(false);
          return p;
        }
        return Math.min(STEPS, p + Math.max(1, Math.round(STEPS / 200)));
      });
    }, PLAY_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [playing]);

  const cabinet = (cabinets.data ?? []).find((c) => c.id === cabinetId) ?? null;
  // 沒有快照時每次 render 都會是一個新的空陣列，底下三個 useMemo 就等於沒有快取
  const devices = useMemo(() => snapshot?.devices ?? [], [snapshot]);
  const bySlot = useMemo(() => new Map(devices.map((d) => [d.slot ?? 0, d])), [devices]);
  const activeAlarms = useMemo(
    () => devices.flatMap((d) => d.alarms.map((a) => ({ ...a, deviceId: d.deviceId }))),
    [devices],
  );
  const withData = devices.filter((d) => d.hadData).length;

  // 這個機櫃所有裝置回報過的指標，當成表格欄位；順序照第一次出現
  const metricKeys = useMemo(() => {
    const keys: string[] = [];
    for (const d of devices) for (const k of Object.keys(d.readings)) if (!keys.includes(k)) keys.push(k);
    return keys;
  }, [devices]);

  const markers = useMemo(() => {
    const list = timeline.data?.alarms ?? [];
    return list.map((a) => {
      const start = Math.max(from, Date.parse(a.firedAt));
      const end = Math.min(anchor, a.resolvedAt ? Date.parse(a.resolvedAt) : anchor);
      return {
        key: a.alarmId,
        left: ((start - from) / range.ms) * 100,
        width: Math.max(0.15, ((end - start) / range.ms) * 100),
        severity: a.severity,
        title: `${a.deviceId}｜${a.message}｜${formatTime(a.firedAt)} → ${a.resolvedAt ? formatTime(a.resolvedAt) : '未解除'}`,
      };
    });
  }, [timeline.data, from, anchor, range.ms]);

  function changeRange(key: string) {
    setRangeKey(key);
    setAnchor(Date.now());
    setPosition(STEPS);
    setPlaying(false);
  }

  return (
    <div className="page">
      <section className="panel replay-toolbar">
        <header className="panel-head">
          <h2>歷史回放</h2>
          <span className="sub">拖動時間軸，看機櫃在那一刻的讀數與告警。每次拖動都真的打後端，右邊的毫秒數是證據。</span>
        </header>

        <div className="replay-controls">
          <select className="input" value={cabinetId} onChange={(e) => setCabinetId(e.target.value)}>
            {(cabinets.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.id}｜{CABINET_TYPE_LABEL[c.type]}｜{c.location}
              </option>
            ))}
          </select>

          <div className="seg" role="radiogroup" aria-label="時間範圍">
            {RANGES.map((r) => (
              <button
                key={r.key}
                type="button"
                className={r.key === rangeKey ? 'seg-item active' : 'seg-item'}
                onClick={() => changeRange(r.key)}
              >
                {r.label}
              </button>
            ))}
          </div>

          <button className="btn" type="button" onClick={() => setPlaying((p) => !p)} disabled={!cabinetId}>
            {playing ? '❚❚ 暫停' : '▶ 播放'}
          </button>
          <button
            className="btn"
            type="button"
            onClick={() => {
              setPosition(STEPS);
              setAnchor(Date.now());
            }}
          >
            回到現在
          </button>
        </div>

        <div className="replay-timeline">
          <div className="replay-markers" aria-hidden="true">
            {markers.map((m) => (
              <span
                key={m.key}
                className={`replay-marker sev-bg-${m.severity}`}
                style={{ left: `${m.left}%`, width: `${m.width}%` }}
                title={m.title}
              />
            ))}
          </div>
          <input
            className="replay-slider"
            type="range"
            min={0}
            max={STEPS}
            value={position}
            onChange={(e) => {
              setPlaying(false);
              setPosition(Number(e.target.value));
            }}
            aria-label="回放時間"
          />
          <div className="replay-scale sub num">
            <span>{formatTime(from)}</span>
            <span className="replay-at mono">{formatTime(atMs)}</span>
            <span>{formatTime(anchor)}</span>
          </div>
        </div>

        <div className="replay-stats">
          {snapshot && (
            <>
              <span className="stat-chip">
                <span className="caption">解析度</span>
                <span className={`res-badge res-${snapshot.resolution}`}>{RESOLUTION_LABEL[snapshot.resolution]}</span>
              </span>
              <span className="stat-chip">
                <span className="caption">桶</span>
                <span className="mono">{formatTime(snapshot.bucketStart)} ～ {formatTime(snapshot.bucketEnd)}</span>
              </span>
              <span className="stat-chip">
                <span className="caption">後端查詢</span>
                <span className={`mono latency ${snapshot.queryMs < 1000 ? 'latency-ok' : 'latency-slow'}`}>{snapshot.queryMs} ms</span>
              </span>
              <span className="stat-chip">
                <span className="caption">來回</span>
                <span className="mono">{roundTripMs ?? '—'} ms</span>
              </span>
              <span className="stat-chip">
                <span className="caption">有資料的裝置</span>
                <span className="mono">
                  {formatInt(withData)}/{formatInt(devices.length)}
                </span>
              </span>
              <span className="stat-chip">
                <span className="caption">當時的告警</span>
                <span className="mono">{formatInt(activeAlarms.length)}</span>
              </span>
              {timeline.data?.truncated && <span className="warn-box">時間軸標記超過上限，只畫前 1000 則</span>}
            </>
          )}
          {error && <p className="error">{error}</p>}
        </div>
      </section>

      <div className="split">
        <section className="panel">
          <header className="panel-head">
            <h2>
              {cabinet ? `${cabinet.id}` : '機櫃'}
              {cabinet && <span className="sub">{CABINET_TYPE_LABEL[cabinet.type]}・{cabinet.location}</span>}
            </h2>
            <span className="sub">{snapshot ? formatTime(snapshot.at) : ''}</span>
          </header>
          {cabinet && snapshot ? (
            <div className="rack replay-rack" aria-label={`${cabinet.id} 在 ${formatTime(snapshot.at)} 的槽位`}>
              <span className="rail" aria-hidden="true" />
              <div className="slots">
                {Array.from({ length: cabinet.slotCount }, (_, i) => {
                  const slot = i + 1;
                  const d = bySlot.get(slot);
                  if (!d) return <span key={slot} className="slot slot-empty" title={`槽位 ${slot}：空`} />;
                  const sev = worst(d.alarms);
                  const status = d.hadData ? 'ONLINE' : 'OFFLINE';
                  return (
                    <Link
                      key={slot}
                      to={`/devices/${d.deviceId}`}
                      className={`slot slot-${status}${sev ? ' slot-alarm' : ''}`}
                      title={`槽位 ${slot}｜${d.deviceId}｜${d.modelCode}｜${d.hadData ? '有讀數' : '這一桶沒有資料'}${sev ? `｜${SEVERITY_LABEL[sev]}` : ''}`}
                    />
                  );
                })}
              </div>
              <span className="rail" aria-hidden="true" />
            </div>
          ) : (
            <p className="empty">{cabinets.loading ? '載入中…' : '選一個機櫃'}</p>
          )}
          <p className="hint">綠＝那一桶有讀數，紅＝沒有；閃爍＝當時有未解除告警。「沒有資料」不等於「離線」——上下線狀態沒有歷史，這裡只誠實地說那段時間有沒有收到東西。</p>
        </section>

        <section className="panel">
          <header className="panel-head">
            <h2>當時的告警</h2>
            <span className="sub">{formatInt(activeAlarms.length)} 則</span>
          </header>
          {activeAlarms.length === 0 ? (
            <p className="empty">這一刻沒有未解除的告警</p>
          ) : (
            <div className="table-scroll">
              <table className="data-table compact">
                <thead>
                  <tr>
                    <th>嚴重度</th>
                    <th>裝置</th>
                    <th>指標</th>
                    <th>說明</th>
                    <th>觸發</th>
                    <th>解除</th>
                  </tr>
                </thead>
                <tbody>
                  {activeAlarms.map((a) => (
                    <tr key={a.alarmId}>
                      <td>
                        <span className={`sev sev-${a.severity}`}>{SEVERITY_LABEL[a.severity]}</span>
                      </td>
                      <td className="mono">{a.deviceId}</td>
                      <td className="mono">{a.metric}</td>
                      <td>{a.message}</td>
                      <td className="sub">{formatTime(a.firedAt)}</td>
                      <td className="sub">{a.resolvedAt ? formatTime(a.resolvedAt) : '之後才解除或仍未解除'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>

      <section className="panel">
        <header className="panel-head">
          <h2>讀數</h2>
          <span className="sub">
            {snapshot ? `${RESOLUTION_LABEL[snapshot.resolution]}，平均值；括號內是該桶的最小／最大` : ''}
          </span>
        </header>
        {devices.length === 0 ? (
          <p className="empty">這個機櫃沒有裝置</p>
        ) : (
          <div className="table-scroll tall">
            <table className="data-table compact replay-readings">
              <thead>
                <tr>
                  <th className="num">槽位</th>
                  <th>裝置</th>
                  <th>機型</th>
                  {metricKeys.map((k) => (
                    <th key={k} className="num mono">
                      {k}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {devices.map((d) => (
                  <tr key={d.deviceId} className={d.hadData ? undefined : 'disabled-row'}>
                    <td className="num mono">{d.slot ?? '—'}</td>
                    <td className="mono">
                      <Link to={`/devices/${d.deviceId}`}>{d.deviceId}</Link>
                    </td>
                    <td className="mono sub">{d.modelCode}</td>
                    {metricKeys.map((k) => {
                      const r = d.readings[k];
                      return (
                        <td key={k} className="num mono">
                          {r ? (
                            <>
                              {formatNumber(r.avg, 2)}
                              {r.count > 1 && <span className="sub"> ({formatNumber(r.min, 1)}–{formatNumber(r.max, 1)})</span>}
                            </>
                          ) : (
                            <span className="sub">—</span>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
