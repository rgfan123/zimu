import { useCallback, useEffect, useState } from 'react';
import {
  App as AntApp,
  Button,
  Drawer,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, GiftOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { productBundlesApi, skusApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type {
  ProductBundleCreateInput,
  ProductBundleItem,
  ProductBundlePage,
  ProductBundleRecord,
  ProductBundleStatus,
  SkuRecord,
} from '@/api/types';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';

const STATUS_LABELS: Record<ProductBundleStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '启用',
  INACTIVE: '下架',
};

const STATUS_OPTIONS = (Object.keys(STATUS_LABELS) as ProductBundleStatus[]).map((value) => ({
  value,
  label: STATUS_LABELS[value],
}));

/** 组件 SKU 选择的分页大小（每页 200，按 total_pages 跨页取全）。 */
const SKU_PAGE_SIZE = 200;

function itemName(item: ProductBundleItem): string {
  return item.product_name || item.source_text_snapshot || item.sku_code || '未命名组件';
}

/** 取全部内部 SKU（跨页取全，避免组件清单选择被分页截断）。 */
async function fetchAllSkus(): Promise<SkuRecord[]> {
  const first = await skusApi.list({ page: 0, size: SKU_PAGE_SIZE });
  if (first.total_pages <= 1) return first.items;
  const rest = await Promise.all(
    Array.from({ length: first.total_pages - 1 }, (_, page) => skusApi.list({ page: page + 1, size: SKU_PAGE_SIZE })),
  );
  return [first, ...rest].flatMap((page) => page.items);
}

export default function BundlesPage() {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [data, setData] = useState<ProductBundlePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [selected, setSelected] = useState<ProductBundleRecord | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [skuLoading, setSkuLoading] = useState(false);
  const [skuRecords, setSkuRecords] = useState<SkuRecord[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await productBundlesApi.list({ page, size }));
    } catch (reason) {
      setData(null);
      setError(reason);
    } finally {
      setLoading(false);
    }
  }, [page, size]);

  useEffect(() => {
    void load();
  }, [load, reloadKey]);

  const openCreate = async () => {
    form.resetFields();
    form.setFieldsValue({ status: 'DRAFT', items: [{ quantity_per_bundle: 1 }] });
    setCreateOpen(true);
    setSkuLoading(true);
    try {
      setSkuRecords(await fetchAllSkus());
    } catch (reason) {
      setSkuRecords([]);
      message.error(errorMessage(reason));
    } finally {
      setSkuLoading(false);
    }
  };

  const submitCreate = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const body: ProductBundleCreateInput = {
        bundle_code: String(values.bundle_code).trim(),
        bundle_name: String(values.bundle_name).trim(),
        barcode: values.barcode ? String(values.barcode).trim() : undefined,
        description: values.description ? String(values.description).trim() : undefined,
        status: values.status ?? 'DRAFT',
        items: values.items.map((item: { sku_id: string; quantity_per_bundle: number; emg_code_snapshot?: string }) => ({
          sku_id: String(item.sku_id),
          quantity_per_bundle: String(item.quantity_per_bundle),
          emg_code_snapshot: item.emg_code_snapshot ? String(item.emg_code_snapshot).trim() : undefined,
        })),
      };
      await productBundlesApi.create(body);
      message.success('静态礼包已创建');
      setCreateOpen(false);
      form.resetFields();
      setPage(0);
      setReloadKey((value) => value + 1);
    } catch (reason) {
      if (reason instanceof Error) message.error(errorMessage(reason));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<ProductBundleRecord> = [
    {
      title: '静态礼包',
      key: 'identity',
      width: 260,
      render: (_, record) => <ProductIdentity name={record.name} code={record.code} />,
    },
    {
      title: '条码',
      key: 'barcode',
      width: 160,
      render: (_, record) => String(record.attributes.barcode || '—'),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_, record) => {
        const status = record.attributes.status;
        return <Tag color={status === 'ACTIVE' ? 'success' : undefined}>{STATUS_LABELS[status]}</Tag>;
      },
    },
    {
      title: '组件',
      key: 'items',
      width: 120,
      render: (_, record) => `${record.attributes.items.length} 个组件`,
    },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_, record) => (
        <Button type="link" onClick={() => setSelected(record)}>组件清单</Button>
      ),
    },
  ];

  const selectedItems = selected?.attributes.items ?? [];

  return (
    <PageShell
      title="静态礼包"
      description="维护静态礼包及其当前组件清单；礼包本身不建 SKU、不单独计库存。"
      icon={<GiftOutlined />}
      actions={(
        <>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => void openCreate()}>
            新建静态礼包
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => setReloadKey((value) => value + 1)}>
            刷新
          </Button>
        </>
      )}
    >
      <DataTable<ProductBundleRecord>
        rowKey="id"
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        error={error}
        onRetry={() => setReloadKey((value) => value + 1)}
        errorTitle="静态礼包列表加载失败"
        emptyText="暂无静态礼包"
        pagination={{
          current: page + 1,
          pageSize: size,
          total: data?.total_elements ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [20, 50, 100],
          showTotal: (total) => `共 ${total} 个静态礼包`,
          onChange: (nextPage, nextSize) => {
            setPage(nextPage - 1);
            setSize(nextSize);
          },
        }}
      />

      <Drawer
        title={selected ? `${selected.name} · 组件清单` : '组件清单'}
        open={selected !== null}
        width={560}
        onClose={() => setSelected(null)}
      >
        <List
          dataSource={selectedItems}
          locale={{ emptyText: '暂无组件' }}
          renderItem={(item, index) => (
            <List.Item>
              <List.Item.Meta
                title={`${index + 1}. ${itemName(item)}`}
                description={(
                  <Space direction="vertical" size={2}>
                    <Typography.Text type="secondary">
                      {item.sku_code || `SKU ${item.sku_id || '—'}`}
                      {item.specification ? ` · ${item.specification}` : ''}
                    </Typography.Text>
                    <Typography.Text>× {item.quantity_per_bundle || '—'} {item.unit || ''}</Typography.Text>
                    {item.emg_code_snapshot ? (
                      <Typography.Text type="secondary">EMG：{item.emg_code_snapshot}</Typography.Text>
                    ) : null}
                  </Space>
                )}
              />
            </List.Item>
          )}
        />
      </Drawer>

      <Modal
        title="新建静态礼包"
        open={createOpen}
        width={760}
        confirmLoading={submitting}
        okText="创建"
        onOk={() => void submitCreate()}
        onCancel={() => {
          if (submitting) return;
          setCreateOpen(false);
          form.resetFields();
        }}
        cancelButtonProps={{ disabled: submitting }}
        destroyOnHidden
        forceRender
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Space size={16} align="start" style={{ width: '100%' }}>
            <Form.Item
              name="bundle_code"
              label="静态礼包编码"
              rules={[{ required: true, whitespace: true, message: '请输入静态礼包编码' }]}
              style={{ width: 280 }}
            >
              <Input placeholder="如 BUNDLE-NEW-YEAR" />
            </Form.Item>
            <Form.Item
              name="bundle_name"
              label="静态礼包名称"
              rules={[{ required: true, whitespace: true, message: '请输入静态礼包名称' }]}
              style={{ width: 360 }}
            >
              <Input placeholder="如 新年牛羊肉礼包" />
            </Form.Item>
          </Space>
          <Space size={16} align="start" style={{ width: '100%' }}>
            <Form.Item name="barcode" label="条码" style={{ width: 280 }}>
              <Input placeholder="选填" />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ width: 220 }}>
              <Select options={STATUS_OPTIONS} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="选填" />
          </Form.Item>

          <Typography.Title level={5} style={{ marginBottom: 4 }}>
            组件清单
          </Typography.Title>
          <Typography.Text type="secondary">
            静态礼包至少需要一个组件，同一内部 SKU 只能出现一次。
          </Typography.Text>
          <Form.List
            name="items"
            rules={[{
              validator: async (_, items) => {
                if (!items?.length) throw new Error('静态礼包至少需要一个组件');
              },
            }]}
          >
            {(fields, { add, remove }, { errors }) => (
              <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 12 }}>
                {fields.map((field) => {
                  const { key, ...fieldProps } = field;
                  return (
                    <Space key={key} align="start" size={8} style={{ width: '100%' }}>
                      <Form.Item
                        {...fieldProps}
                        name={[field.name, 'sku_id']}
                        rules={[{ required: true, message: '请选择内部 SKU' }]}
                        style={{ width: 330, marginBottom: 0 }}
                      >
                        <Select
                          showSearch
                          loading={skuLoading}
                          optionFilterProp="label"
                          placeholder="选择内部 SKU"
                          options={skuRecords.map((sku) => ({
                            value: sku.id,
                            label: `${sku.name}（${sku.code} · ${String(sku.attributes.specification || '无规格')}）`,
                          }))}
                        />
                      </Form.Item>
                      <Form.Item
                        {...fieldProps}
                        name={[field.name, 'quantity_per_bundle']}
                        rules={[{ required: true, message: '请输入单份用量' }]}
                        style={{ width: 130, marginBottom: 0 }}
                      >
                        <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="单份用量" />
                      </Form.Item>
                      <Form.Item
                        {...fieldProps}
                        name={[field.name, 'emg_code_snapshot']}
                        style={{ width: 170, marginBottom: 0 }}
                      >
                        <Input placeholder="EMG（选填）" />
                      </Form.Item>
                      <Button
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        aria-label="删除组件"
                        disabled={fields.length === 1}
                        onClick={() => remove(field.name)}
                      />
                    </Space>
                  );
                })}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ quantity_per_bundle: 1 })} block>
                  添加组件
                </Button>
                <Form.ErrorList errors={errors} />
              </Space>
            )}
          </Form.List>
        </Form>
      </Modal>
    </PageShell>
  );
}
