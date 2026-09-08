import type { AlarmSeverity, DeviceStatus, Resolution } from '../api/types';

export const STATUS_LABEL: Record<DeviceStatus, string> = {
  ONLINE: '正常',
  DEGRADED: '降級',
  OFFLINE: '離線',
  UNKNOWN: '未知',
};

export const SEVERITY_LABEL: Record<AlarmSeverity, string> = {
  CRITICAL: '嚴重',
  WARNING: '警告',
  INFO: '提示',
};

/** 排序用。數字小的排前面，與後端告警列表的優先順序一致。 */
export const SEVERITY_ORDER: Record<AlarmSeverity, number> = {
  CRITICAL: 0,
  WARNING: 1,
  INFO: 2,
};

/** 使用者要一眼分辨自己看的是原始值還是聚合值，所以標籤寫得直白。 */
export const RESOLUTION_LABEL: Record<Resolution, string> = {
  raw: '原始值',
  '1m': '1 分鐘聚合',
  '1h': '1 小時聚合',
};

export const RESOLUTION_HINT: Record<Resolution, string> = {
  raw: '每個資料點都是裝置回報的原始讀數，沒有經過平均。',
  '1m': '每點代表一分鐘，帶狀區間是該分鐘的最低到最高值——只看平均會漏掉尖峰。',
  '1h': '每點代表一小時，帶狀區間是該小時的最低到最高值——只看平均會漏掉尖峰。',
};

export function formatNumber(value: number, digits = 1): string {
  return value.toLocaleString('zh-TW', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function formatInt(value: number): string {
  return value.toLocaleString('zh-TW');
}

export function formatTime(iso: string | number | null): string {
  if (iso === null) return '—';
  const d = new Date(iso);
  return d.toLocaleString('zh-TW', { hour12: false });
}

export function formatRelative(iso: string | number | null): string {
  if (iso === null) return '從未回報';
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 60_000) return `${Math.max(0, Math.floor(diff / 1000))} 秒前`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分鐘前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小時前`;
  return `${Math.floor(diff / 86_400_000)} 天前`;
}

export const CABINET_TYPE_LABEL: Record<string, string> = {
  POWER: '配電櫃',
  SERVER: '伺服器櫃',
  SENSOR: '感測櫃',
};

export const COMPARISON_LABEL: Record<string, string> = {
  GT: '>',
  GTE: '≥',
  LT: '<',
  LTE: '≤',
  OUT_OF_RANGE: '∉',
};
