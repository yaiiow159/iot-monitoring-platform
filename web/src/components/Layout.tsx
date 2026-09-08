import { NavLink, Outlet } from 'react-router-dom';
import { USE_MOCK } from '../api';
import { useLive } from '../live/LiveContext';
import { formatInt } from '../utils/format';

const CONNECTION_TEXT = {
  connecting: '連線中…',
  open: '即時連線正常',
  closed: '連線中斷',
} as const;

export function Layout() {
  const { connection, subscribedCount } = useLive();

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" />
          <span className="brand-name">IoT 裝置監控平台</span>
        </div>

        <nav className="nav">
          <NavLink to="/" end className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}>
            總覽
          </NavLink>
          <NavLink
            to="/config"
            className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}
          >
            設定中心
          </NavLink>
        </nav>

        <div className="topbar-right">
          {USE_MOCK && <span className="badge badge-mock">MOCK 資料</span>}
          <span className="sub" title="只訂閱目前畫面上看得到的裝置">
            訂閱 {formatInt(subscribedCount)} 台
          </span>
          <span className={`conn conn-${connection}`}>
            <span className="conn-dot" />
            {CONNECTION_TEXT[connection]}
          </span>
        </div>
      </header>

      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
