/**
 * 商品与主数据 · 原料库存（票 06 / spec `unified-business-frontend` D4、D6、D7）。
 *
 * 本票是原料库存链路的第一颗曳光弹：菜单位、路由、页面骨架与错误呈现一次打通，**不接真实数据**
 * ——上游 yuanliaokc 今天只有 stdio MCP 面、没有 HTTP 接口，子牧后端在容器里够不着（D7）。
 *
 * 因此这里**刻意不渲染任何表格骨架、不渲染任何数字**：一张写着「暂无数据」的空表，或者一个 0，
 * 会被运营读成「原料没了」并据此下采购决定。页面只呈现一句说清「读不到」的状态，措辞与口径
 * 全部由 `rawMaterialInventoryView` 承载（那是可单测的纯函数，不是散落在 JSX 里的文案）。
 *
 * 状态判据取外壳读到的那份后端模块开放清单（票 03/04 的 `useBusinessModuleStatus`），
 * 不在页面里另立标准——菜单里没有这个入口、页面上说未接通，必须是同一个事实的两种呈现。
 */

import { Alert, Tag, Typography } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import PageShell from '@/components/PageShell';
import { useBusinessModuleStatus } from '@/components/layout/useBusinessModules';
import { PageState } from '@/pages/shared/PageState';
import '@/pages/shared/adminSurface.css';
import {
  RAW_MATERIAL_CHECKING_HINT,
  RAW_MATERIAL_SCOPE_NOTE,
  rawMaterialInventoryNotice,
  rawMaterialInventoryState,
} from './rawMaterialInventoryView';

export default function RawMaterialInventoryPage() {
  const module = useBusinessModuleStatus('raw-material-inventory');
  const notice = rawMaterialInventoryNotice(rawMaterialInventoryState(module));

  return (
    <div className="admin-page">
      <PageShell
        title="原料库存"
        description="原料、批次与结存的只读视图，事实来自原料库存系统（yuanliaokc）。"
        actions={<Tag bordered={false} icon={<LockOutlined />}>只读视图</Tag>}
      >
        {notice ? (
          <Alert type={notice.tone} showIcon message={notice.title} description={notice.description} />
        ) : (
          <PageState state="loading" description={RAW_MATERIAL_CHECKING_HINT} />
        )}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {RAW_MATERIAL_SCOPE_NOTE}
        </Typography.Text>
      </PageShell>
    </div>
  );
}
