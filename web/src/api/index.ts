import { httpApi } from './client';
import { mockApi } from './mock';
import type { IotApi } from './types';

/**
 * 後端已完整實作，未設定時預設打真實後端；要離線看畫面就設 VITE_USE_MOCK=true。
 * 兩者實作同一個 IotApi 介面，切換不需要改任何頁面程式碼。
 */
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';

export const api: IotApi = USE_MOCK ? mockApi : httpApi;

export * from './types';
