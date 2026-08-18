import { Alert, Button, Card, Descriptions, Space, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Link, useSearchParams } from 'react-router-dom';
import { inventoryApi } from '@/api/endpoints';
import type { InventoryDetailCapability, InventoryDetailsResponse } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { AdminFailureAlert, AdminLoading } from '@/pages/shared/AdminVisualComponents';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import '@/pages/shared/adminSurface.css';
import './inventoryOverview.css';
import {
  inventoryObservationPresentation,
  inventoryQuantityLabel,
  inventoryQuantityUnit,
  inventorySourceLabel,
  inventoryTimeLabel,
} from './inventoryOverviewView';
import {
  inventoryCapabilityModeLabel,
  inventoryCapabilityTools,
  inventoryDetailModeLabel,
  safeInventoryReturnLocation,
} from './inventoryDetailsView';

function capabilityTone(capability: InventoryDetailCapability): string | undefined {
  if (capability.integration_status !== 'INTEGRATED') return undefined;
  return capability.runtime_mode === 'REAL' ? 'blue' : undefined;
}

function detailRequest(params: URLSearchParams): Promise<InventoryDetailsResponse> {
  return inventoryApi.details({
    provider_id: params.get('provider_id') ?? '',
    sku_id: params.get('sku_id') ?? '',
    warehouse_code: params.get('warehouse_code') ?? undefined,
  });
}

export default function InventoryDetailsPage() {
  const [searchParams] = useSearchParams();
  const providerId = searchParams.get('provider_id') ?? '';
  const skuId = searchParams.get('sku_id') ?? '';
  const warehouseCode = searchParams.get('warehouse_code') ?? undefined;
  const returnTo = safeInventoryReturnLocation(searchParams.get('return_to'));
  const details = useAsync(
    () => detailRequest(searchParams),
    [providerId, skuId, warehouseCode],
  );

  if (details.loading) {
    return <div className="admin-page"><AdminLoading description="正在加载专业库存明细…" /></div>;
  }
  if (details.error) {
    return (
      <div className="admin-page">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Link to={returnTo}><ArrowLeftOutlined /> 返回总库存</Link>
          <AdminFailureAlert error={details.error} title="专业库存明细加载失败" onRetry={details.reload} />
        </Space>
      </div>
    );
  }

  const response = details.data;
  if (!response) return null;
  const { context, observation } = response;
  const observationPresentation = inventoryObservationPresentation(observation);
  const unit = inventoryQuantityUnit({ quantity_unit: observation.quantity_unit, unit: context.unit });

  return (
    <div className="admin-page inventory-details">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Space wrap>
          <Link to={returnTo}><ArrowLeftOutlined /> 返回总库存</Link>
          <Typography.Title level={5} style={{ margin: 0 }}>专业库存明细</Typography.Title>
          <Tag>{inventoryDetailModeLabel(observation)}</Tag>
          <Tag color={observationPresentation.tone === 'error' ? 'red' : observationPresentation.tone}>
            {observationPresentation.label}
          </Tag>
        </Space>

        <Card size="small" title="库存对象">
          <Descriptions
            size="small"
            column={{ xs: 1, sm: 2, xl: 4 }}
            items={[
              { key: 'sku', label: '商品 / SKU', children: <ProductIdentity name={context.product_name} code={context.sku_code} meta={[context.specification, context.unit]} /> },
              { key: 'provider', label: '履约方', children: `${context.provider_name} · ${context.provider_code}` },
              { key: 'warehouse', label: '仓库', children: context.warehouse_code ?? '尚未观测仓库' },
              { key: 'provider_sku', label: '履约方商品编码', children: context.provider_sku_code ?? '未配置' },
              { key: 'total', label: '总库存', children: inventoryQuantityLabel(observation.total_quantity, unit) },
              { key: 'available', label: '可用库存', children: inventoryQuantityLabel(observation.available_quantity, unit) },
              { key: 'unavailable', label: '不可用差额', children: inventoryQuantityLabel(observation.unavailable_quantity, unit) },
              { key: 'observed_at', label: '观测时间', children: inventoryTimeLabel(observation.observed_at) },
              { key: 'source', label: '数据来源', children: inventorySourceLabel(observation.source_type) },
              { key: 'query_time', label: '本页查询时间', children: inventoryTimeLabel(response.query_time) },
              { key: 'expires_at', label: '时效边界', children: inventoryTimeLabel(observation.expires_at) },
              { key: 'policy', label: '时效策略', children: response.freshness_policy },
            ]}
          />
        </Card>

        {observation.freshness_status === 'STALE' ? (
          <Alert
            type="warning"
            showIcon
            message="数据已过期"
            description="当前数量来自已落库的缓存快照，不是实时预占。用于履约决策前，请通过受审计的履约库存检查重新确认；刷新本页只会重读已落库事实。"
          />
        ) : null}

        <div className="inventory-details__capabilities" aria-label="履约方库存查询能力">
          {response.capabilities.map((capability) => {
            const tools = inventoryCapabilityTools(capability, context);
            return (
              <Card
                key={capability.group}
                size="small"
                title={capability.label}
                extra={<Tag color={capabilityTone(capability)}>{inventoryCapabilityModeLabel(capability)}</Tag>}
              >
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <Typography.Text type="secondary">{capability.explanation}</Typography.Text>
                  {tools.length ? (
                    <Space wrap>
                      {tools.map((tool) => (
                        <Link key={tool.code} to={tool.href}>{tool.label}</Link>
                      ))}
                    </Space>
                  ) : (
                    <Typography.Text type="secondary">
                      {capability.integration_status === 'NOT_INTEGRATED' ? '未接入' : '当前不可查询'}
                    </Typography.Text>
                  )}
                </Space>
              </Card>
            );
          })}
        </div>

        <Alert
          type="info"
          showIcon
          message="口径说明"
          description="本页只展示已有总库存字段与履约方明确接入的专业查询；不自动增加在途、预留等未经证实的数字。京东原始查询仍是系统工具，其返回不直接改写权威库存。"
          action={<Button icon={<ReloadOutlined />} onClick={details.reload}>重读已落库明细</Button>}
        />
      </Space>
    </div>
  );
}
