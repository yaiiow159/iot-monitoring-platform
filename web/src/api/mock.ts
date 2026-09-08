import type {
  Alarm,
  AlarmRule,
  AlarmSeverity,
  Cabinet,
  CabinetType,
  CreateNodeRequest,
  Device,
  DeviceModel,
  DeviceQuery,
  DeviceStatus,
  IotApi,
  LiveSocket,
  LiveSocketHandlers,
  NodeKind,
  Overview,
  Resolution,
  SubscribeMessage,
  TelemetryPoint,
  TelemetryQuery,
  TelemetrySeries,
  TreeNode,
  TreePathNode,
} from './types';

/**
 * 後端尚未完成，這裡用假資料實作與真實 API 完全相同的介面（IotApi）。
 * 規模刻意做到契約講的一萬台，這樣「只訂閱看得見的裝置」這個約束
 * 在開發階段就會被真的驗證到，而不是等接上後端才發現前端撐不住。
 */

const CABINET_COUNT = 250;
const SLOTS_PER_CABINET = 40;

// ------------------------------------------------------- 決定性亂數

/** 用 seed 產生固定亂數，讓同一台裝置的圖表在重新整理後長得一樣，方便比對。 */
function hash(seed: string): number {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

function pick<T>(items: readonly T[], seed: string): T {
  return items[Math.floor(hash(seed) * items.length) % items.length];
}

// ------------------------------------------------------- 機型

const MODELS: DeviceModel[] = [
  {
    code: 'TH-100',
    manufacturer: 'Fujitek',
    displayName: '溫濕度感測器',
    metrics: [
      { key: 'temperature', unit: '°C', minValue: -20, maxValue: 80 },
      { key: 'humidity', unit: '%', minValue: 0, maxValue: 100 },
    ],
  },
  {
    code: 'PWR-400',
    manufacturer: 'Delta',
    displayName: '電力模組',
    metrics: [
      { key: 'voltage', unit: 'V', minValue: 180, maxValue: 260 },
      { key: 'current', unit: 'A', minValue: 0, maxValue: 63 },
      { key: 'powerFactor', unit: '', minValue: 0, maxValue: 1 },
    ],
  },
  {
    code: 'CAB-CTRL-900',
    manufacturer: 'Advantech',
    displayName: '機櫃控制器',
    metrics: [
      { key: 'temperature', unit: '°C', minValue: -20, maxValue: 80 },
      { key: 'humidity', unit: '%', minValue: 0, maxValue: 100 },
      { key: 'voltage', unit: 'V', minValue: 180, maxValue: 260 },
      { key: 'current', unit: 'A', minValue: 0, maxValue: 63 },
      { key: 'doorOpen', unit: '', minValue: 0, maxValue: 1 },
      { key: 'fanRpm', unit: 'rpm', minValue: 0, maxValue: 6000 },
    ],
  },
  {
    code: 'AIR-220',
    manufacturer: 'Sensirion',
    displayName: '空氣品質感測器',
    metrics: [
      { key: 'pm25', unit: 'µg/m³', minValue: 0, maxValue: 500 },
      { key: 'co2', unit: 'ppm', minValue: 300, maxValue: 5000 },
    ],
  },
];

const MODEL_BY_CODE = new Map(MODELS.map((m) => [m.code, m]));

/** 機櫃類型決定它容得下哪些機型，與 README 的模擬情境一致。 */
const MODELS_BY_CABINET_TYPE: Record<CabinetType, string[]> = {
  POWER: ['PWR-400', 'CAB-CTRL-900'],
  SERVER: ['CAB-CTRL-900', 'TH-100'],
  SENSOR: ['TH-100', 'AIR-220'],
};

const CABINET_TYPES: CabinetType[] = ['POWER', 'SERVER', 'SENSOR'];
const ZONES = ['A 區', 'B 區', 'C 區', 'D 區', 'E 區'];

// ------------------------------------------------------- 機櫃與裝置

function pad(n: number, width: number): string {
  return String(n).padStart(width, '0');
}

const cabinets: Cabinet[] = Array.from({ length: CABINET_COUNT }, (_, i) => {
  const id = `CAB-${pad(i + 1, 3)}`;
  const type = CABINET_TYPES[i % CABINET_TYPES.length];
  return {
    id,
    name: `${type === 'POWER' ? '配電櫃' : type === 'SERVER' ? '伺服器櫃' : '感測櫃'} ${pad(i + 1, 3)}`,
    type,
    location: `${ZONES[i % ZONES.length]} / 第 ${Math.floor(i / ZONES.length) + 1} 排`,
    slotCount: SLOTS_PER_CABINET,
  };
});

function rollStatus(seed: string): DeviceStatus {
  const r = hash(seed);
  if (r < 0.86) return 'ONLINE';
  if (r < 0.93) return 'DEGRADED';
  if (r < 0.985) return 'OFFLINE';
  return 'UNKNOWN';
}

const devices: Device[] = [];
{
  let serial = 0;
  for (const cabinet of cabinets) {
    const allowed = MODELS_BY_CABINET_TYPE[cabinet.type];
    for (let slot = 1; slot <= cabinet.slotCount; slot++) {
      // 留一些空槽，機櫃檢視才看得出「這櫃還有位子」而不是每格都填滿。
      if (hash(`${cabinet.id}:empty:${slot}`) < 0.12) continue;
      serial++;
      const deviceId = `DEV-${pad(serial, 6)}`;
      const modelCode = pick(allowed, `${deviceId}:model`);
      const status = rollStatus(`${deviceId}:status`);
      devices.push({
        deviceId,
        name: `${MODEL_BY_CODE.get(modelCode)!.displayName} ${pad(serial, 6)}`,
        modelCode,
        cabinetId: cabinet.id,
        slot,
        status,
        lastSeenAt:
          status === 'UNKNOWN'
            ? null
            : new Date(Date.now() - Math.floor(hash(`${deviceId}:seen`) * 600_000)).toISOString(),
      });
    }
  }
}

const deviceById = new Map(devices.map((d) => [d.deviceId, d]));
const devicesByCabinet = new Map<string, Device[]>();
for (const d of devices) {
  const list = devicesByCabinet.get(d.cabinetId);
  if (list) list.push(d);
  else devicesByCabinet.set(d.cabinetId, [d]);
}

// ------------------------------------------------------- 告警

const SEVERITIES: AlarmSeverity[] = ['INFO', 'WARNING', 'CRITICAL'];

const ALARM_TEXT: Record<string, string> = {
  temperature: '溫度超出門檻',
  humidity: '濕度超出門檻',
  voltage: '電壓異常',
  current: '電流過載',
  pm25: 'PM2.5 濃度過高',
  co2: 'CO₂ 濃度過高',
  fanRpm: '風扇轉速異常',
  doorOpen: '機櫃門未關閉',
};

let alarmSeq = 1000;

function makeAlarm(
  device: Device,
  state: 'FIRING' | 'RESOLVED',
  ageMs: number,
  severityOverride?: AlarmSeverity,
): Alarm {
  const model = MODEL_BY_CODE.get(device.modelCode)!;
  const metric = pick(model.metrics, `${device.deviceId}:alarmMetric:${ageMs}`);
  const severity = severityOverride ?? pick(SEVERITIES, `${device.deviceId}:sev:${ageMs}`);
  const firedAt = Date.now() - ageMs;
  const threshold = metric.minValue + (metric.maxValue - metric.minValue) * 0.8;
  return {
    alarmId: alarmSeq++,
    deviceId: device.deviceId,
    deviceName: device.name,
    cabinetId: device.cabinetId,
    metric: metric.key,
    severity,
    state,
    message: `${ALARM_TEXT[metric.key] ?? '指標超出門檻'}（${metric.key}）`,
    value: Number((threshold * (1.02 + hash(`${device.deviceId}:v:${ageMs}`) * 0.15)).toFixed(2)),
    threshold: Number(threshold.toFixed(2)),
    firedAt: new Date(firedAt).toISOString(),
    resolvedAt: state === 'RESOLVED' ? new Date(firedAt + 900_000).toISOString() : null,
  };
}

/** 非 ONLINE 的裝置比較可能有告警，讓機櫃檢視的顏色與告警列表對得起來。 */
const alarms: Alarm[] = [];
for (const device of devices) {
  const bias = device.status === 'ONLINE' ? 0.004 : 0.35;
  if (hash(`${device.deviceId}:firing`) < bias) {
    alarms.push(makeAlarm(device, 'FIRING', Math.floor(hash(`${device.deviceId}:age`) * 7_200_000)));
  }
  if (hash(`${device.deviceId}:resolved`) < 0.01) {
    alarms.push(
      makeAlarm(device, 'RESOLVED', 86_400_000 + Math.floor(hash(`${device.deviceId}:age2`) * 604_800_000)),
    );
  }
}

const SEVERITY_ORDER: Record<AlarmSeverity, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 };

