/**
 * 系统 · MCP 开放面（只读核对视图，票 05）。
 *
 * 管理员改完 `MCP_MODULES` 之后，在界面上就能确认结果符合预期，不必进容器翻环境变量。
 *
 * 三条口径（与后端 `GET /api/v1/mcp-exposure` 同源，页面不自己推导）：
 * - **已开放**：模块的工具真的进了注册表，外部 MCP 面与内部 Agent 平台此刻都能调用；
 *   工具名与用途摘要取工具自己声明的元数据。
 * - **已知但未开放**：系统里有声明该模块的工具，但当前配置没列出它，一个工具都没注册。
 *   只列模块名——未注册的工具没有明细可给，凭空列出「开了会有什么」就得另建一份必然漂移的清单。
 * - **纯只读**：本页不提供任何修改开放面的控件。开放面由部署期的 `MCP_MODULES` 决定、
 *   启动期一次性生效（ADR 0015），界面上能改就等于绕过部署评审，且注册表运行期不可变。
 */

import { Card, Space, Tag, Typography } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { mcpExposureApi } from '@/api/endpoints';
import type { McpExposureTool } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { AdminEmpty } from '@/pages/shared/AdminVisualComponents';
import { adminFailurePresentation } from '@/pages/shared/adminVisual';
import { PageState } from '@/pages/shared/PageState';
import '@/pages/shared/adminSurface.css';

const toolColumns: ColumnsType<McpExposureTool> = [
  {
    title: '工具名',
    dataIndex: 'name',
    width: 260,
    render: (value: string) => <span style={{ fontFamily: 'monospace' }}>{value}</span>,
  },
  { title: '用途摘要', dataIndex: 'description', ellipsis: true },
  {
    title: '读写',
    dataIndex: 'read_only',
    width: 90,
    render: (readOnly: boolean) =>
      readOnly ? <Tag color="blue">读</Tag> : <Tag color="volcano">写</Tag>,
  },
];

export default function McpExposurePage() {
  const exposure = useAsync(() => mcpExposureApi.read(), []);

  if (exposure.loading) {
    return (
      <div className="admin-page">
        <PageState state="loading" description="正在读取 MCP 开放面…" />
      </div>
    );
  }

  if (exposure.error || !exposure.data) {
    const presentation = adminFailurePresentation(exposure.error, 'MCP 开放面加载失败');
    return (
      <div className="admin-page">
        <PageState
          state="error"
          message={presentation.title}
          description={presentation.description}
          onRetry={exposure.reload}
        />
      </div>
    );
  }

  const { open_modules: openModules, unopened_modules: unopenedModules } = exposure.data;
  const openTools = openModules.flatMap((module) => module.tools);
  const writeToolCount = openTools.filter((tool) => !tool.read_only).length;

  return (
    <div className="admin-page">
      <PageShell
        title="MCP 开放面"
        description="当前注册的 MCP 工具按模块分组；改完 MCP_MODULES 后在这里核对结果。"
        actions={<Tag bordered={false} icon={<LockOutlined />}>只读核对</Tag>}
      >
        <Card size="small" title="已开放模块">
          {openModules.length === 0 ? (
            <>
              <AdminEmpty description="当前没有开放任何 MCP 模块" />
              <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 10 }}>
                这是空值的既定语义，不是故障：<Typography.Text code>MCP_MODULES</Typography.Text>
                未配置（或解析后为空）时一个工具都不注册。需要开放请在部署配置里显式列出模块名并重启后端。
              </Typography.Text>
            </>
          ) : (
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                已开放 {openModules.length} 个模块、共 {openTools.length} 个工具
                {writeToolCount > 0 ? (
                  <Typography.Text type="danger" style={{ fontSize: 12 }}>
                    ，其中 {writeToolCount} 个是写工具
                  </Typography.Text>
                ) : (
                  '，全部为只读工具'
                )}
                。
              </Typography.Text>
              {openModules.map((module) => (
                <div key={module.module}>
                  <Space size={8} style={{ marginBottom: 8 }}>
                    <Typography.Text strong style={{ fontFamily: 'monospace' }}>
                      {module.module}
                    </Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {module.tools.length} 个工具
                    </Typography.Text>
                  </Space>
                  <DataTable<McpExposureTool>
                    rowKey="name"
                    size="small"
                    columns={toolColumns}
                    dataSource={module.tools}
                    pagination={false}
                  />
                </div>
              ))}
            </Space>
          )}
        </Card>

        <Card size="small" title="已知但未开放的模块">
          {unopenedModules.length === 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              全部已知模块都已开放，没有未开放的模块。
            </Typography.Text>
          ) : (
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Space wrap size={8}>
                {unopenedModules.map((module) => (
                  <Tag key={module} style={{ fontFamily: 'monospace' }}>
                    {module}
                  </Tag>
                ))}
              </Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                这些模块有工具声明过，但当前配置没有列出它们，因此一个工具都没注册——
                外部 MCP 面与内部 Agent 平台都调不到。未开放模块不列工具明细：那些工具没进注册表，
                这里给不出经得起核对的清单。
              </Typography.Text>
            </Space>
          )}
        </Card>

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          本页只读，不提供改开放面的入口：开放面由部署期的 <Typography.Text code>MCP_MODULES</Typography.Text>
          决定、后端启动时一次性生效（ADR 0015），运行期改不动。工具名、用途摘要与读写属性都取自工具自身声明。
        </Typography.Text>
      </PageShell>
    </div>
  );
}
