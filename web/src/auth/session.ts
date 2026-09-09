import { useSyncExternalStore } from 'react';
import type { AuthUser, Role } from '../api/types';

/**
 * 登入狀態的唯一真相來源：token 與使用者放 localStorage，
 * 頁面重新整理不用重登；所有讀取都經過這裡，client.ts 與畫面才不會各記一份。
 */
const KEY = 'iotmon.session';

export interface Session {
  token: string;
  user: AuthUser;
}

let current: Session | null = load();
const listeners = new Set<() => void>();

function load(): Session | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Session;
    // 過期的 token 留著只會換來一次 401，直接當沒登入
    if (Date.parse(parsed.user.expiresAt) <= Date.now()) return null;
    return parsed;
  } catch {
    return null;
  }
}

function emit(): void {
  for (const l of listeners) l();
}

export function getSession(): Session | null {
  return current;
}

export function getToken(): string | null {
  return current?.token ?? null;
}

export function setSession(session: Session): void {
  current = session;
  try {
    localStorage.setItem(KEY, JSON.stringify(session));
  } catch {
    // 私密視窗等情況存不進去也沒關係，這次工作階段還在記憶體裡
  }
  emit();
}

export function clearSession(): void {
  current = null;
  try {
    localStorage.removeItem(KEY);
  } catch {
    // 同上
  }
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useSession(): Session | null {
  return useSyncExternalStore(subscribe, getSession, getSession);
}

/** 與後端 Role.canConfigure／canOperate 同一份定義；後端仍會再檢查一次，這裡只決定畫面顯不顯示 */
export const CAN: Record<'configure' | 'operate', Role[]> = {
  configure: ['ADMIN'],
  operate: ['ADMIN', 'OPERATOR'],
};

export function can(user: AuthUser | null | undefined, action: keyof typeof CAN): boolean {
  return !!user && CAN[action].includes(user.role);
}

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: '管理員',
  OPERATOR: '值班工程師',
  VIEWER: '唯讀',
};