// ------------------------------------------------------- 告警規則

const alarmRules: AlarmRule[] = [
  {
    id: 1,
    name: '機房高溫',
    modelCode: 'TH-100',
    metric: 'temperature',
    comparison: 'GT',
    threshold: 35,
    durationSeconds: 60,
    severity: 'CRITICAL',
    enabled: true,
  },
  {
    id: 2,
    name: '濕度過高',
    modelCode: 'TH-100',
    metric: 'humidity',
    comparison: 'GT',
    threshold: 75,
    durationSeconds: 300,
    severity: 'WARNING',
    enabled: true,
  },
  {
    id: 3,
    name: '電壓偏低',
    modelCode: 'PWR-400',
    metric: 'voltage',
    comparison: 'LT',
    threshold: 200,
    durationSeconds: 30,
    severity: 'CRITICAL',
    enabled: true,
  },
  {
    id: 4,
    name: '電流過載',
    modelCode: 'PWR-400',
    metric: 'current',
    comparison: 'GTE',
    threshold: 50,
    durationSeconds: 15,
    severity: 'WARNING',
    enabled: true,
  },
  {
    id: 5,
    name: '機櫃門長時間開啟',
    modelCode: 'CAB-CTRL-900',
    metric: 'doorOpen',
    comparison: 'GTE',
    threshold: 1,
    durationSeconds: 600,
    severity: 'INFO',
    enabled: false,
  },
  {
    id: 6,
    name: 'PM2.5 超標',
    modelCode: 'AIR-220',
    metric: 'pm25',
    comparison: 'GT',
    threshold: 75,
    durationSeconds: 120,
    severity: 'INFO',
    enabled: true,
  },
  {
    id: 7,
    name: '電壓超出安全區間',
    modelCode: 'PWR-400',
    metric: 'voltage',
    comparison: 'OUT_OF_RANGE',
    threshold: 200,
    secondaryValue: 250,
    durationSeconds: 30,
    severity: 'WARNING',
    enabled: true,
  },
];

