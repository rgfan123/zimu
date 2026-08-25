/**
 * 主数据 · 礼包管理（静态礼包 BOM：GET/POST /api/v1/product-bundles，PATCH /api/v1/product-bundles/{id}）。
 * 礼包 = 商品族属性 + 组件清单（bundle_items：SKU + 单份用量）；
 * 礼包本身不建 SKU、不计库存；订单命中时下单快照 BOM，主数据修改不影响历史订单。
 */

import { useCallback, useEffect, useState } from 'react';
import { App as AntApp, Button, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { productBundlesApi } from '@/api/endpoints';
import type { MasterDataPage, MasterDataRecord } from '@/api/types';
import { AdminEmpty, AdminFailureAlert, AdminLoading, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { adminPageState } from '@/pages/shared/adminVisual';
import { useCategoryOptions, useSkuOptions } from './masterOptions';
import { attr } from '@/pages/shared/MasterDataCrud';
import '@/pages/shared/adminSurface.css';

interface BundleItemView {
  sku_id?: string;
  quantity_per_bundle?: string;
  emg_code_snapshot?: string;
  source_text_snapshot?: string;
  sku_code?: string;
  specification?: string;
  unit?: string;
  product_name?: string;
}

const STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿（不可被订单命中）' },
  { value: 'ACTIVE', label: '启用（可识别命中）' },
  { value: 'INACTIVE', label: '下架' },
];

const itemsOf = (record: MasterDataRecord): BundleItemView[] => {
  const raw = attr(record, 'items');
  return Array.isArray(raw) ? (raw as BundleItemView[]) : [];
};

export default function BundlesPage() {
  const { message: messageApi } = AntApp.useApp();
  const categoryOptions = useCategoryOptions();
  const categoryLabels = new Map(categoryOptions.map(({ value, label }) => [String(value), label]));
  const skuOptions = useSkuOptions();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [query, setQuery] = useState('');
  const [data, setData] = useState<MasterDataPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<MasterDataRecord | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const fetchPage = useCallback(() => {
    setLoading(true);
    setError(null);
    productBundlesApi
      .list({ page, size, query: query.trim() || undefined })
      .then((res) => {
        setData(res);
        setLoading(false);
      })
      .catch((e: Error) => {
        setError(e);
        setData(null);
        setLoading(false);
      });
  }, [page, size, query]);

  useEffect(() => {
    fetchPage();
  }, [fetchPage, tick]);

  const reload = () => {
    setData(null);
    setError(null);
    setLoading(true);
    setTick((value) => value + 1);
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ items: [{ quantity_per_bundle: 1 }], status: 'DRAFT' });
    setCreateOpen(true);
  };

  const openEdit = (record: MasterDataRecord) => {
    form.resetFields();
    form.setFieldsValue({
      bundle_name: attr(record, 'bundle_name') ?? record.name,
      category_id: attr(record, 'category_id') ?? undefined,
      barcode: attr(record, 'barcode') ?? undefined,
      description: attr(record, 'description') ?? undefined,
      tax_rate: attr(record, 'tax_rate') ?? undefined,
      settlement_cost: attr(record, 'settlement_cost') ?? undefined,
      status: attr(record, 'status') ?? 'DRAFT',
      items: itemsOf(record).map((item) => ({
        sku_id: item.sku_id,
        quantity_per_bundle: item.quantity_per_bundle,
        emg_code_snapshot: item.emg_code_snapshot,
        source_text_snapshot: item.source_text_snapshot,
      })),
    });
    setEditing(record);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const body = {
        bundle_name: values.bundle_name as string,
        category_id: values.category_id as string | undefined,
        barcode: (values.barcode as string | undefined) || undefined,
        description: (values.description as string | undefined) || undefined,
        tax_rate: values.tax_rate == null ? undefined : String(values.tax_rate),
        settlement_cost: values.settlement_cost == null ? undefined : String(values.settlement_cost),
        status: (values.status ?? 'DRAFT') as 'DRAFT' | 'ACTIVE' | 'INACTIVE',
        items: (values.items ?? []).map((item: Record<string, unknown>) => ({
          sku_id: String(item.sku_id),
          quantity_per_bundle: String(item.quantity_per_bundle),
          emg_code_snapshot: (item.emg_code_snapshot as string | undefined) || undefined,
          source_text_snapshot: (item.source_text_snapshot as string | undefined) || undefined,
        })),
      };
      if (editing) {
        await productBundlesApi.update(editing.id, { expected_version: editing.version, ...body });
        messageApi.success('已保存');
      } else {
        await productBundlesApi.create({ bundle_code: values.bundle_code as string, ...body });
        messageApi.success('已创建');
      }
      setCreateOpen(false);
      setEditing(null);
      form.resetFields();
      reload();
    } catch (e) {
      if (e instanceof Error) messageApi.error(errorMessage(e));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<MasterDataRecord> = [
    { title: '礼包编码', dataIndex: 'code', width: 120 },
    { title: '礼包名称', dataIndex: 'name', width: 200 },
    {
      title: '条码',
      key: 'barcode',
      width: 130,
      render: (_, r) => (attr(r, 'barcode') ? String(attr(r, 'barcode')) : '—'),
    },
    {
      title: '品类',
      key: 'category',
      width: 140,
      render: (_, r) => {
        const id = attr(r, 'category_id');
        if (!id) return '—';
        return <Tag style={{ marginInlineEnd: 0 }}>{categoryLabels.get(String(id)) ?? '—'}</Tag>;
      },
    },
    {
      title: '组件',
      key: 'items',
      width: 70,
      align: 'right',
      render: (_, r) => itemsOf(r).length,
    },
    {
      title: '税率',
      key: 'tax_rate',
      width: 80,
      align: 'right',
      render: (_, r) => (attr(r, 'tax_rate') == null ? '—' : `${attr(r, 'tax_rate')}%`),
    },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (_, r) => <AdminStatusTag status={attr(r, 'status') === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE'} />,
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right' as const,
      render: (_, r) => <Typography.Link onClick={() => openEdit(r)}>编辑</Typography.Link>,
    },
  ];

  const viewState = adminPageState(loading, error, Boolean(data?.items.length));

  return (
    <div className="admin-crud">
      <div className="admin-toolbar">
        <Space>
          <Input.Search
            allowClear
            placeholder="搜索礼包编码 / 名称"
            style={{ width: 260 }}
            onSearch={(value) => {
              setPage(0);
              setQuery(value);
            }}
          />
        </Space>
        <div className="admin-toolbar__spacer" />
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建礼包
        </Button>
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      </div>

      {viewState === 'loading' ? (
        <div style={{ marginTop: 16 }}>
          <AdminLoading description="正在加载礼包…" />
        </div>
      ) : viewState === 'error' ? (
        <div style={{ marginTop: 16 }}>
          <AdminFailureAlert error={error} title="数据加载失败" onRetry={reload} />
        </div>
      ) : (
        <div className="admin-surface" style={{ marginTop: 16 }}>
          <Table<MasterDataRecord>
            rowKey="id"
            columns={columns}
            dataSource={data?.items ?? []}
            size="middle"
            scroll={{ x: 900 }}
            locale={{ emptyText: <AdminEmpty description="暂无礼包，点击「新建礼包」创建" /> }}
            pagination={{
              current: page + 1,
              pageSize: size,
              total: data?.total_elements ?? 0,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total) => `共 ${total} 条`,
              onChange: (p, s) => {
                setPage(p - 1);
                setSize(s);
              },
            }}
          />
        </div>
      )}

      <Modal
        title={editing ? `编辑礼包：${editing.name}` : '新建礼包'}
        open={createOpen || !!editing}
        onCancel={() => {
          setCreateOpen(false);
          setEditing(null);
          form.resetFields();
        }}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okButtonProps={{ disabled: submitting }}
        cancelButtonProps={{ disabled: submitting }}
        width={720}
        destroyOnHidden
        forceRender
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          {!editing ? (
            <Form.Item
              name="bundle_code"
              label="礼包编码"
              rules={[{ required: true, message: '请输入礼包编码' }]}
            >
              <Input placeholder="如 BUNDLE-001 / 商品条码" />
            </Form.Item>
          ) : null}
          <Form.Item name="bundle_name" label="礼包名称" rules={[{ required: true, message: '请输入礼包名称' }]}>
            <Input placeholder="如 牛肉大礼包5200g" />
          </Form.Item>
          <Form.Item name="category_id" label="品类">
            <Select allowClear placeholder="选择品类" options={categoryOptions} />
          </Form.Item>
          <Space size={16} style={{ width: '100%' }} align="start">
            <Form.Item name="barcode" label="条码" style={{ width: 220 }}>
              <Input placeholder="13 位 EAN 条码" />
            </Form.Item>
            <Form.Item name="tax_rate" label="税率（%）" style={{ width: 140 }}>
              <InputNumber min={0} max={100} style={{ width: '100%' }} placeholder="如 9" />
            </Form.Item>
            <Form.Item name="settlement_cost" label="结算成本（元）" style={{ width: 160 }}>
              <InputNumber min={0} precision={2} style={{ width: '100%' }} placeholder="如 398.06" />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ width: 180 }}>
              <Select options={STATUS_OPTIONS} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="选填" />
          </Form.Item>

          <Typography.Title level={5} style={{ marginTop: 8 }}>
            组件清单（礼包内配）
          </Typography.Title>
          <Form.List name="items">
            {(fields, { add, remove }) => (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                {fields.map((field) => (
                  <Space key={field.key} align="start" size={8} style={{ width: '100%' }}>
                    <Form.Item
                      {...field}
                      name={[field.name, 'sku_id']}
                      rules={[{ required: true, message: '选择组件 SKU' }]}
                      style={{ width: 320, marginBottom: 0 }}
                    >
                      <Select
                        showSearch
                        optionFilterProp="label"
                        placeholder="选择内部 SKU"
                        options={skuOptions.map((option) => ({
                          value: option.value,
                          label: `${option.label}（${option.value}）`,
                        }))}
                      />
                    </Form.Item>
                    <Form.Item
                      {...field}
                      name={[field.name, 'quantity_per_bundle']}
                      rules={[{ required: true, message: '单份用量' }]}
                      style={{ width: 110, marginBottom: 0 }}
                    >
                      <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="单份用量" />
                    </Form.Item>
                    <Form.Item
                      {...field}
                      name={[field.name, 'emg_code_snapshot']}
                      style={{ width: 160, marginBottom: 0 }}
                    >
                      <Input placeholder="EMG 编码（选填）" />
                    </Form.Item>
                    <Form.Item
                      {...field}
                      name={[field.name, 'source_text_snapshot']}
                      style={{ width: 200, marginBottom: 0 }}
                    >
                      <Input placeholder="内配原文（选填）" />
                    </Form.Item>
                    <Button danger type="text" onClick={() => remove(field.name)}>
                      删除
                    </Button>
                  </Space>
                ))}
                <Button type="dashed" onClick={() => add({ quantity_per_bundle: 1 })} block icon={<PlusOutlined />}>
                  添加组件
                </Button>
              </Space>
            )}
          </Form.List>
        </Form>
      </Modal>
    </div>
  );
}
