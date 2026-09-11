import { clearSession, getToken } from '../auth/session';
import type {
  Alarm,
  AlarmAction,
  AlarmRule,
  AlarmState,
  AppUser,
  AuditEntry,
  Cabinet,
  CreateAlarmRuleRequest,
  CreateCabinetRequest,
  CreateNodeRequest,
  CreateUserRequest,
  Device,
  DeviceModel,
  DeviceQuery,
  IotApi,
  LiveEvent,
  LiveSocket,
  LiveSocketHandlers,
  LoginRequest,
  LoginResponse,
  Overview,
  RegisterDeviceRequest,
  ReplaySnapshot,
  ReplayTimeline,
  SubscribeMessage,
  TelemetryQuery,
  TelemetrySeries,
  TreeNode,
  TreePathNode,
  UpdateAlarmRuleRequest,
} from './types';

/** 留空時走 vite dev proxy 的相對路徑，免得開發與正式各記一組網址。 */
const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';

const WS_URL =
  import.meta.env.VITE_WS_URL ||
  `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/live`;

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/** 後端的錯誤一律是 {"message": "..."}；解不出來就用原文，總比只給狀態碼好。 */
export function messageOf(text: string, fallback: string): string {
  try {
    const parsed = JSON.parse(text) as { message?: string };
    if (parsed && typeof parsed.message === 'string') return parsed.message;
  } catch {
    // 不是 JSON
  }
  return text || fallback;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    // 401 代表 token 失效：清掉登入狀態，RequireAuth 會把人送回登入頁
    if (res.status === 401 && !path.startsWith('/auth/login')) clearSession();
    throw new ApiError(res.status, messageOf(detail, `${res.status} ${res.statusText}`));
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
    // 瀏覽器的 WebSocket 不能設 Authorization 標頭，token 走查詢參數；沒登入就不連
    const token = getToken();
    if (!token) {
      this.handlers.onClose();
      return;
    }
    const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}`);
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
  login: (body: LoginRequest) =>
    request<LoginResponse>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),

  listUsers: () => request<AppUser[]>('/users'),

  createUser: (body: CreateUserRequest) =>
    request<AppUser>('/users', { method: 'POST', body: JSON.stringify(body) }),

  listAudit: (params = {}) => request<AuditEntry[]>(`/audit${qs({ limit: params.limit, actor: params.actor })}`),

  getOverview: () => request<Overview>('/overview'),

  listModels: () => request<DeviceModel[]>('/models'),

  createModel: (model) =>
    request<DeviceModel>('/models', { method: 'POST', body: JSON.stringify(model) }),

  listCabinets: () => request<Cabinet[]>('/cabinets'),

  createCabinet: (body: CreateCabinetRequest) =>
    request<Cabinet>('/cabinets', { method: 'POST', body: JSON.stringify(body) }),

  listDevices: (query: DeviceQuery = {}) =>
    request<Device[]>(
      `/devices${qs({ status: query.status, cabinetId: query.cabinetId, modelCode: query.modelCode })}`,
    ),

  /** 單筆端點：404 視為不存在，其他錯誤照常拋出 */
  async getDevice(deviceId: string) {
    try {
      return await request<Device>(`/devices/${encodeURIComponent(deviceId)}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  },

  registerDevice: (body: RegisterDeviceRequest) =>
    request<Device>('/devices', { method: 'POST', body: JSON.stringify(body) }),

  listAlarms: (params = {}) =>
    request<Alarm[]>(
      `/alarms${qs({ state: params.state, deviceId: params.deviceId, limit: params.limit })}`,
    ),

  acknowledgeAlarm: (alarmId: number) =>
    request<AlarmAction>(`/alarms/${alarmId}/ack`, { method: 'POST' }),

  resolveAlarm: (alarmId: number) =>
    request<AlarmAction>(`/alarms/${alarmId}/resolve`, { method: 'POST' }),

  listAlarmRules: () => request<AlarmRule[]>('/alarm-rules'),

  createAlarmRule: (rule: CreateAlarmRuleRequest) =>
    request<AlarmRule>('/alarm-rules', { method: 'POST', body: JSON.stringify(rule) }),

  updateAlarmRule: (id: number, rule: UpdateAlarmRuleRequest) =>
    request<AlarmRule>(`/alarm-rules/${id}`, { method: 'PATCH', body: JSON.stringify(rule) }),

  /** 204 沒有內容，不能走 request()——它會去 res.json() 然後炸掉 */
  async deleteAlarmRule(id: number) {
    const res = await fetch(`${API_BASE}/alarm-rules/${id}`, {
      method: 'DELETE',
      headers: { ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}) },
    });
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      throw new ApiError(res.status, messageOf(detail, `${res.status} ${res.statusText}`));
    }
  },

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

  renumberNodes: (parentId: number | null) =>
    request<{ parentId: number | null; renumbered: number }>('/tree/renumber', {
      method: 'POST',
      body: JSON.stringify({ parentId }),
    }),

  getReplay: (cabinetId: string, at: string) => request<ReplaySnapshot>(`/replay${qs({ cabinetId, at })}`),

  getReplayTimeline: (cabinetId: string, from: string, to: string) =>
    request<ReplayTimeline>(`/replay/timeline${qs({ cabinetId, from, to })}`),

  connectLive: (handlers: LiveSocketHandlers) => new ReconnectingLiveSocket(handlers),
};

export type { AlarmState };
