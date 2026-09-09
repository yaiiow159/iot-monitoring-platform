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

// ---------------------------------------------------------------- 登入與權限

/** 與 iot-domain 的 Role 一致，刻意只有三種。 */
export type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER';

export interface AuthUser {
  username: string;
  role: Role;
  displayName: string;
  expiresAt: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse extends AuthUser {
  token: string;
}

export interface AppUser {
  id: number;
  username: string;
  role: Role;
  displayName: string;
  enabled: boolean;
  createdAt: string;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  role: Role;
  displayName: string;
}

export type AuditOutcome = 'OK' | 'REJECTED' | 'DENIED' | 'FAILED';

export interface AuditEntry {
  id: number;
  at: string;
  actor: string;
  action: string;
  targetType: string;
  targetId: string | null;
  outcome: AuditOutcome;
  detail: Record<string, unknown> | null;
}

// ---------------------------------------------------------------- 設定中心

export interface MetricDefinition {
  key: string;
  /** 無因次量（功率因數、門磁）是空字串。 */
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
  /** 就是機櫃代號（CAB-001），同時是 Device.cabinetId 的關聯鍵。 */
  id: string;
  name: string;
  type: CabinetType;
  location: string;
  slotCount: number;
}

export interface CreateCabinetRequest {
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

export interface RegisterDeviceRequest {
  deviceId: string;
  name?: string;
  modelCode: string;
  cabinetId?: string;
  slot?: number;
}

/** modelCode 與 deviceId 恰有一個非 null：綁機型的規則套用到整個機型，綁裝置的是例外。 */
export interface AlarmRule {
  id: number;
  name: string;
  modelCode: string | null;
  deviceId?: string | null;
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
  modelCode?: string | null;
  deviceId?: string | null;
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

// -------------------------------------------------------------- 監控樹

/** Equipment 只能在根、Sensor 可無限自我嵌套、Device 是葉節點；規則由後端強制。 */
export type NodeKind = 'EQUIPMENT' | 'SENSOR' | 'DEVICE';

/**
 * 節點自己與整個子樹裡未解除告警的最高嚴重度與數量。
 * severity 為 null 代表子樹內沒有任何未解除告警。
 */
export interface Rollup {
  severity: AlarmSeverity | null;
  firing: number;
}

export interface TreeNode {
  id: number;
  kind: NodeKind;
  name: string;
  /** 同層排序鍵，之間留有間隔（預設 1000），插入中間不必重編整層。 */
  sortOrder: number;
  /** 只有 DEVICE 節點才有，指向裝置詳情頁。 */
  deviceId?: string;
  rollup: Rollup;
  /** 後端已依 (sortOrder, id) 排好；前端直接照陣列順序渲染，不得再排序。 */
  children: TreeNode[];
}

/** `/tree/{id}/ancestors` 回的路徑節點：只有節點本身，不帶子樹。 */
export type TreePathNode = Omit<TreeNode, 'children'>;

export interface CreateNodeRequest {
  kind: NodeKind;
  name: string;
  /** Equipment 不能有父節點，傳 null。 */
  parentId: number | null;
  deviceId?: string;
  /** 不指定就排在同層最後。 */
  sortOrder?: number;
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
  /**
   * 監控樹上從根到該裝置節點的路徑（由上到下，含裝置節點本身）。
   * 前端據此重抓整條鏈的 rollup，而不是只更新葉節點；裝置不在樹上時為空陣列。
   */
  ancestorIds: number[];
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
  login(request: LoginRequest): Promise<LoginResponse>;
  listUsers(): Promise<AppUser[]>;
  createUser(request: CreateUserRequest): Promise<AppUser>;
  listAudit(params?: { limit?: number; actor?: string }): Promise<AuditEntry[]>;
  getOverview(): Promise<Overview>;
  listModels(): Promise<DeviceModel[]>;
  createModel(model: DeviceModel): Promise<DeviceModel>;
  listCabinets(): Promise<Cabinet[]>;
  createCabinet(request: CreateCabinetRequest): Promise<Cabinet>;
  listDevices(query?: DeviceQuery): Promise<Device[]>;
  getDevice(deviceId: string): Promise<Device | null>;
  registerDevice(request: RegisterDeviceRequest): Promise<Device>;
  listAlarms(params?: { state?: AlarmState; deviceId?: string; limit?: number }): Promise<Alarm[]>;
  listAlarmRules(): Promise<AlarmRule[]>;
  createAlarmRule(rule: CreateAlarmRuleRequest): Promise<AlarmRule>;
  queryTelemetry(query: TelemetryQuery): Promise<TelemetrySeries>;
  /** 整棵樹的根節點清單（每個 Equipment 一棵），已排序、含 rollup。 */
  getTree(): Promise<TreeNode[]>;
  getSubtree(nodeId: number): Promise<TreeNode>;
  /** 從根到該節點的路徑，由上到下。 */
  getAncestors(nodeId: number): Promise<TreePathNode[]>;
  createNode(request: CreateNodeRequest): Promise<TreeNode>;
  reorderNode(nodeId: number, sortOrder: number): Promise<TreeNode>;
  connectLive(handlers: LiveSocketHandlers): LiveSocket;
}
