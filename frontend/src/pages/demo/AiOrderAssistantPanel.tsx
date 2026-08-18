import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Input,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  FileSearchOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { orderAssistantApi } from '@/api/endpoints';
import type { DemoRun, OrderAssistantConfig, OrderAssistantSession } from '@/api/types';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';

const { TextArea } = Input;

const SAMPLE_ORDER =
  '上海子牧团餐需要子牧羊小腿 500g/盒 2 盒。收货人李经理，电话 13800000001，地址上海市浦东新区演示路 8 号；2026 年 8 月 15 日 16:00 前送达，月结，2026 年 8 月 31 日 18:00 结账。';

const MISSING_LABELS: Record<string, string> = {
  'customer.customer_name': '客户名称',
  'receiver.receiver_name': '收货人',
  'receiver.receiver_phone': '联系电话',
  'receiver.address': '收货地址',
  required_delivery_time: '期望送达时间',
  'settlement.settlement_method': '结账方式',
  'settlement.settlement_time': '结账时间',
  items: '商品明细',
};

function missingLabel(path: string): string {
  if (MISSING_LABELS[path]) return MISSING_LABELS[path];
  if (path.endsWith('.product_name')) return '商品名称';
  if (path.endsWith('.specification')) return '商品规格';
  if (path.endsWith('.quantity')) return '商品数量';
  if (path.endsWith('.unit')) return '商品单位';
  return path;
}

interface Props {
  onRunCreated: (run: DemoRun) => void;
}