// ------------------------------------------------------- 遙測

/** 指標的基準值與波動幅度，讓假資料看起來像真的讀數而不是純亂數。 */
const METRIC_SHAPE: Record<string, { base: number; swing: number; noise: number }> = {
  temperature: { base: 26, swing: 5, noise: 0.6 },
  humidity: { base: 58, swing: 12, noise: 1.5 },
  voltage: { base: 222, swing: 6, noise: 1.2 },
  current: { base: 24, swing: 9, noise: 1.8 },
  powerFactor: { base: 0.94, swing: 0.04, noise: 0.01 },
  pm25: { base: 32, swing: 18, noise: 4 },
  co2: { base: 620, swing: 180, noise: 30 },
  fanRpm: { base: 3200, swing: 700, noise: 120 },
  doorOpen: { base: 0, swing: 0, noise: 0 },
};

function shapeOf(metric: string) {
  return METRIC_SHAPE[metric] ?? { base: 50, swing: 10, noise: 2 };
}

/** 指定時刻的「真值」：日週期 ＋ 小幅雜訊。 */
function valueAt(deviceId: string, metric: string, tMs: number): number {
  const s = shapeOf(metric);
  if (metric === 'doorOpen') return hash(`${deviceId}:door:${Math.floor(tMs / 3_600_000)}`) < 0.05 ? 1 : 0;
  const phase = hash(`${deviceId}:phase`) * Math.PI * 2;
  const daily = Math.sin((tMs / 86_400_000) * Math.PI * 2 + phase);
  const jitter = (hash(`${deviceId}:${metric}:${Math.floor(tMs / 1000)}`) - 0.5) * 2;
  const drift = (hash(`${deviceId}:${metric}:offset`) - 0.5) * s.swing * 0.5;
  return s.base + drift + daily * s.swing + jitter * s.noise;
}

