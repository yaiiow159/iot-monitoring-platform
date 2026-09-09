import { useState, type FormEvent, type ReactNode } from 'react';
import { can, ROLE_LABEL, useSession } from '../auth/session';

/** 送出狀態與結果訊息。失敗的原文來自後端的領域規則，照原文顯示。 */
export function useSubmit<T>(action: () => Promise<T>, onSuccess: (result: T) => string) {
  const [busy, setBusy] = useState(false);
  const [ok, setOk] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setOk(null);
    setError(null);
    try {
      setOk(onSuccess(await action()));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }
  return { busy, ok, error, submit };
}

export function Feedback({ ok, error }: { ok: string | null; error: string | null }) {
  return (
    <>
      {error && (
        <p className="error" role="alert">
          <span className="caption">後端拒絕</span>
          {error}
        </p>
      )}
      {ok && <p className="ok-box">{ok}</p>}
    </>
  );
}

/** 沒有權限就不畫表單，只留一句話說明誰能做；後端仍會再擋一次。 */
export function Gate({ action, children }: { action: 'configure' | 'operate'; children: ReactNode }) {
  const session = useSession();
  if (can(session?.user, action)) return <>{children}</>;
  const who = action === 'configure' ? '管理員' : '管理員或值班工程師';
  return (
    <p className="empty">
      你的角色是「{session ? ROLE_LABEL[session.user.role] : '未登入'}」，這個操作需要{who}。
    </p>
  );
}
