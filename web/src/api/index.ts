import { httpApi } from './client';
import { mockApi } from './mock';
import type { IotApi } from './types';

/**
 * 後端還沒完成，所以未設定時預設走 mock；要打真實後端就設 VITE_USE_MOCK=false。
 * 兩者實作同一個 IotApi 介面，切換不需要改任何頁面程式碼。
 */
export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false';

export const api: IotApi = USE_MOCK ? mockApi : httpApi;

export * from './types';