const HOUR = 3_600_000;
const DAY = 86_400_000;

/**
 * 契約規定層級由後端依時間跨度決定、呼叫端不能指定，所以這裡照 README 的路由表複製一份：
 * ≤6 小時讀原始、≤30 天讀 1 分鐘聚合、其餘讀 1 小時聚合。
 */
function resolveLayer(spanMs: number): { resolution: Resolution; bucketMs: number } {
  if (spanMs <= 6 * HOUR) return { resolution: 'raw', bucketMs: 5000 };
  if (spanMs <= 30 * DAY) return { resolution: '1m', bucketMs: 60_000 };
  return { resolution: '1h', bucketMs: HOUR };
}

async function queryTelemetry(query: TelemetryQuery): Promise<TelemetrySeries> {
  await delay(120);
  const from = Date.parse(query.from);
  const to = Date.parse(query.to);
  const maxPoints = query.maxPoints ?? 500;
  if (maxPoints > 5000) {
    // 與契約一致：不安靜截斷，直接拒絕。安靜截斷的圖表比沒有圖表更危險。
    throw new Error('maxPoints 超過上限 5000，請縮小時間範圍或加大時間桶');
  }

  const span = Math.max(1, to - from);
  const layer = resolveLayer(span);
  // 實際桶寬會被 maxPoints 再放大一次，模擬後端「掃描量有上限」的行為。
  const bucketMs = Math.max(layer.bucketMs, Math.ceil(span / maxPoints));
  const count = Math.max(1, Math.min(maxPoints, Math.floor(span / bucketMs)));

  const points: TelemetryPoint[] = [];
  for (let i = 0; i < count; i++) {
    const t = from + i * bucketMs;
    const centre = valueAt(query.deviceId, query.metric, t + bucketMs / 2);
    if (layer.resolution === 'raw') {
      points.push({ t: new Date(t).toISOString(), avg: round(centre) });
      continue;
    }
    // 聚合層一定要給 min/max：只給 avg 的話尖峰會被平均吃掉，那正是監控最常見的誤導。
    const samplesPerBucket = bucketMs / 5000;
    const spread =
      shapeOf(query.metric).noise * (1.5 + hash(`${query.deviceId}:${query.metric}:sp:${i}`) * 2.5);
    const spike = hash(`${query.deviceId}:${query.metric}:spike:${i}`) < 0.03 ? spread * 4 : 0;
    points.push({
      t: new Date(t).toISOString(),
      avg: round(centre),
      min: round(centre - spread),
      max: round(centre + spread + spike),
      count: Math.round(samplesPerBucket),
    });
  }

  return {
    deviceId: query.deviceId,
    metric: query.metric,
    resolution: layer.resolution,
    points,
  };
}

