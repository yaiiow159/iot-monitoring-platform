import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { can, useSession } from '../auth/session';
import type { Alarm } from '../api/types';
import { SEVERITY_LABEL, formatNumber, formatRelative, formatTime } from '../utils/format';

interface Props {
  alarms: Alarm[];
  /** 裝置詳情頁已經知道是哪台裝置了，重複一欄只是浪費寬度。 */
  showDevice?: boolean;
  emptyText?: string;
  /** 有給就長出操作欄（認可／解除），動作完成後呼叫它重抓。唯讀角色仍然看不到按鈕。 */
  onChanged?: () => void;
}

export function AlarmTable({ alarms, showDevice = true, emptyText = '目前沒有告警', onChanged }: Props) {
  const session = useSession();
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const showActions = !!onChanged && can(session?.user, 'operate');

  async function run(alarmId: number, action: 'ack' | 'resolve') {
    setBusyId(alarmId);
    setError(null);
    try {
      await (action === 'ack' ? api.acknowledgeAlarm(alarmId) : api.resolveAlarm(alarmId));
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusyId(null);
    }
  }

  // 記住已經顯示過的告警：只有「這次才出現」的列才做一次入場動畫，初次載入不閃。
  const seen = useRef<Set<number> | null>(null);
  const isFirstRender = seen.current === null;
  if (seen.current === null) seen.current = new Set(alarms.map((a) => a.alarmId));
  const seenIds = seen.current;

  if (alarms.length === 0) {
    return <p className="empty">{emptyText}</p>;
  }

  return (
    <div className="table-scroll">
      {error && (
        <p className="error" role="alert">
          <span className="caption">後端拒絕</span>
          {error}
        </p>
      )}
      <table className="data-table">
        <thead>
          <tr>
            <th>嚴重度</th>
            {showDevice && <th>裝置</th>}
            <th>指標</th>
            <th className="num">讀數</th>
            <th className="num">門檻</th>
            <th>說明</th>
            <th>觸發時間</th>
            <th>狀態</th>
            {showActions && <th>操作</th>}
          </tr>
        </thead>
        <tbody>
          {alarms.map((a) => {
            const isNew = !isFirstRender && !seenIds.has(a.alarmId);
            seenIds.add(a.alarmId);
            return (
              <tr key={a.alarmId} className={`row-${a.severity}${isNew ? ' row-new' : ''}`}>
                <td>
                  <span className={`sev sev-${a.severity}`}>{SEVERITY_LABEL[a.severity]}</span>
                </td>
                {showDevice && (
                  <td>
                    <Link className="link mono" to={`/devices/${a.deviceId}`}>
                      {a.deviceId}
                    </Link>
                    <span className="sub"> {a.cabinetId}</span>
                  </td>
                )}
                <td className="mono">{a.metric}</td>
                <td className="num mono warn">{formatNumber(a.value, 2)}</td>
                <td className="num mono">{formatNumber(a.threshold, 2)}</td>
                <td className="ellipsis">{a.message}</td>
                <td className="sub" title={formatTime(a.firedAt)}>
                  {formatRelative(a.firedAt)}
                </td>
                <td>
                  <span className={`state state-${a.state}`}>
                    {a.state === 'RESOLVED' ? '已解除' : a.acknowledgedAt ? '處理中' : '未解除'}
                  </span>
                  {a.acknowledgedBy && a.state === 'FIRING' && (
                    <span className="sub" title={formatTime(a.acknowledgedAt!)}>
                      {' '}
                      {a.acknowledgedBy}
                    </span>
                  )}
                </td>
                {showActions && (
                  <td className="btn-row">
                    {a.state === 'FIRING' && !a.acknowledgedAt && (
                      <button
                        type="button"
                        className="btn"
                        disabled={busyId === a.alarmId}
                        onClick={() => run(a.alarmId, 'ack')}
                      >
                        認可
                      </button>
                    )}
                    {a.state === 'FIRING' && (
                      <button
                        type="button"
                        className="btn"
                        disabled={busyId === a.alarmId}
                        title="條件若仍成立，滿足持續時間後還會再響一次"
                        onClick={() => run(a.alarmId, 'resolve')}
                      >
                        解除
                      </button>
                    )}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
