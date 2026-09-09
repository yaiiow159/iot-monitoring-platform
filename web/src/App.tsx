import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useSession } from './auth/session';
import { Layout } from './components/Layout';
import { LiveProvider } from './live/LiveContext';
import { Config } from './pages/Config';
import { DeviceDetail } from './pages/DeviceDetail';
import { Login } from './pages/Login';
import { Overview } from './pages/Overview';
import { Tree } from './pages/Tree';

/** 沒登入就送去登入頁，並記住原本要去哪。 */
function RequireAuth({ children }: { children: ReactNode }) {
  const session = useSession();
  const location = useLocation();
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

/**
 * LiveProvider 放在 RequireAuth 裡面、路由外層：換頁時 WebSocket 不斷線，
 * 但登入／登出會重新掛載，握手才會帶上新的 token。
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        element={
          <RequireAuth>
            <LiveProvider>
              <Layout />
            </LiveProvider>
          </RequireAuth>
        }
      >
        <Route path="/" element={<Overview />} />
        <Route path="/devices/:deviceId" element={<DeviceDetail />} />
        <Route path="/tree" element={<Tree />} />
        <Route path="/config" element={<Config />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
