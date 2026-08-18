/** 主数据 · SKU 映射矩阵：内部 SKU 为行，履约方为动态列。 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App as AntApp,
  Alert,
  Button,
  Collapse,
  Descriptions,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  Upload,
  theme,
} from 'antd';
import { CheckOutlined, FileSearchOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { attr } from '@/pages/shared/MasterDataCrud';
import { AdminEmpty, AdminFailureAlert, AdminLoading, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { providerSkuMappingReferencesApi, providerSkuMappingsApi, providersApi, skusApi, sourceSkuMappingsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { CHANNEL_LABELS } from '@/constants/labels';
import type { JdPiecesCandidate, MasterDataRecord, ProviderSkuReferencePreview, ProviderSkuReferenceRow } from '@/api/types';
import { useSkuOptions } from './masterOptions';
import { canConfirmReferenceRow } from '@/pages/fulfillment/fileOperations';
import {
  SOURCE_MAPPING_CHANNELS,
  buildSourceSkuMappingMatrix,
  internalSkuPresentation,
  sourceMappingPresentation,
  type SourceMappingMatrixChannel,
  type SourceSkuMappingMatrixRow,
} from './skuMappingMatrix';
import './skuMappings.css';

function ReferencePreviewPanel() {
  const { message: messageApi } = AntApp.useApp();
  const skuOptions = useSkuOptions();
  const [referenceFile, setReferenceFile] = useState<File | null>(null);
  const [sourceFile, setSourceFile] = useState<File | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [preview, setPreview] = useState<ProviderSkuReferencePreview | null>(null);
  const [selected, setSelected] = useState<ProviderSkuReferenceRow | null>(null);
  const [skuId, setSkuId] = useState<string>();
  const [confirming, setConfirming] = useState(false);

  const runPreview = async () => {
    if (!referenceFile || !sourceFile) {
      messageApi.warning('请分别选择 SKU 映射资料和来源订单样表');
      return;
    }
    setPreviewing(true);
    try {
      setPreview(await providerSkuMappingReferencesApi.preview(referenceFile, sourceFile));
      messageApi.success('映射资料核对完成');
    } catch (error) {
      messageApi.error(errorMessage(error));
    } finally {
      setPreviewing(false);
    }
  };

  const confirmMapping = async () => {
    if (!selected || !skuId || !canConfirmReferenceRow(selected)) return;
    setConfirming(true);
    try {
      const [providers, providerMappings, sourceMappings] = await Promise.all([
        providersApi.list(),
        providerSkuMappingsApi.list({ page: 0, size: 200 }),
        sourceSkuMappingsApi.list({ page: 0, size: 200, source_channel: preview?.source_channel }),
      ]);
      const jd = providers.find((provider) => provider.provider_type === 'JD_WAREHOUSE');
      if (!jd) {
        messageApi.error('尚未配置京东仓履约方');
        return;
      }
      const existingProvider = providerMappings.items.find(
        (mapping) => attr(mapping, 'provider_sku_code') === selected.provider_sku_code,
      );
      if (existingProvider && String(attr(existingProvider, 'sku_id')) !== skuId) {
        messageApi.error('该京东商品编号已绑定其他内部 SKU，请先核对现有映射');
        return;
      }
      const existingProviderForSku = providerMappings.items.find(
        (mapping) => String(attr(mapping, 'provider_id')) === jd.id && String(attr(mapping, 'sku_id')) === skuId,
      );
      if (existingProviderForSku && attr(existingProviderForSku, 'provider_sku_code') !== selected.provider_sku_code) {
        messageApi.error('该内部 SKU 已绑定其他京东商品编号，请先核对现有映射');
        return;
      }
      const existingSource = sourceMappings.items.find(
        (mapping) => attr(mapping, 'source_sku_ref') === selected.source_sku_ref,
      );
      if (existingSource) {
        messageApi.error('该来源商品编号已有映射，请在来源映射列表中核对后编辑');
        return;
      }
      if (!existingProvider) {
        await providerSkuMappingsApi.create({
          provider_id: jd.id,
          sku_id: skuId,
          provider_sku_code: selected.provider_sku_code!,
          provider_sku_name: selected.provider_sku_name,
          active: true,
        });
      }
      await sourceSkuMappingsApi.create({
        source_channel: preview!.source_channel,
        source_sku_ref: selected.source_sku_ref,
        source_sku_name: selected.source_product_name,
        sku_id: skuId,
        quantity_multiplier: String(selected.quantity_multiplier),
        active: true,
      });
      messageApi.success('来源与京东 SKU 映射已确认');
      setSelected(null);
      setSkuId(undefined);
    } catch (error) {
      messageApi.error(errorMessage(error));
    } finally {
      setConfirming(false);
    }
  };

  const columns: ColumnsType<ProviderSkuReferenceRow> = [
    {
      title: '来源商品',
      key: 'source_product',
      width: 250,
      render: (_, row) => <ProductIdentity name={row.source_product_name} code={row.source_sku_ref} />,
    },
    { title: '来源数量', dataIndex: 'source_quantity', width: 90, align: 'right' },
    {
      title: '京东商品',
      key: 'provider_product',
      width: 250,
      render: (_, row) => <ProductIdentity name={row.provider_sku_name} code={row.provider_sku_code} />,
    },
    { title: '包装乘数', dataIndex: 'quantity_multiplier', width: 90, align: 'right', render: (value?: string | number) => value ?? '—' },
    {
      title: '核对结果',
      dataIndex: 'match_status',
      width: 110,
      render: (value: ProviderSkuReferenceRow['match_status']) => <AdminStatusTag status={value} />,
    },
    { title: '核对说明', dataIndex: 'reason', width: 220, ellipsis: true },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, row) => (
        <Button
          type="link"
          size="small"
          icon={<CheckOutlined />}
          disabled={!canConfirmReferenceRow(row)}
          onClick={() => {
            setSelected(row);
            setSkuId(undefined);
          }}
        >
          确认
        </Button>
      ),
    },
  ];

  return (
    <>
      <Space className="sku-mapping-reference-panel" direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="映射资料只用于核对来源商品与京东商品编号，不作为订单模板。预览不会写入主数据，必须逐条人工确认。"
        />
        <Space wrap size={[10, 10]}>
          <Upload accept=".xlsx" maxCount={1} showUploadList={false} beforeUpload={(file) => { setReferenceFile(file); setPreview(null); return false; }}>
            <Button>选择 SKU 映射资料</Button>
          </Upload>
          <Typography.Text type="secondary">{referenceFile?.name ?? '尚未选择映射资料'}</Typography.Text>
          <Upload accept=".xlsx,.csv" maxCount={1} showUploadList={false} beforeUpload={(file) => { setSourceFile(file); setPreview(null); return false; }}>
            <Button>选择来源订单样表</Button>
          </Upload>
          <Typography.Text type="secondary">{sourceFile?.name ?? '尚未选择来源订单样表'}</Typography.Text>
          <Button type="primary" icon={<FileSearchOutlined />} loading={previewing} onClick={runPreview}>开始核对</Button>
        </Space>
        {preview ? (
          <>
            <Descriptions size="small" bordered column={4}>
              <Descriptions.Item label="来源渠道">{CHANNEL_LABELS[preview.source_channel]}</Descriptions.Item>
              <Descriptions.Item label="来源行">{preview.summary.total}</Descriptions.Item>
              <Descriptions.Item label="可确认">{preview.summary.matched}</Descriptions.Item>
              <Descriptions.Item label="待复核/冲突">{preview.summary.need_review + preview.summary.conflict}</Descriptions.Item>
              <Descriptions.Item label="京东商品编号">{preview.reference_quality.provider_sku_count}</Descriptions.Item>
              <Descriptions.Item label="组合候选">{preview.reference_quality.bundle_count}</Descriptions.Item>
              <Descriptions.Item label="重复编号">{preview.reference_quality.duplicate_provider_codes}</Descriptions.Item>
              <Descriptions.Item label="归属不明确行">{preview.reference_quality.ambiguous_bundle_rows}</Descriptions.Item>
            </Descriptions>
            <Table
              rowKey={(row) => `${row.sheet_index}-${row.row_index}-${row.source_sku_ref}`}
              size="small"
              columns={columns}
              dataSource={preview.rows}
              scroll={{ x: 1380 }}
              pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 行` }}
            />
          </>
        ) : null}
      </Space>

      <Modal
        title="确认 SKU 映射"
        open={Boolean(selected)}
        onCancel={() => setSelected(null)}
        confirmLoading={confirming}
        okButtonProps={{ disabled: confirming || !skuId }}
        cancelButtonProps={{ disabled: confirming }}
        closable={!confirming}
        maskClosable={!confirming}
        keyboard={!confirming}
        okText="确认并启用"
        onOk={confirmMapping}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions size="small" bordered column={1}>
            <Descriptions.Item label="来源商品"><ProductIdentity name={selected?.source_product_name} code={selected?.source_sku_ref} /></Descriptions.Item>
            <Descriptions.Item label="京东商品"><ProductIdentity name={selected?.provider_sku_name} code={selected?.provider_sku_code} /></Descriptions.Item>
            <Descriptions.Item label="包装乘数">{selected?.quantity_multiplier}</Descriptions.Item>
          </Descriptions>
          <div>
            <Typography.Text>内部 SKU</Typography.Text>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择经人工确认的内部 SKU"
              style={{ width: '100%', marginTop: 6 }}
              value={skuId}
              onChange={setSkuId}
              options={skuOptions}
            />
          </div>
          <Typography.Text type="secondary">确认后将建立该内部 SKU 的京东商品编号映射及来源渠道包装换算映射。</Typography.Text>
        </Space>
      </Modal>
    </>
  );
}

function JdPiecesPanel() {
  const { message: messageApi } = AntApp.useApp();
  const [candidates, setCandidates] = useState<JdPiecesCandidate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCandidates(await providerSkuMappingsApi.jdPiecesCandidates());
    } catch (caught) {
      setError(caught instanceof Error ? caught : new Error(String(caught)));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  const importSelected = async () => {
    const rows = candidates
      .filter((row) => selectedRowKeys.includes(row.provider_sku_code))
      .map((row) => ({
        provider_sku_code: row.provider_sku_code,
        jd_pieces_per_unit: values[row.provider_sku_code]?.trim() || row.candidate || '',
      }))
      .filter((row) => row.jd_pieces_per_unit.length > 0);
    if (rows.length === 0) {
      messageApi.warning('请先勾选要导入的 SKU，并确保有候选或已填写确认值');
      return;
    }
    setSubmitting(true);
    try {
      const result = await providerSkuMappingsApi.importJdPiecesPerUnit({ rows });
      messageApi.success(`导入完成：接受 ${result.accepted_count}，跳过 ${result.skipped_count}`);
      setSelectedRowKeys([]);
      setValues({});
      await reload();
    } catch (caught) {
      messageApi.error(errorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<JdPiecesCandidate> = [
    { title: '京东 SKU 编码', dataIndex: 'provider_sku_code', width: 180 },
    { title: '内部 SKU', dataIndex: 'sku_id', width: 110 },
    { title: '单位', dataIndex: 'unit', width: 80, render: (value?: string | null) => value || '—' },
    { title: '内部规格', dataIndex: 'specification', width: 140, ellipsis: true, render: (value?: string | null) => value || '—' },
    { title: '来源规格', dataIndex: 'source_specification', width: 140, ellipsis: true, render: (value?: string | null) => value || '—' },
    { title: '来源商品', dataIndex: 'source_product_name', width: 180, ellipsis: true, render: (value?: string | null) => value || '—' },
    { title: '候选件数', dataIndex: 'candidate', width: 90, render: (value?: string | null) => value ?? '—' },
    { title: '已配置', dataIndex: 'configured', width: 90, render: (value?: string | null) => value ?? '—' },
    {
      title: '确认值',
      key: 'confirm_value',
      width: 120,
      render: (_, row) => (
        <Input
          aria-label={`确认 ${row.provider_sku_code} 的京东件数换算`}
          placeholder={row.candidate ?? '需人工填写'}
          value={values[row.provider_sku_code] ?? ''}
          onChange={(event) => setValues((prev) => ({ ...prev, [row.provider_sku_code]: event.target.value }))}
        />
      ),
    },
  ];

  if (loading) return <AdminLoading description="正在加载京东件数换算候选…" />;
  if (error) return <AdminFailureAlert error={error} title="京东件数换算加载失败" onRetry={reload} />;

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="候选来自商品规格/来源规格解析，只用于人工确认；未配置前不会默认按 1 件处理。"
      />
      <Space wrap>
        <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
        <Button type="primary" loading={submitting} disabled={selectedRowKeys.length === 0} onClick={importSelected}>
          导入选中确认值（{selectedRowKeys.length}）
        </Button>
      </Space>
      <Table<JdPiecesCandidate>
        rowKey={(row) => row.provider_sku_code}
        size="small"
        columns={columns}
        dataSource={candidates}
        rowSelection={{
          selectedRowKeys,
          onChange: (keys) => setSelectedRowKeys(keys.map(String)),
        }}
        pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
        scroll={{ x: 1200 }}
      />
    </Space>
  );
}

interface EditingMatrixCell {
  sku: MasterDataRecord;
  channel: SourceMappingMatrixChannel;
  mapping?: MasterDataRecord;
}

function SourceSkuMatrix() {
  const { message: messageApi } = AntApp.useApp();
  const [form] = Form.useForm();
  const [skus, setSkus] = useState<MasterDataRecord[]>([]);
  const [mappings, setMappings] = useState<MasterDataRecord[]>([]);
  const [selectedChannels, setSelectedChannels] = useState<SourceMappingMatrixChannel[]>(SOURCE_MAPPING_CHANNELS);
  const [editingCell, setEditingCell] = useState<EditingMatrixCell | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  const reload = useCallback(() => setReloadToken((value) => value + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      skusApi.list({ page: 0, size: 200 }),
      sourceSkuMappingsApi.list({ page: 0, size: 200 }),
    ])
      .then(([skuPage, mappingPage]) => {
        if (cancelled) return;
        setSkus(skuPage.items);
        setMappings(mappingPage.items);
        setLoading(false);
      })
      .catch((caught: Error) => {
        if (cancelled) return;
        setError(caught);
        setLoading(false);
      });
    return () => { cancelled = true; };
  }, [reloadToken]);

  const matrix = useMemo(
    () => buildSourceSkuMappingMatrix(skus, mappings, selectedChannels),
    [mappings, selectedChannels, skus],
  );

  const openCell = (cell: EditingMatrixCell) => {
    setEditingCell(cell);
    form.setFieldsValue(cell.mapping
      ? {
          source_sku_ref: attr(cell.mapping, 'source_sku_ref'),
          source_sku_name: cell.mapping.name,
          quantity_multiplier: attr(cell.mapping, 'quantity_multiplier'),
          active: cell.mapping.active,
        }
      : { quantity_multiplier: '1.000', active: true });
  };

  const saveCell = async () => {
    if (!editingCell) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editingCell.mapping) {
        await sourceSkuMappingsApi.update(
          editingCell.mapping.id,
          {
            expected_version: editingCell.mapping.version,
            quantity_multiplier: values.quantity_multiplier,
            active: values.active,
          },
        );
        messageApi.success('映射已更新');
      } else {
        await sourceSkuMappingsApi.create({
          source_channel: editingCell.channel,
          source_sku_ref: values.source_sku_ref,
          source_sku_name: values.source_sku_name,
          sku_id: editingCell.sku.id,
          quantity_multiplier: values.quantity_multiplier,
          active: values.active,
        });
        messageApi.success('映射已创建');
      }
      setEditingCell(null);
      form.resetFields();
      reload();
    } catch (caught) {
      if (caught instanceof Error) messageApi.error(errorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  };

  const columns = useMemo<ColumnsType<SourceSkuMappingMatrixRow>>(() => [
    {
      title: '内部 SKU',
      key: 'sku',
      fixed: 'left',
      width: 260,
      render: (_, row) => {
        const presentation = internalSkuPresentation(row.sku);
        return (
          <ProductIdentity name={presentation.primary} code={presentation.secondary} meta={[presentation.meta]} />
        );
      },
    },
    ...matrix.channels.map((channel) => ({
      title: (
        <div className="sku-matrix__provider-heading">
          <Typography.Text strong>{CHANNEL_LABELS[channel]}</Typography.Text>
          <Typography.Text type="secondary">来源平台</Typography.Text>
        </div>
      ),
      key: channel,
      width: 260,
      render: (_: unknown, row: SourceSkuMappingMatrixRow) => {
        const cellMappings = row.mappingsByChannel[channel];
        if (!cellMappings.length) {
          return (
            <Button
              className="sku-matrix__empty-cell"
              type="text"
              icon={<PlusOutlined />}
              onClick={() => openCell({ sku: row.sku, channel })}
            >
              未映射 · 添加
            </Button>
          );
        }
        return (
          <div className="sku-matrix__mapping-stack">
            {cellMappings.map((mapping) => {
              const presentation = sourceMappingPresentation(mapping);
              return (
                <button
                  key={mapping.id}
                  type="button"
                  className="sku-matrix__mapped-cell"
                  onClick={() => openCell({ sku: row.sku, channel, mapping })}
                  aria-label={`编辑 ${row.sku.code} 在 ${CHANNEL_LABELS[channel]} 的映射`}
                >
                  <span className="sku-matrix__mapping-topline">
                    <ProductIdentity className="sku-matrix__mapping-identity" name={presentation.primary} code={presentation.secondary} />
                    <AdminStatusTag status={mapping.active ? 'ACTIVE' : 'INACTIVE'} />
                  </span>
                  <span className="sku-matrix__mapping-meta">数量乘数 {presentation.multiplier}</span>
                </button>
              );
            })}
            <Button type="link" size="small" icon={<PlusOutlined />} onClick={() => openCell({ sku: row.sku, channel })}>
              添加另一条
            </Button>
          </div>
        );
      },
    })),
  ], [matrix.channels]);

  if (loading) return <AdminLoading description="正在加载 SKU 映射矩阵…" />;
  if (error) return <AdminFailureAlert error={error} title="SKU 映射加载失败" onRetry={reload} />;

  const channelOptions = SOURCE_MAPPING_CHANNELS.map((channel) => ({
    value: channel,
    label: CHANNEL_LABELS[channel],
  }));

  return (
    <div className="sku-matrix">
      <div className="sku-matrix__toolbar">
        <div className="sku-matrix__filter">
          <Typography.Text type="secondary">显示平台</Typography.Text>
          <Select
            aria-label="显示平台"
            mode="multiple"
            maxTagCount="responsive"
            placeholder="选择要显示的平台"
            value={selectedChannels}
            onChange={(channels) => setSelectedChannels(channels as SourceMappingMatrixChannel[])}
            options={channelOptions}
          />
        </div>
        <Typography.Text type="secondary">
          {matrix.rows.length} 个内部 SKU · 显示 {matrix.channels.length} 个平台
        </Typography.Text>
        <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
      </div>

      <Table<SourceSkuMappingMatrixRow>
        className="sku-matrix__table"
        rowKey={(row) => row.sku.id}
        columns={columns}
        dataSource={matrix.rows}
        size="middle"
        sticky
        scroll={{ x: 260 + matrix.channels.length * 260 }}
        locale={{ emptyText: <AdminEmpty description="暂无内部 SKU" /> }}
        pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `共 ${total} 个内部 SKU` }}
      />

      <Modal
        title={editingCell?.mapping ? '编辑平台 SKU 映射' : '添加平台 SKU 映射'}
        open={Boolean(editingCell)}
        okText="保存"
        confirmLoading={submitting}
        onOk={saveCell}
        onCancel={() => { setEditingCell(null); form.resetFields(); }}
      >
        <Descriptions className="sku-matrix__modal-context" size="small" bordered column={1}>
          <Descriptions.Item label="内部 SKU">
            {editingCell ? <ProductIdentity name={editingCell.sku.name} code={editingCell.sku.code} /> : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="来源平台">
            {editingCell ? CHANNEL_LABELS[editingCell.channel] : '—'}
          </Descriptions.Item>
        </Descriptions>
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="source_sku_ref" label="平台 SKU 标识" rules={[{ required: true, message: '请填写平台 SKU 标识' }]}>
            <Input disabled={Boolean(editingCell?.mapping)} />
          </Form.Item>
          <Form.Item name="source_sku_name" label="平台商品名">
            <Input disabled={Boolean(editingCell?.mapping)} />
          </Form.Item>
          <Form.Item
            name="quantity_multiplier"
            label="数量乘数"
            rules={[
              { required: true, message: '请填写数量乘数' },
              { pattern: /^(?:0*[1-9]\d*)(?:\.\d{1,3})?$|^0*\.\d{0,2}[1-9]$/, message: '数量乘数必须是正数，且最多三位小数' },
            ]}
          >
            <Input placeholder="例如 1 或 2.5" />
          </Form.Item>
          <Form.Item name="active" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default function SkuMappingsPage() {
  const { token } = theme.useToken();

  return (
    <div className="sku-mappings-page">
      <Flex className="sku-mappings-page__heading" align="flex-start" justify="space-between" gap={24} wrap>
        <div>
          <Typography.Title id="sku-mapping-workspace-title" level={4} style={{ margin: 0 }}>
            SKU 映射矩阵
          </Typography.Title>
          <Typography.Text type="secondary">
            以内部 SKU 为主键，横向查看飞象、彩食鲜、聚福宝的平台商品映射。
          </Typography.Text>
        </div>
        <Tag
          bordered={false}
          style={{ marginInlineEnd: 0, color: token.colorTextSecondary, background: token.colorFillTertiary }}
        >
          主数据
        </Tag>
      </Flex>

      <section aria-labelledby="sku-mapping-workspace-title" className="sku-mappings-page__workspace">
        <SourceSkuMatrix />
      </section>

      <section
        aria-label="SKU 映射资料辅助核对"
        className="sku-mappings-page__reference"
        style={{
          background: token.colorBgContainer,
          border: `1px solid ${token.colorBorderSecondary}`,
          borderRadius: token.borderRadiusLG,
        }}
      >
        <Collapse
          ghost
          items={[
            {
              key: 'reference-preview',
              label: (
                <Space size={10}>
                  <FileSearchOutlined style={{ color: token.colorTextSecondary }} />
                  <span>
                    <Typography.Text strong>使用文件辅助核对</Typography.Text>
                    <Typography.Text type="secondary" className="sku-mappings-page__reference-copy">
                      上传映射资料和来源样表，预览候选后再逐条确认。
                    </Typography.Text>
                  </span>
                </Space>
              ),
              children: <ReferencePreviewPanel />,
            },
          ]}
        />
      </section>

      <section
        aria-label="京东件数换算"
        className="sku-mappings-page__reference"
        style={{
          background: token.colorBgContainer,
          border: `1px solid ${token.colorBorderSecondary}`,
          borderRadius: token.borderRadiusLG,
        }}
      >
        <Collapse
          ghost
          defaultActiveKey={['jd-pieces']}
          items={[
            {
              key: 'jd-pieces',
              label: (
                <Space size={10}>
                  <FileSearchOutlined style={{ color: token.colorTextSecondary }} />
                  <span>
                    <Typography.Text strong>京东件数换算</Typography.Text>
                    <Typography.Text type="secondary" className="sku-mappings-page__reference-copy">
                      从规格生成候选，人工确认后导入为 planQuantity 换算。
                    </Typography.Text>
                  </span>
                </Space>
              ),
              children: <JdPiecesPanel />,
            },
          ]}
        />
      </section>

      <Typography.Text className="sku-mappings-page__footnote" type="secondary">
        数量乘数用于把平台商品数量换算为内部 SKU 数量。飞象、彩食鲜、聚福宝均只展示有证据的显式映射，未映射时不会自动猜测。
      </Typography.Text>
    </div>
  );
}