export default function AiOrderAssistantPanel({ onRunCreated }: Props) {
  const initialized = useRef(false);
  const [config, setConfig] = useState<OrderAssistantConfig | null>(null);
  const [session, setSession] = useState<OrderAssistantSession | null>(null);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const startSession = async () => {
    setLoading(true);
    setError(null);
    try {
      const created = await orderAssistantApi.createSession();
      setSession(created);
      setInput('');
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;
    orderAssistantApi
      .config()
      .then((value) => {
        setConfig(value);
        if (value.service_ready) return startSession();
        return undefined;
      })
      .catch(setError);
  }, []);

  const send = async () => {
    if (!session || !input.trim()) return;
    setLoading(true);
    setError(null);
    try {
      setSession(await orderAssistantApi.sendMessage(session.session_id, input.trim()));
      setInput('');
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  const confirm = async () => {
    if (!session) return;
    setLoading(true);
    setError(null);
    try {
      const confirmed = await orderAssistantApi.confirm(session.session_id);
      setSession(confirmed);
      if (confirmed.order_result) {
        onRunCreated(confirmed.order_result);
        message.success('演示订单已创建');
      }
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  if (config && !config.service_ready) {
    return (
      <Alert
        type="warning"
        showIcon
        message="智能提取服务尚未配置"
        description="管理员配置兼容 OpenAI 协议的模型后即可启用；固定演示场景仍可正常使用。"
      />
    );
  }

  const draft = session?.draft;
  const previewItems = (draft?.items ?? []).map((item, index) => ({
    ...item,
    preview_key: String(index),
  }));
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {error ? (
        <Alert type="error" showIcon message="智能提取未完成" description={errorMessage(error)} />
      ) : null}

      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ flex: 1 }}>
          <Typography.Title level={5} style={{ margin: 0 }}>AI 订单提取</Typography.Title>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            自然语言仅生成订单草稿；核对并确认后才创建隔离演示订单。
          </Typography.Text>
        </div>
        <Button icon={<PlusOutlined />} onClick={startSession} loading={loading}>新订单</Button>
      </div>

      <div
        style={{
          height: 286,
          overflowY: 'auto',
          padding: '14px 16px',
          border: '1px solid #e4e8ee',
          borderRadius: 8,
          background: '#f8fafc',
        }}
      >
        {session?.messages.length ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            {session.messages.map((entry, index) => (
              <div
                key={`${entry.role}-${index}`}
                style={{ display: 'flex', justifyContent: entry.role === 'user' ? 'flex-end' : 'flex-start' }}
              >
                <div
                  style={{
                    maxWidth: '86%',
                    padding: '8px 11px',
                    borderRadius: 8,
                    color: entry.role === 'user' ? '#fff' : '#1c2230',
                    background: entry.role === 'user' ? '#2563eb' : '#fff',
                    border: entry.role === 'user' ? 'none' : '1px solid #e4e8ee',
                    whiteSpace: 'pre-wrap',
                    lineHeight: 1.6,
                  }}
                >
                  {entry.content}
                </div>
              </div>
            ))}
          </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在建立订单会话" />
        )}
      </div>

      <Space.Compact style={{ width: '100%' }}>
        <TextArea
          value={input}
          onChange={(event) => setInput(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              void send();
            }
          }}
          disabled={!session || session.status === 'CONFIRMED'}
          autoSize={{ minRows: 2, maxRows: 5 }}
          placeholder="描述客户、收货、商品、交付和结账信息"
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={loading}
          disabled={!session || !input.trim() || session.status === 'CONFIRMED'}
          onClick={send}
          style={{ height: 'auto' }}
        >
          发送
        </Button>
      </Space.Compact>
      <Typography.Link onClick={() => setInput(SAMPLE_ORDER)} style={{ fontSize: 12 }}>
        填入完整演示订单示例
      </Typography.Link>

      <div style={{ borderTop: '1px solid #e4e8ee', paddingTop: 16 }}>
        <Space align="center" style={{ marginBottom: 12 }}>
          <FileSearchOutlined style={{ color: '#2563eb' }} />
          <Typography.Text strong>结构化订单预览</Typography.Text>
          {session?.status === 'READY_TO_CONFIRM' ? <Tag color="blue">待确认</Tag> : null}
          {session?.status === 'CONFIRMED' ? <Tag color="green">已创建演示订单</Tag> : null}
        </Space>

        {session?.missing_fields.length ? (
          <Alert
            type="warning"
            showIcon
            message="仍需补充"
            description={<Space wrap>{session.missing_fields.map((field) => <Tag key={field}>{missingLabel(field)}</Tag>)}</Space>}
            style={{ marginBottom: 12 }}
          />
        ) : null}

        <Descriptions
          size="small"
          column={{ xs: 1, md: 2 }}
          items={[
            { key: 'customer', label: '客户', children: draft?.customer.customer_name || '—' },
            { key: 'receiver', label: '收货人', children: draft?.receiver.receiver_name || '—' },
            { key: 'phone', label: '联系电话', children: draft?.receiver.receiver_phone || '—' },
            { key: 'address', label: '收货地址', children: draft?.receiver.address || '—', span: 2 },
            { key: 'delivery', label: '期望送达', children: draft?.required_delivery_time || '—' },
            { key: 'settlement', label: '结账', children: draft?.settlement.settlement_method || '—' },
          ]}
        />
        <Table
          rowKey="preview_key"
          size="small"
          pagination={false}
          dataSource={previewItems}
          locale={{ emptyText: '尚未提取商品明细' }}
          columns={[
            { title: '商品 / SKU', key: 'product', render: (_, item) => <ProductIdentity name={item.product_name} code={item.sku_code} /> },
            { title: '规格', dataIndex: 'specification', width: 130 },
            { title: '数量', dataIndex: 'quantity', width: 80, align: 'right' },
            { title: '单位', dataIndex: 'unit', width: 70 },
          ]}
          style={{ marginTop: 8 }}
        />
        <Button
          type="primary"
          icon={<CheckCircleOutlined />}
          block
          loading={loading}
          disabled={session?.status !== 'READY_TO_CONFIRM'}
          onClick={confirm}
          style={{ marginTop: 14 }}
        >
          核对无误，创建演示订单
        </Button>
      </div>
    </Space>
  );
}
