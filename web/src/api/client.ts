import type {
  Alarm,
  AlarmRule,
  AlarmState,
  Cabinet,
  CreateAlarmRuleRequest,
  CreateNodeRequest,
  Device,
  DeviceModel,
  DeviceQuery,
  IotApi,
  LiveEvent,
  LiveSocket,
  LiveSocketHandlers,
  Overview,
  SubscribeMessage,
  TelemetryQuery,
  TelemetrySeries,
  TreeNode,
  TreePathNode,
} from './types';

/** 留空時走 vite dev proxy 的相對路徑，免得開發與正式各記一組網址。 */
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1';

const WS_URL =
  import.meta.env.VITE_WS_URL ??
  `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/live`;

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!res.ok) {
    // 契約規定 maxPoints 超限會回 400 並說明原因，把訊息帶出來使用者才知道要縮小範圍。
    const detail = await res.text().catch(() => '');
    throw new ApiError(res.status, detail || `${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

function qs(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

/**
 * 真實後端的 WebSocket，帶指數退避重連。
 *
 * 重連後不必記住舊的訂閱：LiveProvider 在 onOpen 會重送目前可見裝置的完整集合，
 * 這樣「連線狀態」與「訂閱狀態」只有一個真相來源。
 */
class ReconnectingLiveSocket implements LiveSocket {
  private ws: WebSocket | null = null;
  private timer: number | null = null;
  private attempt = 0;
  private closed = false;
  private pending: SubscribeMessage | null = null;

  constructor(private readonly handlers: LiveSocketHandlers) {
    this.open();
  }

  private open(): void {
    if (this.closed) return;
    const ws = new WebSocket(WS_URL);
    this.ws = ws;

    ws.onopen = () => {
      this.attempt = 0;
      if (this.pending) ws.send(JSON.stringify(this.pending));
      this.handlers.onOpen();
    };

    ws.onmessage = (ev) => {
      try {
        this.handlers.onEvent(JSON.parse(ev.data as string) as LiveEvent);
      } catch {
        // 單一則格式錯誤不該讓整條連線掛掉，丟掉繼續收下一則。
      }
    };

    ws.onclose = () => {
      this.ws = null;
      this.handlers.onClose();
      this.scheduleReconnect();
    };

    // onerror 之後 onclose 一定會來，重連邏輯只放在 onclose 一處避免重複排程。
    ws.onerror = () => ws.close();
  }

  private scheduleReconnect(): void {
    if (this.closed || this.timer !== null) return;
    // 上限 15 秒：後端重啟通常在這個量級內，退避太久會讓大屏空白很久。
    const delay = Math.min(15000, 500 * 2 ** this.attempt++);
    this.timer = window.setTimeout(() => {
      this.timer = null;
      this.open();
    }, delay);
  }

  send(message: SubscribeMessage): void {
    this.pending = message;
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(message));
  }

  close(): void {
    this.closed = true;
    if (this.timer !== null) window.clearTimeout(this.timer);
    this.ws?.close();
  }
}

export const httpApi: IotApi = {
  getOverview: () => request<Overview>('/overview'),

  listModels: () => request<DeviceModel[]>('/models'),

  createModel: (model) =>
    request<DeviceModel>('/models', { method: 'POST', body: JSON.stringify(model) }),

  listCabinets: () => request<Cabinet[]>('/cabinets'),

  listDevices: (query: DeviceQuery = {}) =>
    request<Device[]>(
      `/devices${qs({ status: query.status, cabinetId: query.cabinetId, modelCode: query.modelCode })}`,
    ),

  /**
   * 契約沒有 /devices/{id}，所以先用清單再過濾。
   * 一萬台裝置這樣做並不划算，後端補上單筆端點後這裡要跟著改。
   */
  async getDevice(deviceId: string) {
    const all = await httpApi.listDevices();
    return all.find((d) => d.deviceId === deviceId) ?? null;
  },

  listAlarms: (params = {}) =>
    request<Alarm[]>(
      // 契約只列了 state，deviceId 是裝置詳情的告警歷史所需，後端要一併支援。
      `/alarms${qs({ state: params.state, deviceId: params.deviceId, limit: params.limit })}`,
    ),

  listAlarmRules: () => request<AlarmRule[]>('/alarm-rules'),

  createAlarmRule: (rule: CreateAlarmRuleRequest) =>
    request<AlarmRule>('/alarm-rules', { method: 'POST', body: JSON.stringify(rule) }),

  queryTelemetry: (query: TelemetryQuery) =>
    request<TelemetrySeries>(
      `/telemetry${qs({
        deviceId: query.deviceId,
        metric: query.metric,
        from: query.from,
        to: query.to,
        maxPoints: query.maxPoints,
      })}`,
    ),

  // 監控樹：子節點順序由後端以 (sortOrder, id) 保證，這裡原封不動回傳，不做任何排序。
  getTree: () => request<TreeNode[]>('/tree'),

  getSubtree: (nodeId: number) => request<TreeNode>(`/tree/${nodeId}`),

  getAncestors: (nodeId: number) => request<TreePathNode[]>(`/tree/${nodeId}/ancestors`),

  createNode: (body: CreateNodeRequest) =>
    request<TreeNode>('/tree/nodes', { method: 'POST', body: JSON.stringify(body) }),

  reorderNode: (nodeId: number, sortOrder: number) =>
    request<TreeNode>(`/tree/nodes/${nodeId}/order`, {
      method: 'PATCH',
      body: JSON.stringify({ sortOrder }),
    }),

  connectLive: (handlers: LiveSocketHandlers) => new ReconnectingLiveSocket(handlers),
};

export type { AlarmState };
