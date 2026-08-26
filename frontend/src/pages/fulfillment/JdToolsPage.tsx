/**
 * 京东工具（UIUX-10 #144）：六个京东只读查询工具收敛为单入口 + 页内 Tab 切换。
 * 原六条直达 URL 保留（路由仍各自注册到本页并定位到对应 Tab），书签不失效。
 */
import { useLocation, useNavigate } from 'react-router-dom';
import { Card, Tabs } from 'antd';
import PageShell from '@/components/PageShell';
import JdWarehousePage from '@/pages/fulfillment/JdWarehousePage';
import JdBasicInfoQueryPage from '@/pages/fulfillment/JdBasicInfoQueryPage';
import JdStockQueryPage from '@/pages/fulfillment/JdStockQueryPage';
import JdSerialQueryPage from '@/pages/fulfillment/JdSerialQueryPage';
import JdOrderQueryPage from '@/pages/fulfillment/JdOrderQueryPage';
import JdReturnQueryPage from '@/pages/fulfillment/JdReturnQueryPage';

const JD_TOOL_TABS = [
  { path: '/fulfillment/jd-warehouse', label: '连接与出库查询', element: <JdWarehousePage /> },
  { path: '/fulfillment/jd-basicinfo', label: '基础资料查询', element: <JdBasicInfoQueryPage /> },
  { path: '/fulfillment/jd-stock', label: '库存原始查询', element: <JdStockQueryPage /> },
  { path: '/fulfillment/jd-serial', label: '序列号查询', element: <JdSerialQueryPage /> },
  { path: '/fulfillment/jd-order', label: '京东专业单据', element: <JdOrderQueryPage /> },
  { path: '/fulfillment/jd-return', label: '退货退供查询', element: <JdReturnQueryPage /> },
] as const;

export default function JdToolsPage() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const active = JD_TOOL_TABS.find((tab) => pathname.startsWith(tab.path))?.path ?? JD_TOOL_TABS[0].path;

  return (
    <PageShell
      title="京东工具"
      description="六个京东只读查询工具统一收纳为页内 Tab；旧直达 URL 保留可打开对应工具。"
    >
      <Card size="small" styles={{ body: { paddingTop: 8 } }}>
        <Tabs
          activeKey={active}
          onChange={(key) => navigate(key)}
          items={JD_TOOL_TABS.map((tab) => ({
            key: tab.path,
            label: tab.label,
            children: tab.element,
          }))}
        />
      </Card>
    </PageShell>
  );
}
