import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { LiveProvider } from './live/LiveContext';
import { Config } from './pages/Config';
import { DeviceDetail } from './pages/DeviceDetail';
import { Overview } from './pages/Overview';
import { Tree } from './pages/Tree';

/**
 * LiveProvider 放在路由外層，換頁時 WebSocket 不會斷開重連——
 * 只有訂閱的裝置清單會跟著畫面換掉。
 */
export function App() {
  return (
    <LiveProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Overview />} />
          <Route path="/devices/:deviceId" element={<DeviceDetail />} />
          <Route path="/tree" element={<Tree />} />
          <Route path="/config" element={<Config />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </LiveProvider>
  );
}
