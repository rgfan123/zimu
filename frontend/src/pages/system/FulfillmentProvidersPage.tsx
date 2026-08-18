/** 系统管理 · 履约方配置：统一维护京东云仓与第三方履约方目录。 */

import { useMemo, useState } from 'react';
import {
  App as AntApp,
  Button,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { providersApi } from '@/api/endpoints';
import type { FulfillmentProvider } from '@/api/types';
import { PROVIDER_TYPE_LABELS } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import {
  AdminCategoryTag,
  AdminEmpty,
  AdminFailureAlert,
  AdminLoading,
  AdminStatusTag,
} from '@/pages/shared/AdminVisualComponents';
import { adminPageState } from '@/pages/shared/adminVisual';
import '@/pages/shared/adminSurface.css';

/** 京东建单所需标识（jd-real-sdk-switch 01）；customerCode 属 02 票客户级字段，不在此列。 */
const JD_CONFIG_KEYS = [
  'sourceNo',
  'warehouseNo',
  'pin',
  'erpShopNo',
  'salesPlatformSource',
  'ownerNo',
  'shopNo',
  'carrierNo',
  'townRequired',
] as const;

const JD_CONFIG_LABELS: Record<string, string> = {
  sourceNo: '来源编码 sourceNo',
  warehouseNo: '仓库编码 warehouseNo',
  pin: '京东 pin',
  erpShopNo: 'ERP 店铺编码 erpShopNo',
  salesPlatformSource: '销售平台来源 salesPlatformSource',
  ownerNo: '货主编码 ownerNo',
  shopNo: '店铺编码 shopNo',
  carrierNo: '承运商编码 carrierNo',
  townRequired: '乡镇必填 townRequired',
  outboundMode: '建单路由 outboundMode',
};

const OUTBOUND_MODE_LABELS: Record<string, string> = {
  SDK: 'SDK 直连（确认批次后自动建单）',
  FILE: '导单文件（缺省，保持既有路径）',
};

const JD_STRING_KEYS = JD_CONFIG_KEYS.filter((key) => key !== 'pin' && key !== 'townRequired');

export default function FulfillmentProvidersPage() {
  const { message: messageApi } = AntApp.useApp();
  const { data, loading, error, reload } = useAsync(() => providersApi.list(), []);
  const [editing, setEditing] = useState<FulfillmentProvider | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const rows = useMemo(() => data ?? [], [data]);

  const openEdit = (record: FulfillmentProvider) => {
    const values: Record<string, unknown> = {
      provider_name: record.provider_name,
      tracking_sla_minutes: record.tracking_sla_minutes,
      active: record.active,
    };
    if (record.provider_type === 'JD_WAREHOUSE') {
      for (const key of JD_STRING_KEYS) {
        const entry = record.jd_config?.[key];
        values[key] = entry?.present && typeof entry.value === 'string' ? entry.value : '';
      }
      const town = record.jd_config?.townRequired;
      values.townRequired = town?.present ? Boolean(town.value) : false;
      const mode = record.jd_config?.outboundMode;
      values.outboundMode = mode?.present && typeof mode.value === 'string' ? mode.value : 'FILE';
    }
    form.setFieldsValue(values);
    setEditing(record);
  };

  const closeEditor = () => {
    if (submitting) return;
    setEditing(null);
    form.resetFields();
  };

  const handleSubmit = async () => {
    if (!editing) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const config: Record<string, string | boolean> = {};
      if (editing.provider_type === 'JD_WAREHOUSE') {
        for (const key of JD_STRING_KEYS) {
          if (typeof values[key] === 'string' && values[key].length > 0) {
            config[key] = values[key] as string;
          }
        }
        if (typeof values.pin === 'string' && values.pin.length > 0) {
          config.pin = values.pin;
        }
        if (typeof values.townRequired === 'boolean') {
          config.townRequired = values.townRequired;
        }
        if (typeof values.outboundMode === 'string' && values.outboundMode.length > 0) {
          config.outboundMode = values.outboundMode;
        }
      }
      await providersApi.update(editing.id, {
        expected_version: editing.version,
        provider_name: typeof values.provider_name === 'string' ? values.provider_name : undefined,
        tracking_sla_minutes:
          typeof values.tracking_sla_minutes === 'number' ? values.tracking_sla_minutes : undefined,
        active: typeof values.active === 'boolean' ? values.active : undefined,
        config: editing.provider_type === 'JD_WAREHOUSE' ? config : undefined,
      });
      messageApi.success('履约方配置已保存');
      setEditing(null);
      form.resetFields();
      reload();
    } catch (submitError) {
      if (submitError instanceof Error) messageApi.error(errorMessage(submitError));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<FulfillmentProvider> = [
    {
      title: '履约方编码',
      dataIndex: 'provider_code',
      width: 160,
      render: (value: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>,
    },
    { title: '履约方名称', dataIndex: 'provider_name', width: 220 },
    {
      title: '类型',
      dataIndex: 'provider_type',
      width: 120,
      render: (value: FulfillmentProvider['provider_type']) => (
        <AdminCategoryTag category={value}>{PROVIDER_TYPE_LABELS[value]}</AdminCategoryTag>
      ),
    },
    {
      title: '京东标识',
      dataIndex: 'jd_config',
      width: 180,
      render: (config: FulfillmentProvider['jd_config'], record) => {
        if (record.provider_type !== 'JD_WAREHOUSE') {
          return <span>—</span>;
        }
        const missing = JD_CONFIG_KEYS.filter((key) => !config?.[key]?.present);
        const mode = config?.outboundMode?.present && config.outboundMode.value === 'SDK' ? 'SDK' : 'FILE';
        return (
          <Space size={6} wrap>
            {missing.length === 0 ? (
              <Tag color="success">全部就绪</Tag>
            ) : (
              <Tooltip title={`缺失：${missing.map((key) => JD_CONFIG_LABELS[key]).join('、')}`}>
                <Tag color="warning">缺 {missing.length} 项</Tag>
              </Tooltip>
            )}
            <Tag color={mode === 'SDK' ? 'blue' : 'default'}>{OUTBOUND_MODE_LABELS[mode]}</Tag>
          </Space>
        );
      },
    },
    {
      title: '运单回传时限',
      dataIndex: 'tracking_sla_minutes',
      width: 150,
      align: 'right',
      render: (value: number) => (value ? `${value} 分钟` : '—'),
    },
    {
      title: '状态',
      dataIndex: 'active',
      width: 100,
      render: (value: boolean) => <AdminStatusTag status={value ? 'ACTIVE' : 'INACTIVE'} />,
    },
    { title: '版本', dataIndex: 'version', width: 80, align: 'right' },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, record) => <Typography.Link onClick={() => openEdit(record)}>编辑</Typography.Link>,
    },
  ];

  const viewState = adminPageState(loading, error, rows.length > 0);

  if (viewState === 'loading') {
    return <div className="admin-page"><AdminLoading description="正在加载履约方目录…" /></div>;
  }

  if (viewState === 'error') {
    return (
      <div className="admin-page">
        <AdminFailureAlert error={error} title="履约方目录加载失败" onRetry={reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-page__intro">
        <Typography.Text className="admin-page__intro-copy" type="secondary">
          统一维护京东云仓与第三方履约方。缺货采购完成后，订单仍回到原履约方继续处理。
        </Typography.Text>
        <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
      </div>

      <div className="admin-surface">
        <Table<FulfillmentProvider>
          rowKey="id"
          columns={columns}
          dataSource={rows}
          size="middle"
          scroll={{ x: 1000 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <AdminEmpty description="暂无履约方配置" /> }}
        />
      </div>

      <Modal
        title={`编辑履约方 ${editing?.provider_code ?? ''}`}
        open={Boolean(editing)}
        onCancel={closeEditor}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okButtonProps={{ disabled: submitting }}
        cancelButtonProps={{ disabled: submitting }}
        closable={!submitting}
        maskClosable={!submitting}
        keyboard={!submitting}
        width={560}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="provider_name" label="履约方名称" rules={[{ required: true, message: '请填写履约方名称' }]}>
            <Input placeholder="请输入履约方名称" />
          </Form.Item>
          <Form.Item
            name="tracking_sla_minutes"
            label="运单回传时限（分钟）"
            rules={[{ required: true, message: '请填写运单回传时限' }]}
          >
            <InputNumber min={1} style={{ width: '100%' }} placeholder="如 1440" />
          </Form.Item>
          <Form.Item
            name="active"
            label="启用"
            valuePropName="checked"
            extra="停用后不再生成新的履约导出文件，库存不计入库存总览；既有订单、已导入批次与既有运单回传处理不受影响"
          >
            <Switch />
          </Form.Item>
          {editing?.provider_type === 'JD_WAREHOUSE' && (
            <>
              <Divider orientation="left" style={{ margin: '8px 0 16px' }}>
                京东标识（缺失时建单预览会阻断）
              </Divider>
              {JD_STRING_KEYS.map((key) => (
                <Form.Item
                  key={key}
                  name={key}
                  label={JD_CONFIG_LABELS[key]}
                  rules={[{ required: true, message: `请填写 ${JD_CONFIG_LABELS[key]}` }]}
                >
                  <Input placeholder={`请输入 ${JD_CONFIG_LABELS[key]}`} />
                </Form.Item>
              ))}
              <Form.Item
                name="pin"
                label="京东 pin"
                extra="留空表示保持现有值；保存后只显示是否已配置，永不回显明文"
              >
                <Input.Password
                  placeholder={editing.jd_config?.pin?.present ? '已配置（不显示明文）' : '未配置'}
                />
              </Form.Item>
              <Form.Item
                name="townRequired"
                label="乡镇必填 townRequired"
                valuePropName="checked"
                extra="只接受布尔值；缺失时预览不会猜测京东要求"
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="outboundMode"
                label="建单路由 outboundMode"
                extra="SDK 直连：确认导入批次后自动对京东发货批次建出库单（前置未就绪的落待处理）；导单文件为缺省路径，切换不影响历史批次"
              >
                <Select
                  options={Object.entries(OUTBOUND_MODE_LABELS).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </div>
  );
}
