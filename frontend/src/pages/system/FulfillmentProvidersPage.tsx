/** 系统管理 · 履约方配置：统一维护京东云仓与第三方履约方目录。 */

import { useMemo, useState } from 'react';
import {
  App as AntApp,
  AutoComplete,
  Button,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { providersApi, wecomChatsApi } from '@/api/endpoints';
import type { FulfillmentProvider, KnownWecomChat } from '@/api/types';
import { PROVIDER_TYPE_LABELS } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import {
  AdminCategoryTag,
  AdminEmpty,
  AdminStatusTag,
} from '@/pages/shared/AdminVisualComponents';
import { adminFailurePresentation, adminPageState } from '@/pages/shared/adminVisual';
import { PageState } from '@/pages/shared/PageState';
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

/** 企微群 chatid 前端校验：与后端契约一致（可见 ASCII、无空白/控制字符、最长 128）。 */
const validateGroupChatId = (_rule: unknown, value: string | undefined) => {
  if (typeof value !== 'string' || value.length === 0) return Promise.resolve();
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return Promise.reject(new Error('企微群 chatid 不能只含空白字符；清除登记请留空保存'));
  }
  if (trimmed.length > 128) {
    return Promise.reject(new Error('企微群 chatid 最长 128 个字符'));
  }
  if (/[^\x21-\x7E]/.test(trimmed)) {
    return Promise.reject(new Error('企微群 chatid 只能包含可见 ASCII 字符（不含空白与控制字符）'));
  }
  return Promise.resolve();
};

/** 下拉候选：群在前（按活跃倒序，事件表就是这个序）、单聊在后；chatid 保持可手填。 */
function chatOptions(chats: KnownWecomChat[]) {
  const groups = chats.filter((chat) => chat.chat_type === 'group');
  const singles = chats.filter((chat) => chat.chat_type === 'single');
  const describe = (chat: KnownWecomChat) =>
    chat.chat_type === 'group'
      ? `群聊 · ${chat.chat_id}${chat.last_seen_at ? `（${chat.last_seen_at.slice(5, 10)} 活跃）` : ''}`
      : `单聊 · ${chat.chat_id}${chat.label ? `（${chat.label}）` : ''}`;
  const toOption = (chat: KnownWecomChat) => ({ value: chat.chat_id, label: describe(chat) });
  return [
    ...(groups.length > 0 ? [{ label: '机器人所在群聊', options: groups.map(toOption) }] : []),
    ...(singles.length > 0 ? [{ label: '运营人员单聊', options: singles.map(toOption) }] : []),
  ];
}

export default function FulfillmentProvidersPage() {
  const { message: messageApi } = AntApp.useApp();
  const { data, loading, error, reload } = useAsync(() => providersApi.list(), []);
  // 会话目录加载失败不挡编辑：候选只是便利，chatid 永远可以手填
  const { data: chatDirectory } = useAsync(
    () => wecomChatsApi.list().catch(() => ({ chats: [] as KnownWecomChat[] })),
    [],
  );
  const [editing, setEditing] = useState<FulfillmentProvider | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const rows = useMemo(() => data ?? [], [data]);

  const openEdit = (record: FulfillmentProvider) => {
    const values: Record<string, unknown> = {
      provider_name: record.provider_name,
      tracking_sla_minutes: record.tracking_sla_minutes,
      active: record.active,
      wecom_group_chat_id: record.wecom_group_chat_id ?? '',
      wecom_reminder_interval_minutes: record.wecom_reminder_interval_minutes ?? null,
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
      // 所有履约方类型都维护企微群 chatid：空串提交 null（清除登记），其余交给后端 trim/校验
      const config: Record<string, string | boolean | number | null> = {};
      const groupChatId = typeof values.wecom_group_chat_id === 'string' ? values.wecom_group_chat_id : '';
      config.wecomGroupChatId = groupChatId.length > 0 ? groupChatId : null;
      // 回传提醒间隔（Issue #84）：留空提交 null（恢复默认 = 运单回传时限），显式值 1..10080
      const reminderInterval = values.wecom_reminder_interval_minutes;
      config.wecomReminderIntervalMinutes =
        typeof reminderInterval === 'number' && reminderInterval > 0 ? reminderInterval : null;
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
        config,
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
      title: '企微群',
      dataIndex: 'wecom_group_chat_id',
      width: 200,
      ellipsis: true,
      render: (value: string | null) =>
        value ? (
          <Tag color="blue" style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</Tag>
        ) : (
          <Tag>未登记</Tag>
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
    return (
      <div className="admin-page">
        <PageState state="loading" description="正在加载履约方目录…" />
      </div>
    );
  }

  if (viewState === 'error') {
    const presentation = adminFailurePresentation(error, '履约方目录加载失败');
    return (
      <div className="admin-page">
        <PageState state="error" message={presentation.title} description={presentation.description} onRetry={reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="履约方配置"
        description="统一维护京东云仓与第三方履约方。缺货采购完成后，订单仍回到原履约方继续处理。"
        actions={<Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>}
      >
        <div className="admin-surface">
          <DataTable<FulfillmentProvider>
            rowKey="id"
            columns={columns}
            dataSource={rows}
            size="middle"
            scroll={{ x: 1000 }}
            pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
            emptyText={<AdminEmpty description="暂无履约方配置" />}
          />
        </div>
      </PageShell>

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
          <Form.Item
            name="wecom_group_chat_id"
            label="企微推送会话 chatid"
            extra="发货清单等推送的目标会话：从候选选择（机器人收到过消息的群 / 运营人员单聊），或手填。把机器人拉进新群并发一条消息，刷新页面即可出现在候选里。留空并保存即清除登记，改完立即生效无需重启"
            rules={[{ validator: validateGroupChatId }]}
          >
            <AutoComplete
              options={chatOptions(chatDirectory?.chats ?? [])}
              filterOption={(input, option) => {
                const value = (option as { value?: unknown } | undefined)?.value;
                return typeof value === 'string' && value.toLowerCase().includes(input.toLowerCase());
              }}
            >
              <Input placeholder="选择或输入会话 chatid（留空并保存 = 清除登记）" allowClear />
            </AutoComplete>
          </Form.Item>
          <Form.Item
            name="wecom_reminder_interval_minutes"
            label="回传提醒间隔（分钟）"
            extra="到期未收齐运单时的群提醒间隔；留空并保存 = 默认等于运单回传时限，改动只影响之后新生成的导出"
            rules={[
              {
                validator: (_rule, value: number | null | undefined) => {
                  if (value == null) return Promise.resolve();
                  if (!Number.isInteger(value) || value < 1 || value > 10080) {
                    return Promise.reject(new Error('提醒间隔必须是 1..10080 的整数分钟'));
                  }
                  return Promise.resolve();
                },
              },
            ]}
          >
            <InputNumber min={1} max={10080} style={{ width: '100%' }} placeholder="留空 = 默认等于运单回传时限" />
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