function round(v: number): number {
  return Number(v.toFixed(3));
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ------------------------------------------------------- 監控樹

/** 樹的節點只存扁平結構與 parentId；children 與 rollup 每次查詢時從子樹重算，行為與後端一致。 */
interface FlatNode {
  id: number;
  kind: NodeKind;
  name: string;
  parentId: number | null;
  sortOrder: number;
  deviceId?: string;
}

const treeNodes: FlatNode[] = [];
let nextNodeId = 1;

const SORT_GAP = 1000;

/** 描述用的樹形規格：sensors 可以無限嵌套，devices 是這一層底下要掛的裝置名稱。 */
interface TreeSpec {
  name: string;
  sensors?: TreeSpec[];
  devices?: string[];
}

const TREE_SPEC: TreeSpec[] = [
  {
    name: '1 號變電站',
    sensors: [
      {
        name: 'A 相',
        sensors: [
          {
            name: '一次側',
            sensors: [
              {
                name: '溫度監測',
                sensors: [
                  { name: '測點 1', devices: ['繞組溫度計', '油溫計'] },
                  { name: '測點 2', devices: ['繞組溫度計'] },
                ],
              },
              { name: '電流量測', devices: ['電流計', '零序電流計'] },
            ],
          },
          { name: '二次側', devices: ['電壓計', '電流計', '功率因數計'] },
        ],
      },
      {
        name: 'B 相',
        sensors: [
          { name: '一次側', devices: ['電流計', '電壓計'] },
          { name: '二次側', devices: ['電流計', '電壓計'] },
        ],
      },
      {
        name: 'C 相',
        sensors: [{ name: '一次側', devices: ['電流計', '電壓計', '溫度計'] }],
      },
      { name: '環境', devices: ['室溫計', '濕度計', '煙霧偵測器'] },
    ],
  },
  {
    name: '2 號變電站',
    sensors: [
      {
        name: '主變壓器',
        sensors: [
          { name: '高壓側', devices: ['電壓計', '電流計', '避雷器監測'] },
          { name: '低壓側', devices: ['電壓計', '電流計'] },
          {
            name: '冷卻',
            sensors: [
              { name: '風扇群 1', devices: ['轉速計 1', '轉速計 2'] },
              { name: '風扇群 2', devices: ['轉速計 1', '轉速計 2', '轉速計 3'] },
            ],
          },
        ],
      },
      { name: '配電盤', devices: ['總電表', '分路電表 1', '分路電表 2', '分路電表 3'] },
      { name: '環境', devices: ['室溫計', '濕度計'] },
    ],
  },
  {
    name: '冷卻水塔系統',
    sensors: [
      {
        name: '1 號塔',
        sensors: [
          { name: '進水', devices: ['流量計', '水溫計', '壓力計'] },
          { name: '出水', devices: ['流量計', '水溫計'] },
          { name: '風機', devices: ['轉速計', '振動計'] },
        ],
      },
      {
        name: '2 號塔',
        sensors: [
          { name: '進水', devices: ['流量計', '水溫計'] },
          { name: '出水', devices: ['流量計', '水溫計'] },
          { name: '風機', devices: ['轉速計', '振動計', '軸溫計'] },
        ],
      },
      { name: '水質', devices: ['pH 計', '導電度計'] },
    ],
  },
];

/** 從一萬台裝置裡跳著挑，讓樹上的裝置散在不同機櫃，而不是全擠在前幾櫃。 */
let treeDeviceCursor = 0;
function nextTreeDevice(): Device {
  treeDeviceCursor += 137;
  return devices[treeDeviceCursor % devices.length];
}

function addFlatNode(kind: NodeKind, name: string, parentId: number | null, deviceId?: string): FlatNode {
  const last = treeNodes
    .filter((n) => n.parentId === parentId)
    .reduce((max, n) => Math.max(max, n.sortOrder), 0);
  const node: FlatNode = { id: nextNodeId++, kind, name, parentId, sortOrder: last + SORT_GAP, deviceId };
  treeNodes.push(node);
  return node;
}

function buildSpec(spec: TreeSpec, parentId: number | null, kind: NodeKind): void {
  const node = addFlatNode(kind, spec.name, parentId);
  for (const child of spec.sensors ?? []) buildSpec(child, node.id, 'SENSOR');
  for (const deviceName of spec.devices ?? []) {
    addFlatNode('DEVICE', deviceName, node.id, nextTreeDevice().deviceId);
  }
}
for (const spec of TREE_SPEC) buildSpec(spec, null, 'EQUIPMENT');

function descendantIds(rootId: number): number[] {
  const out: number[] = [];
  const stack = [rootId];
  while (stack.length > 0) {
    const id = stack.pop()!;
    out.push(id);
    for (const n of treeNodes) if (n.parentId === id) stack.push(n.id);
  }
  return out;
}

/** 從根到該節點的路徑，由上到下（含節點本身）。 */
function pathTo(nodeId: number): FlatNode[] {
  const path: FlatNode[] = [];
  let cur = treeNodes.find((n) => n.id === nodeId);
  while (cur) {
    path.unshift(cur);
    const parentId = cur.parentId;
    cur = parentId === null ? undefined : treeNodes.find((n) => n.id === parentId);
  }
  return path;
}

const nodeByDeviceId = new Map<string, FlatNode>();
for (const n of treeNodes) if (n.deviceId) nodeByDeviceId.set(n.deviceId, n);

/** 推播用：裝置不在樹上就回空陣列，前端據此略過。 */
function ancestorIdsOfDevice(deviceId: string): number[] {
  const node = nodeByDeviceId.get(deviceId);
  return node ? pathTo(node.id).map((n) => n.id) : [];
}

/**
 * 讓樹上有代表性的告警分布：1 號變電站最深那條鏈是 CRITICAL、2 號變電站有 WARNING 與 INFO，
 * 冷卻水塔系統保持安靜——它是推播示範「告警上浮」時要點亮的那條分支。
 */
{
  const byPath = (path: string[]): FlatNode | undefined => {
    let parentId: number | null = null;
    let found: FlatNode | undefined;
    for (const name of path) {
      found = treeNodes.find((n) => n.parentId === parentId && n.name === name);
      if (!found) return undefined;
      parentId = found.id;
    }
    return found;
  };
  const quietIds = new Set(descendantIds(byPath(['冷卻水塔系統'])!.id));
  for (const n of treeNodes) {
    if (!quietIds.has(n.id) || !n.deviceId) continue;
    for (const a of alarms) if (a.deviceId === n.deviceId && a.state === 'FIRING') a.state = 'RESOLVED';
  }
  const force: Array<[string[], AlarmSeverity]> = [
    [['1 號變電站', 'A 相', '一次側', '溫度監測', '測點 1', '繞組溫度計'], 'CRITICAL'],
    [['1 號變電站', 'A 相', '一次側', '溫度監測', '測點 1', '油溫計'], 'WARNING'],
    [['1 號變電站', 'A 相', '二次側', '功率因數計'], 'WARNING'],
    [['1 號變電站', 'C 相', '一次側', '溫度計'], 'INFO'],
    [['2 號變電站', '主變壓器', '冷卻', '風扇群 2', '轉速計 3'], 'WARNING'],
    [['2 號變電站', '配電盤', '分路電表 2'], 'INFO'],
  ];
  for (const [path, severity] of force) {
    const node = byPath(path);
    const device = node?.deviceId ? deviceById.get(node.deviceId) : undefined;
    if (!device) continue;
    if (!alarms.some((a) => a.deviceId === device.deviceId && a.state === 'FIRING' && a.severity === severity)) {
      alarms.push(
        makeAlarm(device, 'FIRING', Math.floor(hash(`${device.deviceId}:treeAge`) * 3_600_000), severity),
      );
    }
  }
}

/** 契約：所有回傳子節點的地方一律以 (sortOrder, id) 排序，id 是決定性的平手判斷。 */
function sortedChildren(parentId: number | null): FlatNode[] {
  return treeNodes
    .filter((n) => n.parentId === parentId)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
}

const ROLLUP_RANK: Record<AlarmSeverity, number> = { INFO: 1, WARNING: 2, CRITICAL: 3 };

function firingIndex(): Map<string, AlarmSeverity[]> {
  const map = new Map<string, AlarmSeverity[]>();
  for (const a of alarms) {
    if (a.state !== 'FIRING') continue;
    const list = map.get(a.deviceId);
    if (list) list.push(a.severity);
    else map.set(a.deviceId, [a.severity]);
  }
  return map;
}

/** rollup 由子樹重新計算而不是遞增遞減，解除一則告警後父節點不會出現錯誤的綠燈。 */
function materialize(node: FlatNode, firingByDevice: Map<string, AlarmSeverity[]>): TreeNode {
  const children = sortedChildren(node.id).map((c) => materialize(c, firingByDevice));
  let firing = 0;
  let severity: AlarmSeverity | null = null;
  const bump = (sev: AlarmSeverity) => {
    if (!severity || ROLLUP_RANK[sev] > ROLLUP_RANK[severity]) severity = sev;
  };
  if (node.kind === 'DEVICE' && node.deviceId) {
    for (const sev of firingByDevice.get(node.deviceId) ?? []) {
      firing++;
      bump(sev);
    }
  }
  for (const c of children) {
    firing += c.rollup.firing;
    if (c.rollup.severity) bump(c.rollup.severity);
  }
  return {
    id: node.id,
    kind: node.kind,
    name: node.name,
    sortOrder: node.sortOrder,
    ...(node.deviceId ? { deviceId: node.deviceId } : {}),
    rollup: { severity, firing },
    children,
  };
}

// ------------------------------------------------------- 假的即時推播

/**
 * 模擬後端的推播消費端。刻意照契約做了兩件事：
 * 一、不訂閱就什麼都不推；二、遙測每台裝置每秒最多一則。
 * 前端因此在 mock 模式下就會遇到與真實環境相同的節奏。
 */
class MockLiveSocket implements LiveSocket {
  private subscribed: string[] = [];
  private timer: number | null = null;
  private closed = false;
  private ticks = 0;

  constructor(private readonly handlers: LiveSocketHandlers) {
    // 非同步觸發 onOpen，行為與真實 WebSocket 一致（建構當下還沒連上）。
    setTimeout(() => {
      if (this.closed) return;
      this.handlers.onOpen();
      this.timer = window.setInterval(() => this.tick(), 1000);
    }, 250);
  }

  private tick(): void {
    const ids = this.subscribed;
    if (ids.length === 0) return;
    this.ticks++;

    // 一秒最多推 400 台，模擬後端的推播上限，也避免 mock 自己把瀏覽器拖垮。
    const budget = Math.min(ids.length, 400);
    const now = Date.now();
    for (let i = 0; i < budget; i++) {
      const deviceId = ids[Math.floor(Math.random() * ids.length)];
      const device = deviceById.get(deviceId);
      if (!device || device.status === 'OFFLINE' || device.status === 'UNKNOWN') continue;
      const model = MODEL_BY_CODE.get(device.modelCode)!;
      const metrics: Record<string, number> = {};
      for (const m of model.metrics) metrics[m.key] = round(valueAt(deviceId, m.key, now));
      this.handlers.onEvent({ type: 'telemetry', deviceId, metrics, ts: now });
    }

    // 狀態與告警是低頻但每則都重要，維持很低的發生率，否則機櫃格會一直閃。
    if (Math.random() < 0.05) {
      const deviceId = ids[Math.floor(Math.random() * ids.length)];
      const device = deviceById.get(deviceId);
      if (device) {
        const next: DeviceStatus =
          device.status === 'ONLINE' ? (Math.random() < 0.5 ? 'DEGRADED' : 'OFFLINE') : 'ONLINE';
        device.status = next;
        this.handlers.onEvent({ type: 'status', deviceId, state: next, ts: now });
      }
    }

    if (Math.random() < 0.03) {
      const deviceId = ids[Math.floor(Math.random() * ids.length)];
      const device = deviceById.get(deviceId);
      if (device) this.fireAlarm(device, undefined, now);
    }

    // 監控樹在看的時候，每 12 秒挑一台「所在 Equipment 目前整棵安靜」的樹上裝置響一則，
    // 讓告警從葉節點一路亮到根的上浮效果看得到，不必等隨機命中。
    if (this.ticks % 12 === 0) {
      const firing = firingIndex();
      const quiet = ids.filter((id) => {
        const node = nodeByDeviceId.get(id);
        if (!node) return false;
        return !descendantIds(pathTo(node.id)[0].id).some((nid) => {
          const d = treeNodes.find((n) => n.id === nid)?.deviceId;
          return d !== undefined && firing.has(d);
        });
      });
      const pool = quiet.length > 0 ? quiet : ids.filter((id) => nodeByDeviceId.has(id));
      if (pool.length > 0) {
        const device = deviceById.get(pool[Math.floor(Math.random() * pool.length)]);
        if (device) this.fireAlarm(device, Math.random() < 0.5 ? 'CRITICAL' : 'WARNING', now);
      }
    }
  }

  private fireAlarm(device: Device, severity: AlarmSeverity | undefined, now: number): void {
    const alarm = makeAlarm(device, 'FIRING', 0, severity);
    alarms.unshift(alarm);
    this.handlers.onEvent({
      type: 'alarm',
      alarmId: alarm.alarmId,
      deviceId: device.deviceId,
      severity: alarm.severity,
      state: 'FIRING',
      ts: now,
      ancestorIds: ancestorIdsOfDevice(device.deviceId),
    });
  }

  send(message: SubscribeMessage): void {
    this.subscribed = message.deviceIds;
  }

  close(): void {
    this.closed = true;
    if (this.timer !== null) window.clearInterval(this.timer);
  }
}

// ------------------------------------------------------- API 實作

let nextRuleId = alarmRules.length + 1;

export const mockApi: IotApi = {
  async getOverview(): Promise<Overview> {
    await delay(80);
    const deviceCounts: Record<DeviceStatus, number> = {
      ONLINE: 0,
      DEGRADED: 0,
      OFFLINE: 0,
      UNKNOWN: 0,
    };
    for (const d of devices) deviceCounts[d.status]++;
    return {
      deviceCounts,
      unresolvedAlarms: alarms.filter((a) => a.state === 'FIRING').length,
      // 讓數字會小幅跳動，比較像真的在量測而不是寫死的常數。
      ingestRatePerSecond: Math.round(48_000 + Math.random() * 4000),
      generatedAt: new Date().toISOString(),
    };
  },

  async listModels() {
    await delay(60);
    return MODELS.map((m) => ({ ...m, metrics: [...m.metrics] }));
  },

  async createModel(model) {
    await delay(80);
    const created = model as DeviceModel;
    MODELS.push(created);
    MODEL_BY_CODE.set(created.code, created);
    return created;
  },

  async listCabinets() {
    await delay(60);
    return cabinets.map((c) => ({ ...c }));
  },

  async listDevices(query: DeviceQuery = {}) {
    await delay(60);
    const source = query.cabinetId ? (devicesByCabinet.get(query.cabinetId) ?? []) : devices;
    return source
      .filter((d) => !query.status || d.status === query.status)
      .filter((d) => !query.modelCode || d.modelCode === query.modelCode)
      .map((d) => ({ ...d }));
  },

  async getDevice(deviceId: string) {
    await delay(40);
    const d = deviceById.get(deviceId);
    return d ? { ...d } : null;
  },

  async listAlarms(params = {}) {
    await delay(70);
    return alarms
      .filter((a) => !params.state || a.state === params.state)
      .filter((a) => !params.deviceId || a.deviceId === params.deviceId)
      .sort(
        (a, b) =>
          SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] ||
          Date.parse(b.firedAt) - Date.parse(a.firedAt),
      )
      .slice(0, params.limit ?? 200)
      .map((a) => ({ ...a }));
  },

  async listAlarmRules() {
    await delay(60);
    return alarmRules.map((r) => ({ ...r }));
  },

  async createAlarmRule(rule) {
    await delay(120);
    const created: AlarmRule = { id: nextRuleId++, ...rule };
    alarmRules.push(created);
    return created;
  },

  queryTelemetry,

  async getTree() {
    await delay(90);
    const firing = firingIndex();
    return sortedChildren(null).map((root) => materialize(root, firing));
  },

  async getSubtree(nodeId: number) {
    await delay(70);
    const node = treeNodes.find((n) => n.id === nodeId);
    if (!node) throw new Error(`找不到節點 ${nodeId}`);
    return materialize(node, firingIndex());
  },

  async getAncestors(nodeId: number) {
    await delay(40);
    const firing = firingIndex();
    return pathTo(nodeId).map((n): TreePathNode => {
      const { children: _children, ...rest } = materialize(n, firing);
      return rest;
    });
  },

  async createNode(request: CreateNodeRequest) {
    await delay(100);
    const parent = request.parentId === null ? null : treeNodes.find((n) => n.id === request.parentId);
    if (request.parentId !== null && !parent) throw new Error('400：父節點不存在');
    // 與後端相同的結構規則，違反直接拒絕，前端不該有機會做出不合法的樹。
    if (request.kind === 'EQUIPMENT' && parent) throw new Error('400：Equipment 只能在根');
    if (request.kind !== 'EQUIPMENT' && !parent) throw new Error('400：Sensor／Device 必須有父節點');
    if (parent?.kind === 'DEVICE') throw new Error('400：Device 不能有子節點');
    if (request.kind === 'DEVICE' && parent?.kind !== 'SENSOR') {
      throw new Error('400：Device 的父節點只能是 Sensor');
    }
    const node = addFlatNode(request.kind, request.name, request.parentId, request.deviceId);
    if (request.sortOrder !== undefined) node.sortOrder = request.sortOrder;
    if (node.deviceId) nodeByDeviceId.set(node.deviceId, node);
    return materialize(node, firingIndex());
  },

  async reorderNode(nodeId: number, sortOrder: number) {
    await delay(80);
    const node = treeNodes.find((n) => n.id === nodeId);
    if (!node) throw new Error(`找不到節點 ${nodeId}`);
    node.sortOrder = sortOrder;
    return materialize(node, firingIndex());
  },

  connectLive: (handlers: LiveSocketHandlers) => new MockLiveSocket(handlers),
};
