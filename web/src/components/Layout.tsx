import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Clock } from './Clock';
import { USE_MOCK } from '../api';
import { clearSession, ROLE_LABEL, useSession } from '../auth/session';
import { useLive } from '../live/LiveContext';
import { formatInt } from '../utils/format';

const CONNECTION_TEXT = {
  connecting: '連線中…',
  open: '即時連線正常',
  closed: '連線中斷',
} as const;

const navClass = ({ isActive }: { isActive: boolean }) => (isActive ? 'nav-item active' : 'nav-item');

export function Layout() {
  const { connection, subscribedCount } = useLive();
  const session = useSession();
  const navigate = useNavigate();

  function logout() {
    clearSession();
    navigate('/login', { replace: true });
  }

  return (
    <div className="app">
      {/* 環境光層：點陣網格、緩慢漂移的光暈、極淡的掃描線。純裝飾，不擋滑鼠 */}
      <div className="ambient" aria-hidden="true">
        <span className="ambient-glow ambient-glow-a" />
        <span className="ambient-glow ambient-glow-b" />
      </div>
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span className="brand-name">IoT 裝置監控平台</span>
        </div>

        <nav className="nav">
          <NavLink to="/" end className={navClass}>
            總覽
          </NavLink>
          <NavLink to="/tree" className={navClass}>
            監控樹
          </NavLink>
          <NavLink to="/replay" className={navClass}>
            歷史回放
          </NavLink>
          <NavLink to="/config" className={navClass}>
            設定中心
          </NavLink>
        </nav>

        <div className="topbar-right">
          <Clock />
          {USE_MOCK && <span className="badge badge-mock">MOCK</span>}
          <span className="sub num" title="只訂閱目前畫面上看得到的裝置">
            訂閱 {formatInt(subscribedCount)} 台
          </span>
          <span className={`conn conn-${connection}`}>
            <span className="conn-dot" />
            {CONNECTION_TEXT[connection]}
          </span>
          {session && (
            <span className="user-chip" title={session.user.username}>
              <span className={`role role-${session.user.role}`}>{ROLE_LABEL[session.user.role]}</span>
              <span>{session.user.displayName}</span>
              <button type="button" className="link-btn" onClick={logout}>
                登出
              </button>
            </span>
          )}
        </div>
      </header>

      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
