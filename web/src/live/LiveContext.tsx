import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { api } from '../api';
import type { AlarmSeverity, AlarmState, DeviceStatus, LiveEvent, LiveSocket } from '../api/types';

export type ConnectionState = 'connecting' | 'open' | 'closed';

export interface LiveReading {
  metrics: Record<string, number>;
  ts: number;
}

export interface LiveAlarmNotice {
  alarmId: number;
  deviceId: string;
  severity: AlarmSeverity;
  state: AlarmState;
  ts: number;
  /** 監控樹從根到裝置節點的路徑（由上到下）；空陣列代表裝置不在樹上。 */
  ancestorIds: number[];
}

interface LiveSnapshot {
  connection: ConnectionState;
  /** 只保留目前有訂閱的裝置，離開畫面就清掉，避免記憶體隨著瀏覽歷程無上限成長。 */
  readings: Record<string, LiveReading>;
  statuses: Record<string, DeviceStatus>;
  alarms: LiveAlarmNotice[];
  subscribedCount: number;
}

interface LiveContextValue extends LiveSnapshot {
  /** 由 useLiveDevices 呼叫；元件不要直接用。 */
  retain(deviceIds: string[]): () => void;
}

const LiveContext = createContext<LiveContextValue | null>(null);

/** 推播進來的頻率遠高於人眼能分辨的頻率，累積在 ref 裡每 250ms 才進 React 一次。 */
const FLUSH_INTERVAL_MS = 250;

export function LiveProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<LiveSnapshot>({
    connection: 'connecting',
    readings: {},
    statuses: {},
    alarms: [],
    subscribedCount: 0,
  });

  const socketRef = useRef<LiveSocket | null>(null);

  /**
   * 這是整個前端最重要的設計約束：一萬台裝置全訂閱會直接打爆瀏覽器，
   * 所以只訂閱「目前畫面上看得到的裝置」。用參考計數是因為同一台裝置可能同時
   * 出現在機櫃格與告警列表裡，任一方卸載都不該把另一方的訂閱一起收走。
   */
  const refCounts = useRef(new Map<string, number>());
  const pendingResubscribe = useRef<number | null>(null);

  // 待寫入的推播暫存區，避免每則訊息都觸發一次 render。
  const buffer = useRef({
    readings: new Map<string, LiveReading>(),
    statuses: new Map<string, DeviceStatus>(),
    alarms: [] as LiveAlarmNotice[],
    dirty: false,
  });

  const resubscribe = useCallback(() => {
    if (pendingResubscribe.current !== null) return;
    // 同一輪 render 內可能有多個元件掛載，合併成一次訂閱訊息再送。
    pendingResubscribe.current = window.setTimeout(() => {
      pendingResubscribe.current = null;
      const deviceIds = [...refCounts.current.keys()];
      // 契約只定義 subscribe，因此每次都送完整集合，由伺服器以最後一次為準。
      socketRef.current?.send({ action: 'subscribe', deviceIds });
      setSnapshot((prev) =>
        prev.subscribedCount === deviceIds.length ? prev : { ...prev, subscribedCount: deviceIds.length },
      );
    }, 50);
  }, []);

  const retain = useCallback(
    (deviceIds: string[]) => {
      const counts = refCounts.current;
      for (const id of deviceIds) counts.set(id, (counts.get(id) ?? 0) + 1);
      resubscribe();

      return () => {
        for (const id of deviceIds) {
          const next = (counts.get(id) ?? 1) - 1;
          if (next <= 0) {
            counts.delete(id);
            // 沒人看的裝置連同它的最後讀數一起丟掉，記憶體才不會愈跑愈肥。
            buffer.current.readings.delete(id);
            buffer.current.statuses.delete(id);
          } else {
            counts.set(id, next);
          }
        }
        resubscribe();
      };
    },
    [resubscribe],
  );

  useEffect(() => {
    const socket = api.connectLive({
      onOpen() {
        setSnapshot((prev) => ({ ...prev, connection: 'open' }));
        // 重連後把目前可見的集合重送一次，訂閱狀態不必自己記在別處。
        socket.send({ action: 'subscribe', deviceIds: [...refCounts.current.keys()] });
      },
      onClose() {
        setSnapshot((prev) => ({ ...prev, connection: 'connecting' }));
      },
      onEvent(event: LiveEvent) {
        const buf = buffer.current;
        buf.dirty = true;
        if (event.type === 'telemetry') {
          buf.readings.set(event.deviceId, { metrics: event.metrics, ts: event.ts });
        } else if (event.type === 'status') {
          buf.statuses.set(event.deviceId, event.state);
        } else {
          // 告警只留最近 50 則，大屏上再多也看不完，留著只是佔記憶體。
          buf.alarms = [
            {
              alarmId: event.alarmId,
              deviceId: event.deviceId,
              severity: event.severity,
              state: event.state,
              ts: event.ts,
              // 舊版後端可能還沒帶這個欄位，補成空陣列讓監控樹安全略過。
              ancestorIds: event.ancestorIds ?? [],
            },
            ...buf.alarms,
          ].slice(0, 50);
        }
      },
    });
    socketRef.current = socket;

    const flush = window.setInterval(() => {
      const buf = buffer.current;
      if (!buf.dirty) return;
      buf.dirty = false;
      setSnapshot((prev) => ({
        ...prev,
        readings: Object.fromEntries(buf.readings),
        statuses: Object.fromEntries(buf.statuses),
        alarms: buf.alarms,
      }));
    }, FLUSH_INTERVAL_MS);

    return () => {
      window.clearInterval(flush);
      socket.close();
      socketRef.current = null;
    };
  }, []);

  const value = useMemo<LiveContextValue>(() => ({ ...snapshot, retain }), [snapshot, retain]);

  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export function useLive(): LiveContextValue {
  const ctx = useContext(LiveContext);
  if (!ctx) throw new Error('useLive 必須在 LiveProvider 內使用');
  return ctx;
}

/**
 * 宣告「這個元件目前看得到這些裝置」。掛載時訂閱、卸載時退訂，
 * 換頁或翻頁時瀏覽器接收的推播量就會跟著畫面走，而不是跟著裝置總數走。
 */
export function useLiveDevices(deviceIds: string[]): void {
  const { retain } = useLive();
  // 依內容而非陣列參考來比較，否則每次 render 都會退訂再訂閱一輪。
  const key = deviceIds.join(',');
  useEffect(() => {
    if (!key) return;
    return retain(key.split(','));
  }, [key, retain]);
}
