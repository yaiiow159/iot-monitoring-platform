import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { api, USE_MOCK } from '../api';
import { setSession, useSession } from '../auth/session';

/** 登入頁。已登入就直接送回原本要去的地方。 */
export function Login() {
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (session) return <Navigate to={from} replace />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { token, ...user } = await api.login({ username: username.trim(), password });
      setSession({ token, user });
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '登入失敗');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <div className="ambient" aria-hidden="true">
        <span className="ambient-glow ambient-glow-a" />
        <span className="ambient-glow ambient-glow-b" />
      </div>
      <form className="login-card" onSubmit={submit}>
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span className="brand-name">IoT 裝置監控平台</span>
        </div>
        <p className="sub">請以你的帳號登入。角色決定你能改什麼：管理員改設定、值班工程師動裝置與樹、唯讀只看。</p>

        <label className="field">
          <span>帳號</span>
          <input
            className="input"
            autoComplete="username"
            autoFocus
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </label>
        <label className="field">
          <span>密碼</span>
          <input
            className="input"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>

        {error && <p className="error">{error}</p>}

        <button className="btn btn-primary login-submit" type="submit" disabled={busy || !username || !password}>
          {busy ? '登入中…' : '登入'}
        </button>

        {USE_MOCK ? (
          <p className="hint">MOCK 模式：任何帳號都能登入，帳號名含 admin／operator 決定角色。</p>
        ) : (
          <p className="hint">示範帳號：admin／operator／viewer，密碼見 application.yml 的 iot.auth.bootstrap。</p>
        )}
      </form>
    </div>
  );
}
