/**
 * 這份型別是 docs/contracts.md 的 TypeScript 投影。
 * 欄位名稱與字面值一律照契約寫，client.ts 與 mock.ts 都以它為準——
 * 兩邊共用同一份型別，mock 才不會悄悄長出真實 API 沒有的欄位。
 */

/** 與 iot-domain 的 DeviceStatus 一致。 */
export type DeviceStatus = 'ONLINE' | 'DEGRADED' | 'OFFLINE' | 'UNKNOWN';

/** 與 iot-domain 的 AlarmSeverity 一致：刻意只有三級，分太細值班的人反而不知道要不要起床。 */
export type AlarmSeverity = 'INFO' | 'WARNING' | 'CRITICAL';

export type AlarmState = 'FIRING' | 'RESOLVED';

/** 契約規定回應一定帶這個欄位，前端也一定要顯示，否則使用者分不出原始值與聚合值。 */
export type Resolution = 'raw' | '1m' | '1h';

export type CabinetType = 'POWER' | 'SERVER' | 'SENSOR';

/** 與 iot-domain 的 Comparison 一致。OUT_OF_RANGE 需要 secondaryValue 當另一個邊界。 */
export type Comparison = 'GT' | 'GTE' | 'LT' | 'LTE' | 'OUT_OF_RANGE';

// ---------------------------------------------------------------- 設定中心

export interface MetricDefinition {
  key: string;
  unit: string;
  minValue: number;
  maxValue: number;
}

export interface DeviceModel {
  code: string;
  manufacturer: string;
  displayName: string;
  metrics: MetricDefinition[];
}

export interface Cabinet {
  id: string;
  name: string;
  type: CabinetType;
  location: string;
  slotCount: number;
}

export interface Device {
  deviceId: string;
  name: string;
  modelCode: string;
  cabinetId: string;
  /** 槽位編號，從 1 開始。機櫃檢視靠它決定裝置畫在哪一格。 */
  slot: number;
  status: DeviceStatus;
  lastSeenAt: string | null;
}

export interface AlarmRule {
  id: number;
  name: string;
  modelCode: string;
  metric: string;
  comparison: Comparison;
  threshold: number;
  /** 只有 OUT_OF_RANGE 用到：與 threshold 一起構成區間的上下界。 */
  secondaryValue?: number | null;
  /** 連續超標多久才觸發，用來濾掉單點雜訊。 */
  durationSeconds: number;
  severity: AlarmSeverity;
  enabled: boolean;
}

export interface CreateAlarmRuleRequest {
  name: string;
  modelCode: string;
  metric: string;
  comparison: Comparison;
  threshold: number;
  secondaryValue?: number | null;
  durationSeconds: number;
  severity: AlarmSeverity;
  enabled: boolean;
}

// ------------------------------------------------------------------ 監控

export interface Overview {
  /** 四種狀態都會出現，沒有裝置的狀態值為 0，前端不必補預設值。 */
  deviceCounts: Record<DeviceStatus, number>;
  unresolvedAlarms: number;
  /** 每秒寫入的資料點數。 */
  ingestRatePerSecond: number;
  generatedAt: string;
}

export interface Alarm {
  alarmId: number;
  deviceId: string;
  deviceName: string;
  cabinetId: string;
  metric: string;
  severity: AlarmSeverity;
  state: AlarmState;
  message: string;
  value: number;
  threshold: number;
  firedAt: string;
  resolvedAt: string | null;
}

/**
 * 聚合層的點才有 min/max/count；raw 層只有 avg（就是原始讀數本身）。
 * 因此 min/max 是選填，圖表要據此決定畫不畫帶狀區間。
 */
export interface TelemetryPoint {
  t: string;
  avg: number;
  min?: number;
  max?: number;
  count?: number;
}

export interface TelemetrySeries {
  deviceId: string;
  metric: string;
  resolution: Resolution;
  points: TelemetryPoint[];
}

export interface TelemetryQuery {
  deviceId: string;
  metric: string;
  from: string;
  to: string;
  /** 契約上限 5000，超過後端回 400。 */
  maxPoints?: number;
}

export interface DeviceQuery {
  status?: DeviceStatus;
  cabinetId?: string;
  modelCode?: string;
}

// -------------------------------------------------------------- WebSocket

export interface LiveTelemetryEvent {
  type: 'telemetry';
  deviceId: string;
  metrics: Record<string, number>;
  ts: number;
}

export interface LiveStatusEvent {
  type: 'status';
  deviceId: string;
  state: DeviceStatus;
  ts: number;
}

export interface LiveAlarmEvent {
  type: 'alarm';
  alarmId: number;
  deviceId: string;
  severity: AlarmSeverity;
  state: AlarmState;
  ts: number;
}

export type LiveEvent = LiveTelemetryEvent | LiveStatusEvent | LiveAlarmEvent;

/** 契約只定義 subscribe 一種動作，所以前端每次都送「目前可見裝置的完整集合」。 */
export interface SubscribeMessage {
  action: 'subscribe';
  deviceIds: string[];
}

/**
 * 真實 WebSocket 與 mock 推播共用的最小介面，
 * 讓 LiveProvider 不必知道自己連的是後端還是假資料。
 */
export interface LiveSocket {
  send(message: SubscribeMessage): void;
  close(): void;
}

export interface LiveSocketHandlers {
  onOpen(): void;
  onEvent(event: LiveEvent): void;
  onClose(): void;
}

/** 前後端共用的 API 介面。client.ts 與 mock.ts 各實作一份。 */
export interface IotApi {
  getOverview(): Promise<Overview>;
  listModels(): Promise<DeviceModel[]>;
  createModel(model: Omit<DeviceModel, never>): Promise<DeviceModel>;
  listCabinets(): Promise<Cabinet[]>;
  listDevices(query?: DeviceQuery): Promise<Device[]>;
  getDevice(deviceId: string): Promise<Device | null>;
  listAlarms(params?: { state?: AlarmState; deviceId?: string; limit?: number }): Promise<Alarm[]>;
  listAlarmRules(): Promise<AlarmRule[]>;
  createAlarmRule(rule: CreateAlarmRuleRequest): Promise<AlarmRule>;
  queryTelemetry(query: TelemetryQuery): Promise<TelemetrySeries>;
  connectLive(handlers: LiveSocketHandlers): LiveSocket;
}
