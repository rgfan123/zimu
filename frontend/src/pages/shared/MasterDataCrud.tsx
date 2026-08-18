/**
 * 主数据通用 CRUD 页面骨架：服务端分页表格 + 新建/编辑弹窗 + 筛选区。
 * 主数据板块五个页面（品类/商品/Internal SKU/来源映射/履约方映射）共用；
 * 字段与列由各页配置，写操作严格按 openapi Write/Patch 载荷（expected_version 乐观锁）。
 */

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { App as AntApp, Button, Form, Input, Modal, Select, Space, Switch, Table, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import type { MasterDataPage, MasterDataRecord } from '@/api/types';
import { AdminEmpty, AdminFailureAlert, AdminLoading, AdminStatusTag } from './AdminVisualComponents';
import { adminPageState } from './adminVisual';
import './adminSurface.css';

export interface CrudField {
  name: string;
  label: string;
  required?: boolean;
  /** text | select | switch；数量类字段（乘数等）用 text + pattern 校验 */
  type?: 'text' | 'select' | 'switch';
  options?: { value: string | number | boolean; label: string }[];
  placeholder?: string;
  pattern?: RegExp;
  patternMessage?: string;
}

export interface MasterDataCrudProps {
  /** 表格上方筛选区（页面自定义，如渠道/履约方 Select） */
  filters?: ReactNode;
  /** 请求额外查询参数（筛选联动，变化时回到第一页） */
  extraQuery?: Record<string, string | undefined>;
  fetchPage: (query: { page: number; size: number }) => Promise<MasterDataPage>;
  create?: (values: Record<string, unknown>) => Promise<unknown>;
  update?: (id: string, values: Record<string, unknown>) => Promise<unknown>;
  columns: ColumnsType<MasterDataRecord>;
  createFields?: CrudField[];
  updateFields?: CrudField[];
  pageSizeOptions?: number[];
}

/** 读取 MasterDataRecord 的展示字段：直属字段优先，否则取 attributes（openapi 附加属性）。 */
export function attr(record: MasterDataRecord, key: string): unknown {
  if (key in record) return (record as unknown as Record<string, unknown>)[key];
  return record.attributes?.[key];
}

function fieldControl(field: CrudField) {
  if (field.type === 'select') {
    return (
      <Select
        placeholder={field.placeholder ?? `请选择${field.label}`}
        options={field.options}
        allowClear={!field.required}
      />
    );
  }
  if (field.type === 'switch') return <Switch />;
  return <Input placeholder={field.placeholder ?? `请输入${field.label}`} />;
}

export default function MasterDataCrud({
  filters,
  extraQuery = {},
  fetchPage,
  create,
  update,
  columns,
  createFields = [],
  updateFields = [],
  pageSizeOptions = [10, 20, 50],
}: MasterDataCrudProps) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [data, setData] = useState<MasterDataPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [settledQueryKey, setSettledQueryKey] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<MasterDataRecord | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const { message: messageApi } = AntApp.useApp();

  const query = { ...extraQuery, page, size };
  const queryKey = JSON.stringify(query);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setData(null);
    fetchPage(query)
      .then((res) => {
        if (!cancelled) {
          setData(res);
          setSettledQueryKey(queryKey);
          setLoading(false);
        }
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setData(null);
          setError(e);
          setSettledQueryKey(queryKey);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchPage, queryKey, tick]);

  const reload = useCallback(() => {
    setData(null);
    setError(null);
    setLoading(true);
    setTick((t) => t + 1);
  }, []);

  const openCreate = () => {
    setEditing(null);
    setCreateOpen(true);
  };

  const openEdit = (record: MasterDataRecord) => {
    const values: Record<string, unknown> = {};
    for (const f of updateFields) {
      values[f.name] = f.name === 'active' ? record.active : attr(record, f.name);
    }
    form.setFieldsValue(values);
    setEditing(record);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editing) {
        await update?.(editing.id, { expected_version: editing.version, ...values });
        messageApi.success('已保存');
      } else if (create) {
        await create(values);
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

  const renderFields = (fields: CrudField[]) => (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      {fields.map((f) => (
        <Form.Item
          key={f.name}
          name={f.name}
          label={f.label}
          valuePropName={f.type === 'switch' ? 'checked' : 'value'}
          rules={[
            { required: f.required, message: `请${f.type === 'select' ? '选择' : '填写'}${f.label}` },
            ...(f.pattern ? [{ pattern: f.pattern, message: f.patternMessage ?? `${f.label}格式不正确` }] : []),
          ]}
        >
          {fieldControl(f)}
        </Form.Item>
      ))}
    </Space>
  );

  const mergedColumns: ColumnsType<MasterDataRecord> = [
    ...columns,
    {
      title: '状态',
      dataIndex: 'active',
      width: 80,
      render: (v: boolean) => <AdminStatusTag status={v ? 'ACTIVE' : 'INACTIVE'} />,
    },
    ...(update
      ? [
          {
            title: '操作',
            key: 'action',
            width: 90,
            fixed: 'right' as const,
            render: (_: unknown, record: MasterDataRecord) => (
              <Typography.Link onClick={() => openEdit(record)}>编辑</Typography.Link>
            ),
          },
        ]
      : []),
  ];

  const viewState = adminPageState(
    loading || settledQueryKey !== queryKey,
    error,
    Boolean(data?.items.length),
  );

  const stateContent = viewState === 'loading'
    ? <AdminLoading description="正在加载主数据…" />
    : viewState === 'error'
      ? <AdminFailureAlert error={error} title="数据加载失败" onRetry={reload} />
      : null;

  return stateContent ?? (
    <div className="admin-crud">
          <div className="admin-toolbar">
            {filters}
            <div className="admin-toolbar__spacer" />
            {create ? (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建
              </Button>
            ) : null}
            <Button icon={<ReloadOutlined />} onClick={reload}>
              刷新
            </Button>
          </div>

          <div className="admin-surface" style={{ marginTop: 16 }}>
            <Table<MasterDataRecord>
              rowKey="id"
              columns={mergedColumns}
              dataSource={data?.items ?? []}
              size="middle"
              scroll={{ x: 860 }}
              locale={{ emptyText: <AdminEmpty description="暂无主数据" /> }}
              pagination={{
                current: page + 1,
                pageSize: size,
                total: data?.total_elements ?? 0,
                showSizeChanger: true,
                pageSizeOptions,
                showTotal: (total) => `共 ${total} 条`,
                onChange: (p, s) => {
                  setPage(p - 1);
                  setSize(s);
                },
              }}
            />
          </div>

          <Modal
            title={editing ? '编辑' : '新建'}
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
            width={480}
            destroyOnHidden
            forceRender
          >
            <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
              {renderFields(editing ? updateFields : createFields)}
            </Form>
          </Modal>
    </div>
  );
}
