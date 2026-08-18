/**
 * 路由装配：布局路由（侧边栏 + 顶栏）包裹全部业务路由。
 * 路由表只来自 routes.tsx 的 routeConfig —— 后续票扩展只改配置数组。
 */

import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from '@/components/layout/AppLayout';
import { flattenRoutes, routeConfig } from '@/routes';

export default function App() {
  const routes = flattenRoutes(routeConfig);
  return (
    <Routes>
      <Route element={<AppLayout />}>
        {routes.map((r) => (
          <Route key={r.path} path={r.path} element={r.element} />
        ))}
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Route>
    </Routes>
  );
}
