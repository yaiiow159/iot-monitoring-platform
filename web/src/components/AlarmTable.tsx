import { Link } from 'react-router-dom';
import type { Alarm } from '../api/types';
import { SEVERITY_LABEL, formatNumber, formatRelative, formatTime } from '../utils/format';

interface Props {
  alarms: Alarm[];
  /** 裝置詳情頁已經知道是哪台裝置了，重複一欄只是浪費寬度。 */
  showDevice?: boolean;
  emptyText?: string;
}

export function AlarmTable({ alarms, showDevice = true, emptyText = '目前沒有告警' }: Props) {
  if (alarms.length === 0) {
    return <p className="empty">{emptyText}</p>;
  }

  return (
    <div className="table-scroll">
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
          </tr>
        </thead>
        <tbody>
          {alarms.map((a) => (
            <tr key={a.alarmId}>
              <td>
                <span className={`sev sev-${a.severity}`}>{SEVERITY_LABEL[a.severity]}</span>
              </td>
              {showDevice && (
                <td>
                  <Link className="link" to={`/devices/${a.deviceId}`}>
                    {a.deviceId}
                  </Link>
                  <span className="sub"> {a.cabinetId}</span>
                </td>
              )}
              <td className="mono">{a.metric}</td>
              <td className="num mono warn">{formatNumber(a.value, 2)}</td>
              <td className="num mono">{formatNumber(a.threshold, 2)}</td>
              <td className="ellipsis">{a.message}</td>
              <td title={formatTime(a.firedAt)}>{formatRelative(a.firedAt)}</td>
              <td>
                <span className={`state state-${a.state}`}>
                  {a.state === 'FIRING' ? '未解除' : '已解除'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
