/**
 * 系统 · 系统配置（只读总览）。
 *
 * 契约（docs/api-contract.md §4.6）没有独立的 `/api/v1/system/config` 端点——
 * 系统级配置实体就是 ConnectorConfig（渠道接入）与 FulfillmentProvider（履约方），
 * 此处以只读总览呈现（编辑/测试动作在渠道接入 / 履约方配置页）。写操作统一走各资源端点，
 * 不为此新增不存在于契约的配置接口。
 */

import { Button, Card, Tag, Table, Typography } from 'antd';
import { LockOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { connectorsApi, providersApi } from '@/api/endpoints';
import { CHANNEL_LABELS, PROVIDER_TYPE_LABELS } from '@/constants/labels';
import type { ConnectorConfig, FulfillmentProvider, SourceChannel } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { AdminCategoryTag, AdminEmpty, AdminFailureAlert, AdminLoading, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { adminPageState } from '@/pages/shared/adminVisual';
import '@/pages/shared/adminSurface.css';

const TRANSPORT_LABELS = { EXCEL: '文件接入', API: '在线接入' } as const;
const MODE_LABELS = { MOCK: '仿真模式', REAL: '生产模式' } as const;

export default function SystemConfigPage() {
  const connectors = useAsync(() => connectorsApi.list(), []);
  const providers = useAsync(() => providersApi.list(), []);
  const error = connectors.error || providers.error;
  const reloadAll = () => {
    connectors.reload();
    providers.reload();
  };

  const connectorColumns: ColumnsType<ConnectorConfig> = [
    {
      title: '来源渠道',
      dataIndex: 'source_channel',
      width: 120,
      render: (v: SourceChannel) => <AdminCategoryTag category={v}>{CHANNEL_LABELS[v]}</AdminCategoryTag>,
    },
    {
      title: '接入方式',
      dataIndex: 'transport_mode',
      width: 120,
      render: (v: ConnectorConfig['transport_mode']) => TRANSPORT_LABELS[v],
    },
    {
      title: '客户端模式',
      dataIndex: 'client_mode',
      width: 110,
      render: (v: ConnectorConfig['client_mode']) => MODE_LABELS[v],
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (v: boolean) => <AdminStatusTag status={v ? 'ACTIVE' : 'INACTIVE'} />,
    },
    { title: '端点', dataIndex: 'endpoint', ellipsis: true, render: (v?: string | null) => v ?? '—' },
    {
      title: '凭据',
      dataIndex: 'credential_configured',
      width: 90,
      render: (v?: boolean) => <AdminStatusTag status={v ? 'CONFIGURED' : 'UNCONFIGURED'} />,
    },
  ];

  const providerColumns: ColumnsType<FulfillmentProvider> = [
    { title: '履约方编码', dataIndex: 'provider_code', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '履约方名称', dataIndex: 'provider_name', width: 220 },
    {
      title: '类型',
      dataIndex: 'provider_type',
      width: 100,
      render: (v: FulfillmentProvider['provider_type']) => (
        <AdminCategoryTag category={v}>{PROVIDER_TYPE_LABELS[v]}</AdminCategoryTag>
      ),
    },
    {
      title: '运单回传 SLA',
      dataIndex: 'tracking_sla_minutes',
      width: 140,
      align: 'right',
      render: (v: number) => `${v} 分钟`,
    },
    {
      title: '状态',
      dataIndex: 'active',
      width: 90,
      render: (v: boolean) => <AdminStatusTag status={v ? 'ACTIVE' : 'INACTIVE'} />,
    },
  ];

  const viewState = adminPageState(
    connectors.loading || providers.loading,
    error,
    Boolean(connectors.data?.length || providers.data?.length),
  );

  if (viewState === 'loading') {
    return <div className="admin-page"><AdminLoading description="正在加载系统配置…" /></div>;
  }

  if (viewState === 'error') {
    return (
      <div className="admin-page">
        <AdminFailureAlert error={error} title="系统配置加载失败" onRetry={reloadAll} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page__intro">
        <Typography.Text className="admin-page__intro-copy" type="secondary">
          集中查看渠道接入与履约方配置，具体变更请前往对应管理页。
        </Typography.Text>
        <Tag bordered={false} icon={<LockOutlined />}>只读总览</Tag>
      </div>

      <Card
        size="small"
        title="渠道接入配置"
        extra={
          <Button size="small" icon={<ReloadOutlined />} onClick={connectors.reload}>
            刷新
          </Button>
        }
      >
        <Table<ConnectorConfig>
          rowKey="source_channel"
          size="small"
          columns={connectorColumns}
          dataSource={connectors.data ?? []}
          pagination={false}
          locale={{ emptyText: <AdminEmpty description="暂无渠道接入配置" /> }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 10 }}>
          彩食鲜、聚福宝和飞象当前采用文件接入。完成平台授权和生产连通性验收后，方可切换为在线接入；配置维护与连通性检查见「渠道接入」。
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 6 }}>
          「启用」列表示渠道连通性测试开关：停用后，文件接入渠道的测试连接将判定为失败，文件导入、已导入批次与既有事实不受影响；企业微信长连接由连接就绪度诊断独立判定，不受此开关影响。
        </Typography.Text>
      </Card>

      <Card
        size="small"
        title="履约方配置"
        extra={
          <Button size="small" icon={<ReloadOutlined />} onClick={providers.reload}>
            刷新
          </Button>
        }
      >
        <Table<FulfillmentProvider>
          rowKey="id"
          size="small"
          columns={providerColumns}
          dataSource={providers.data ?? []}
          pagination={false}
          locale={{ emptyText: <AdminEmpty description="暂无履约方配置" /> }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 10 }}>
          编辑见「履约方配置」页（含运单回传 SLA）。
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 6 }}>
          「状态」列表示履约方可用开关：停用后不再生成新的履约导出文件、库存不计入库存总览；既有订单、已导入批次与既有运单回传处理不受影响。
        </Typography.Text>
      </Card>
    </div>
  );
}
