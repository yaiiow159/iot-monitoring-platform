import { useNavigate } from 'react-router-dom';
import type { Cabinet, Device, DeviceStatus } from '../api/types';
import { CABINET_TYPE_LABEL, STATUS_LABEL } from '../utils/format';

interface Props {
  cabinets: Cabinet[];
  devicesByCabinet: Map<string, Device[]>;
  /** 由 WebSocket 推播覆蓋的最新狀態，蓋在 REST 快照之上。 */
  liveStatuses: Record<string, DeviceStatus>;
  firingDeviceIds: Set<string>;
}

/** 一格 = 一個槽位。整櫃的健康狀況用「最差的那一格」代表，避免平均掉問題。 */
function worstStatus(statuses: DeviceStatus[]): DeviceStatus {
  if (statuses.includes('OFFLINE')) return 'OFFLINE';
  if (statuses.includes('DEGRADED')) return 'DEGRADED';
  if (statuses.includes('ONLINE')) return 'ONLINE';
  return 'UNKNOWN';
}

export function CabinetGrid({ cabinets, devicesByCabinet, liveStatuses, firingDeviceIds }: Props) {
  const navigate = useNavigate();

  return (
    <div className="cabinet-grid">
      {cabinets.map((cabinet) => {
        const devices = devicesByCabinet.get(cabinet.id) ?? [];
        const bySlot = new Map(devices.map((d) => [d.slot, d]));
        const statuses = devices.map((d) => liveStatuses[d.deviceId] ?? d.status);
        const health = worstStatus(statuses);
        const firing = devices.filter((d) => firingDeviceIds.has(d.deviceId)).length;

        return (
          <section key={cabinet.id} className={`cabinet health-${health}`}>
            <header className="cabinet-head">
              <div>
                <span className="cabinet-id">{cabinet.id}</span>
                <span className="cabinet-type">{CABINET_TYPE_LABEL[cabinet.type] ?? cabinet.type}</span>
              </div>
              <div className="cabinet-meta">
                <span className="sub">{cabinet.location}</span>
                {firing > 0 && <span className="badge badge-alarm">{firing} 告警</span>}
              </div>
            </header>

            {/* 兩側導軌純粹是視覺：讓這個格子讀起來像機櫃正面，而不是一張熱力圖 */}
            <div className="rack" aria-label={`${cabinet.id} 槽位`}>
              <span className="rail" aria-hidden="true" />
              <div className="slots">
                {Array.from({ length: cabinet.slotCount }, (_, i) => {
                  const slot = i + 1;
                  const device = bySlot.get(slot);
                  if (!device) {
                    return <span key={slot} className="slot slot-empty" title={`槽位 ${slot}：空`} />;
                  }
                  const status = liveStatuses[device.deviceId] ?? device.status;
                  const alarming = firingDeviceIds.has(device.deviceId);
                  return (
                    <button
                      key={slot}
                      type="button"
                      className={`slot slot-${status}${alarming ? ' slot-alarm' : ''}`}
                      title={`槽位 ${slot}｜${device.deviceId}｜${device.modelCode}｜${STATUS_LABEL[status]}${alarming ? '｜有未解除告警' : ''}`}
                      onClick={() => navigate(`/devices/${device.deviceId}`)}
                    />
                  );
                })}
              </div>
              <span className="rail" aria-hidden="true" />
            </div>

            <footer className="cabinet-foot">
              <span className="sub">
                {devices.length}/{cabinet.slotCount} 槽位使用
              </span>
              <span className="cabinet-health" title={`整櫃狀態：${STATUS_LABEL[health]}`}>
                <span className={`dot dot-${health}`} />
                {STATUS_LABEL[health]}
              </span>
            </footer>
          </section>
        );
      })}
    </div>
  );
}
