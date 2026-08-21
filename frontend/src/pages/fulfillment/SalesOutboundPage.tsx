/**
 * 履约中心 · 销售出库（GET /api/v1/fulfillment-exports + 详情 + 文件下载）。
 * 履约导出 = 发货前交给履约方（京东/第三方）的发货指令文件；下载后进入回传闭环，
 * 使用状态见 ExportUsageStatus。文件一旦生成即形成履约承诺（CONTEXT.md 履约导出）。
 */

import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Empty, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, Upload, message } from 'antd';
import { CloudSyncOutlined, DownloadOutlined, FileExcelOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { Link, useSearchParams } from 'react-router-dom';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { ApiError, errorMessage } from '@/api/client';
import { fileOperationsApi, fulfillmentExportsApi, platformOrdersApi, providersApi } from '@/api/endpoints';
import type { ExportUsageStatus, FulfillmentExport, FulfillmentExportDetail, FulfillmentExportWecomState, ImportBatch, PlatformOrderRefreshResult, TrackingImportBatch } from '@/api/types';
import { CHANNEL_LABELS, PROVIDER_TYPE_LABELS } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import { EXPORT_USAGE_SEMANTIC, importRowStatusSemantic } from '@/pages/shared/semanticStatus';
import {
  FILE_JOB_BATCH_PARAM,
  invalidBatchIdMessage,
  parseBatchIdParam,
  reviewsUrlForBatch,
} from '@/pages/shared/batchUrl';
import {
  canReceiveTracking,
  presentImportRow,
  presentTrackingBatchRow,
  summarizeImportBatch,
  type ImportRowView,
  type TrackingBatchRowView,
} from './fileOperations';

const USAGE_LABELS: Record<ExportUsageStatus, string> = {
  GENERATED_NOT_DOWNLOADED: '未下载',
  DOWNLOADED_WAITING_RETURN: '已下载待回传',
  RETURNED: '已回传',
  RETURN_OVERDUE: '回传超时',
};

/** 企微出站状态（Issue #84）展示语义。 */
const WECOM_STATUS_LABELS: Record<FulfillmentExportWecomState['status'], { text: string; color: string }> = {
  PENDING: { text: '待发送', color: 'processing' },
  ACTIVE: { text: '已发送', color: 'success' },
  COMPLETED: { text: '已收齐', color: 'green' },
  MANUALLY_STOPPED: { text: '已停止提醒', color: 'warning' },
  FAILED: { text: '发送失败', color: 'error' },
  UNKNOWN: { text: '发送未知需对账', color: 'error' },
  LEGACY: { text: '历史导出未纳入', color: 'default' },
};

function num(v: string | number | undefined | null): string {
  if (v === undefined || v === null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v);
}

function SourceImportPanel({ onCompleted }: { onCompleted: () => void }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const batchParam = parseBatchIdParam(searchParams.get(FILE_JOB_BATCH_PARAM));
  const urlBatchId = batchParam.kind === 'valid' ? batchParam.id : null;
  const urlBatchInvalid = batchParam.kind === 'invalid' ? batchParam.raw : null;
  const [urlBatchError, setUrlBatchError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [mode, setMode] = useState<'NEW' | 'REVISION'>('NEW');
  const [parentBatchId, setParentBatchId] = useState('');
  const [uploading, setUploading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [jdSubmitting, setJdSubmitting] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportBatch | null>(null);
  const [confirmRows, setConfirmRows] = useState<ImportRowView[]>([]);
  const [confirmTotal, setConfirmTotal] = useState(0);
  const [confirmRowsLoading, setConfirmRowsLoading] = useState(false);
  const [confirmRowsError, setConfirmRowsError] = useState<unknown>(null);
  const [pulling, setPulling] = useState(false);
  const [pullResult, setPullResult] = useState<PlatformOrderRefreshResult | null>(null);

  /** 批次标识进 URL（Issue #95）：刷新或浏览器回退后按 ?import_batch= 恢复当前批次，不再依赖页面本地 state。 */
  useEffect(() => {
    if (urlBatchInvalid !== null) {
      setUrlBatchError(invalidBatchIdMessage(urlBatchInvalid, '自动恢复该批次'));
      setResult(null);
      setConfirmRows([]);
      setConfirmTotal(0);
      return;
    }
    if (urlBatchId === null) {
      setUrlBatchError(null);
      return;
    }
    if (result?.id === urlBatchId) {
      return; // 本批次刚上传/刚确认，页面已有权威结果，无需重复拉取
    }
    let active = true;
    setUrlBatchError(null);
    fileOperationsApi.getSourceBatch(urlBatchId)
      .then(async (batch) => {
        if (!active) return;
        setResult(batch);
        await loadConfirmRows(batch);
      })
      .catch((error) => {
        if (!active) return;
        setResult(null);
        setConfirmRows([]);
        setConfirmTotal(0);
        setUrlBatchError(error instanceof ApiError && error.status === 404
          ? `批次 ${urlBatchId} 不存在或已被清理，请核对链接或重新上传文件。`
          : errorMessage(error));
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlBatchId, urlBatchInvalid, result?.id]);

  const loadConfirmRows = async (batch: ImportBatch) => {
    const statuses = [
      ...(batch.row_counts.accepted > 0 ? (['ACCEPTED'] as const) : []),
      ...(batch.row_counts.need_review > 0 ? (['NEED_REVIEW'] as const) : []),
      ...(batch.row_counts.rejected > 0 ? (['REJECTED'] as const) : []),
    ];
    setConfirmRows([]);
    setConfirmTotal(0);
    setConfirmRowsError(null);
    if (statuses.length === 0) return;

    setConfirmRowsLoading(true);
    try {
      const pages = await Promise.all(statuses.map((status) =>
        fileOperationsApi.getSourceRows(batch.id, { page: 0, size: 200, status })));
      setConfirmRows(pages.flatMap((page) => page.items).map(presentImportRow));
      setConfirmTotal(pages.reduce((total, page) => total + page.total_elements, 0));
    } catch (error) {
      setConfirmRowsError(error);
    } finally {
      setConfirmRowsLoading(false);
    }
  };

  const submit = async () => {
    if (!file) {
      message.warning('请先选择来源订单文件');
      return;
    }
    if (mode === 'REVISION' && !parentBatchId.trim()) {
      message.warning('修订导入需要填写原批次 ID');
      return;
    }
    setUploading(true);
    try {
      const imported = await fileOperationsApi.uploadSource(file, mode, parentBatchId.trim() || undefined);
      setResult(imported);
      setSearchParams({ [FILE_JOB_BATCH_PARAM]: imported.id });
      setFile(null);
      message.success('来源订单文件已处理');
      onCompleted();
      await loadConfirmRows(imported);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setUploading(false);
    }
  };

  const confirmBatch = async () => {
    if (!result) return;
    setConfirming(true);
    setConfirmError(null);
    try {
      const confirmed = await fileOperationsApi.confirmSourceBatch(result.id);
      setResult(confirmed);
      const sdkCount = confirmed.outbound_routing?.jd_sdk_shipment_ids?.length ?? 0;
      if (sdkCount > 0) {
        message.success(`本批次已确认：${sdkCount} 个发货批次走京东 SDK 建单；未就绪的已落待处理，可在「发货单」页确认地址后重试`);
      } else {
        message.success('本批次已确认，履约文件已生成');
      }
      onCompleted();
      // 确认后重载明细：已接收行建立系统订单关联，逐行结果需反映"已确认"
      void loadConfirmRows(confirmed);
    } catch (error) {
      const reason = errorMessage(error);
      setConfirmError(reason);
      message.error(reason);
    } finally {
      setConfirming(false);
    }
  };

  /** 对批次内京东发货批次重试 SDK 建单（05）；已提交跳过，失败项给出逐条可读原因。 */
  const submitJdOutbounds = async () => {
    if (!result) return;
    setJdSubmitting(true);
    try {
      const outcome = await fileOperationsApi.submitJdOutboundsForBatch(result.id);
      const failed = outcome.items.filter((item) => item.business_code);
      if (failed.length === 0) {
        message.success(`京东建单完成：新提交 ${outcome.submitted_count}，已跳过 ${outcome.skipped_count}`);
      } else {
        message.warning(`京东建单：新提交 ${outcome.submitted_count}，跳过 ${outcome.skipped_count}，失败 ${failed.length} 项（${failed.map((item) => `#${item.shipment_id} ${item.business_code}`).join('；')}）`);
      }
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setJdSubmitting(false);
    }
  };

  const confirmable = result && result.row_counts.need_review === 0 && result.row_counts.rejected === 0;
  const confirmLabel = result ? `确认本批次（已接收 ${result.row_counts.accepted} 行）` : '确认本批次';
  const confirmDisabledReason = result && result.row_counts.need_review + result.row_counts.rejected > 0
    ? `待复核 ${result.row_counts.need_review} 行、拒绝 ${result.row_counts.rejected} 行，请先处理后再确认`
    : '';

  /** 一键刷新三平台（彩食鲜/聚福宝/飞象）订单数据：拉取脚本产物自动上传为导入批次。 */
  const refreshPlatforms = async () => {
    setPulling(true);
    setPullResult(null);
    try {
      const outcome = await platformOrdersApi.refresh();
      setPullResult(outcome);
      const imported = outcome.channels.filter((c) => c.batch_no);
      if (imported.length > 0) {
        message.success(`三平台刷新完成：${imported.map((c) => `${CHANNEL_LABELS[c.channel]} ${c.batch_no}`).join('、')}`);
      } else {
        message.info('三平台刷新完成，未生成新导入批次（请查看各渠道结果）');
      }
      onCompleted();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setPulling(false);
    }
  };

  return (
    <Card
      title={<Space><FileExcelOutlined /><span>来源订单导入</span></Space>}
      size="small"
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          上传彩食鲜、聚福宝或飞象的待发货订单文件。商品编号资料不属于订单模板，请前往主数据 → SKU 映射维护。
        </Typography.Text>
        <Space wrap>
          <Button icon={<CloudSyncOutlined />} loading={pulling} onClick={refreshPlatforms}>
            刷新三平台订单
          </Button>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            一键拉取彩食鲜、聚福宝、飞象最新待发货订单并自动生成导入批次（聚福宝缺收货人字段，仅拉取不导入）。
          </Typography.Text>
        </Space>
        {pullResult ? (
          <Alert
            type={pullResult.channels.some((c) => c.status === 'FAILED') ? 'warning' : 'success'}
            showIcon
            closable
            onClose={() => setPullResult(null)}
            message={`三平台订单刷新完成（${pullResult.date_begin ?? ''} ~ ${pullResult.date_end ?? ''}）`}
            description={(
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {pullResult.channels.map((c) => (
                  <Typography.Text key={c.channel} style={{ display: 'block' }}>
                    {CHANNEL_LABELS[c.channel] ?? c.channel}：
                    {c.status === 'OK' && c.batch_no
                      ? `批次 ${c.batch_no} · 已接收 ${c.row_counts?.accepted ?? 0} 行 / 待复核 ${c.row_counts?.need_review ?? 0} / 拒绝 ${c.row_counts?.rejected ?? 0}`
                      : c.status === 'OK' && c.order_count != null
                        ? `已拉取 ${c.order_count} 单（${c.message ?? ''}）`
                        : c.message ?? (c.status === 'SKIPPED' ? '已跳过' : '失败')}
                  </Typography.Text>
                ))}
              </Space>
            )}
          />
        ) : null}
        <Space wrap>
          <Upload
            accept=".xlsx,.csv"
            maxCount={1}
            showUploadList={false}
            beforeUpload={(selected) => {
              setFile(selected);
              setResult(null);
              setConfirmRows([]);
              setConfirmTotal(0);
              setConfirmRowsError(null);
              setUrlBatchError(null);
              setSearchParams({});
              return false;
            }}
          >
            <Button icon={<FileExcelOutlined />}>选择订单文件</Button>
          </Upload>
          <Typography.Text>{file?.name ?? '尚未选择文件'}</Typography.Text>
          <Select
            value={mode}
            style={{ width: 120 }}
            options={[{ value: 'NEW', label: '新批次' }, { value: 'REVISION', label: '修订批次' }]}
            onChange={setMode}
          />
          {mode === 'REVISION' ? (
            <Input
              value={parentBatchId}
              onChange={(event) => setParentBatchId(event.target.value)}
              placeholder="原批次 ID"
              style={{ width: 160 }}
            />
          ) : null}
          <Button type="primary" icon={<UploadOutlined />} loading={uploading} onClick={submit}>
            开始导入
          </Button>
        </Space>
        {urlBatchId !== null && !result && !urlBatchError ? (
          <Typography.Text type="secondary">正在恢复批次 {urlBatchId} 的导入结果…</Typography.Text>
        ) : null}
        {urlBatchError ? (
          <Alert
            type="error"
            showIcon
            message="批次恢复失败"
            description={urlBatchError}
            closable
            onClose={() => setUrlBatchError(null)}
            action={<Link to="/fulfillment/sales-outbound">清除批次参数</Link>}
          />
        ) : null}
        {result ? (
          <Alert
            type={result.row_counts.need_review > 0 || result.row_counts.rejected > 0 ? 'warning' : 'success'}
            showIcon
            message={`批次 ${result.batch_no} · ${result.source_channel_display_name ?? '来源待确认'}`}
            description={result.confirmed_at
              ? `${summarizeImportBatch(result.row_counts)}；批次已确认，生成履约文件 ${result.generated_fulfillment_export_ids?.length ?? 0} 份，已形成履约承诺。`
              : `${summarizeImportBatch(result.row_counts)}；确认后已接收行将写入系统订单，并生成履约文件，形成履约承诺。请核对整个批次后统一确认。`}
            action={result.confirmed_at ? (
              <Button
                icon={<ReloadOutlined />}
                loading={jdSubmitting}
                onClick={submitJdOutbounds}
              >
                重试京东建单
              </Button>
            ) : (
              <Tooltip title={confirmDisabledReason || undefined}>
                <span>
                  <Popconfirm
                    title={confirmLabel}
                    description={`确认后已接收的 ${result.row_counts.accepted} 行将写入系统订单并生成履约文件，形成履约承诺；待复核 ${result.row_counts.need_review} 行、拒绝 ${result.row_counts.rejected} 行不在本次确认范围。`}
                    okText="确认本批次"
                    cancelText="取消"
                    onConfirm={confirmBatch}
                    disabled={!confirmable}
                  >
                    <Button
                      type="primary"
                      loading={confirming}
                      disabled={!confirmable}
                    >
                      {confirmLabel}
                    </Button>
                  </Popconfirm>
                </span>
              </Tooltip>
            )}
          />
        ) : null}
        {confirmError ? (
          <Alert type="error" showIcon message="批次确认失败" description={confirmError} />
        ) : null}
        {result && result.row_counts.total > 0 ? (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Space wrap style={{ justifyContent: 'space-between', width: '100%' }}>
              <Typography.Text strong>确认明细</Typography.Text>
              <Typography.Text type="secondary">
                共 {confirmTotal || result.row_counts.total} 行，当前展示 {confirmRows.length} 行
              </Typography.Text>
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {result.confirmed_at
                ? '已接收行已随本批次确认写入系统订单并生成履约文件，形成履约承诺。'
                : `已接收行将随本批次确认写入系统订单并生成履约文件，形成履约承诺；待复核与拒绝行不在确认范围。`}
            </Typography.Text>
            {confirmRowsError ? (
              <Alert
                type="error"
                showIcon
                message="导入明细加载失败"
                description={errorMessage(confirmRowsError)}
                action={<Button size="small" onClick={() => loadConfirmRows(result)}>重试</Button>}
              />
            ) : null}
            <Table<ImportRowView>
              rowKey="id"
              size="small"
              loading={confirmRowsLoading}
              pagination={false}
              scroll={{ x: 1720, y: 260 }}
              dataSource={confirmRows}
              locale={{ emptyText: confirmRowsLoading
                ? '正在加载导入明细…'
                : confirmRowsError
                  ? '明细加载失败，请使用上方「重试」按钮'
                  : '暂无明细行' }}
              columns={[
                {
                  title: '所属来源',
                  dataIndex: 'sourceChannel',
                  width: 110,
                  render: () => result?.source_channel_display_name
                    ?? (result?.source_channel ? CHANNEL_LABELS[result.source_channel] : '—'),
                },
                { title: '来源订单号', dataIndex: 'sourceOrderRef', width: 160 },
                {
                  title: '收货人',
                  dataIndex: 'receiverName',
                  width: 120,
                  render: (value: string) => value || '—',
                },
                {
                  title: '手机号',
                  dataIndex: 'receiverPhone',
                  width: 130,
                  render: (value: string) => value || '—',
                },
                {
                  title: '收货地址',
                  dataIndex: 'receiverAddress',
                  width: 220,
                  render: (value: string) => value || '—',
                },
                {
                  title: '商品',
                  dataIndex: 'productName',
                  width: 180,
                  render: (value: string) => value || '—',
                },
                {
                  title: '规格',
                  dataIndex: 'specification',
                  width: 110,
                  render: (value: string) => value || '—',
                },
                {
                  title: '数量',
                  dataIndex: 'quantity',
                  width: 80,
                  align: 'right',
                  render: (value: string) => value || '—',
                },
                { title: '来源 SKU', dataIndex: 'sourceSkuRef', width: 150 },
                {
                  title: '履约归属',
                  dataIndex: 'fulfillmentType',
                  width: 110,
                  render: (value: ImportRowView['fulfillmentType']) => value
                    ? (
                      <Tag color={value === 'JD_WAREHOUSE' ? 'geekblue' : 'default'}>
                        {PROVIDER_TYPE_LABELS[value]}
                      </Tag>
                    )
                    : '—',
                },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 100,
                  render: (status: ImportRowView['status']) => (
                    <Tag color={importRowStatusSemantic(status)}>
                      {status === 'ACCEPTED' ? '已接收' : status === 'REJECTED' ? '已拒绝' : '待复核'}
                    </Tag>
                  ),
                },
                {
                  title: '本次确认',
                  key: 'confirmScope',
                  width: 110,
                  render: (_, row) => row.status === 'ACCEPTED'
                    ? result.confirmed_at
                      ? <Tag color="success">已确认</Tag>
                      : <Tag color="processing">将确认</Tag>
                    : <Typography.Text type="secondary">不参与</Typography.Text>,
                },
                { title: '处理结果', dataIndex: 'reason', width: 260 },
                { title: '系统订单 ID', dataIndex: 'orderId', width: 130 },
                { title: '系统订单行 ID', dataIndex: 'orderLineId', width: 140 },
                {
                  title: '操作',
                  key: 'action',
                  width: 110,
                  fixed: 'right',
                  render: (_, row) => row.status === 'ACCEPTED'
                    ? row.orderId === '—'
                      ? <Typography.Text type="warning">未建立订单关联</Typography.Text>
                      : <Link to={`/orders/${row.orderId}`}>查看系统订单</Link>
                    : <Link to={reviewsUrlForBatch(result.id)}>前往人工复核</Link>,
                },
              ]}
            />
          </Space>
        ) : null}
      </Space>
    </Card>
  );
}

function TrackingUploadModal({
  target,
  open,
  onClose,
  onCompleted,
}: {
  target: FulfillmentExport | null;
  open: boolean;
  onClose: () => void;
  onCompleted: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<TrackingImportBatch | null>(null);

  const submit = async () => {
    if (!target || !file) {
      message.warning('请先选择履约回传文件');
      return;
    }
    setUploading(true);
    try {
      const imported = await fileOperationsApi.uploadTracking(target.id, file);
      setResult(imported);
      setFile(null);
      message.success('履约结果已回传');
      onCompleted();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setUploading(false);
    }
  };

  const close = () => {
    setFile(null);
    setResult(null);
    onClose();
  };

  const downloadReturn = async (id: string) => {
    try {
      await fileOperationsApi.downloadSourceReturn(id);
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  return (
    <Modal title={`回传履约结果 · ${target?.export_batch_no ?? ''}`} open={open} onCancel={close} footer={null} destroyOnClose>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert type="info" showIcon message="请上传由本批次履约文件填写后的 XLSX，系统会先整批校验，再原子写入发货与运单结果。" />
        <Space wrap>
          <Upload
            accept=".xlsx"
            maxCount={1}
            showUploadList={false}
            beforeUpload={(selected) => {
              setFile(selected);
              setResult(null);
              return false;
            }}
          >
            <Button icon={<FileExcelOutlined />}>选择回传文件</Button>
          </Upload>
          <Typography.Text>{file?.name ?? '尚未选择文件'}</Typography.Text>
        </Space>
        <Popconfirm
          title="确认校验并接收本批回传结果？"
          description="系统会先整批校验，再原子写入发货与运单结果；写入后形成发货与运单事实，失败行将标记异常原因，不可撤回。"
          okText="校验并接收"
          cancelText="取消"
          onConfirm={submit}
          disabled={!file}
        >
          <Button type="primary" icon={<UploadOutlined />} loading={uploading} disabled={!file} block>
            校验并接收
          </Button>
        </Popconfirm>
        {result ? (
          <>
            <Descriptions size="small" column={3} bordered>
              <Descriptions.Item label="已发货">{result.business_results?.shipped ?? 0}</Descriptions.Item>
              <Descriptions.Item label="部分发货">{result.business_results?.partial ?? 0}</Descriptions.Item>
              <Descriptions.Item label="失败">{result.business_results?.failed ?? 0}</Descriptions.Item>
            </Descriptions>
            {(result.rows?.length ?? 0) > 0 ? (
              <Table<TrackingBatchRowView>
                rowKey="id"
                size="small"
                pagination={false}
                scroll={{ x: 880, y: 220 }}
                dataSource={(result.rows ?? []).map(presentTrackingBatchRow)}
                columns={[
                  { title: '行号', dataIndex: 'rowIndex', width: 60, align: 'right' },
                  { title: '出库单号', dataIndex: 'outboundOrderNo', width: 140 },
                  {
                    title: '结果',
                    dataIndex: 'result',
                    width: 100,
                    render: (value: string, row) => (
                      <Tag color={row.result === '已发货' ? 'success' : row.result === '失败' ? 'error' : 'warning'}>
                        {value}
                      </Tag>
                    ),
                  },
                  { title: '实际发货数量', dataIndex: 'actualQuantity', width: 100, align: 'right' },
                  { title: '快递公司', dataIndex: 'carrier', width: 110 },
                  { title: '物流单号', dataIndex: 'trackingNo', width: 150 },
                  {
                    title: '异常原因',
                    dataIndex: 'failureReason',
                    width: 200,
                    render: (value: string) => value === '—'
                      ? <Typography.Text type="secondary">—</Typography.Text>
                      : <Typography.Text type="danger">{value}</Typography.Text>,
                  },
                ]}
              />
            ) : null}
            <Space wrap>
              {(result.generated_source_return_export_ids ?? []).map((id, index, items) => (
                <Button key={id} icon={<DownloadOutlined />} onClick={() => downloadReturn(id)}>
                  下载来源回填文件{items.length > 1 ? ` ${index + 1}` : ''}
                </Button>
              ))}
            </Space>
          </>
        ) : null}
      </Space>
    </Modal>
  );
}

/** 企微出站时间线（#84）：已发送时间/下次提醒/提醒次数/最后错误/停止原因。 */
function WecomTimeline({ wecom }: { wecom: FulfillmentExportWecomState }) {
  const semantic = WECOM_STATUS_LABELS[wecom.status];
  return (
    <Descriptions size="small" column={2} bordered style={{ marginBottom: 8 }}>
      <Descriptions.Item label="企微发送状态">
        <Tag color={semantic.color}>{semantic.text}</Tag>
        {wecom.chat_id ? (
          <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
            群 {wecom.chat_id}
          </Typography.Text>
        ) : null}
      </Descriptions.Item>
      <Descriptions.Item label="已发送时间">{wecom.initial_sent_at ?? '—'}</Descriptions.Item>
      <Descriptions.Item label="回传截止">{wecom.tracking_due_at ?? '—'}</Descriptions.Item>
      <Descriptions.Item label="下次提醒">
        {wecom.next_reminder_at ?? (wecom.status === 'ACTIVE' ? '已暂停' : '—')}
      </Descriptions.Item>
      <Descriptions.Item label="提醒次数">{wecom.reminder_count}</Descriptions.Item>
      <Descriptions.Item label="提醒间隔（分钟）">{wecom.reminder_interval_minutes}</Descriptions.Item>
      {wecom.status === 'FAILED' || wecom.status === 'UNKNOWN' ? (
        <Descriptions.Item label="最后错误" span={2}>
          <Typography.Text type="danger">{wecom.last_error ?? semantic.text}</Typography.Text>
        </Descriptions.Item>
      ) : null}
      {wecom.stopped ? (
        <Descriptions.Item label="停止原因" span={2}>
          <Typography.Text type="warning">
            {wecom.stopped.reason}（{wecom.stopped.by} · {wecom.stopped.at}）
          </Typography.Text>
        </Descriptions.Item>
      ) : null}
    </Descriptions>
  );
}

export default function SalesOutboundPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [providerId, setProviderId] = useState<string | undefined>();
  const [usage, setUsage] = useState<ExportUsageStatus | undefined>();
  const [selected, setSelected] = useState<FulfillmentExport | null>(null);
  const [trackingTarget, setTrackingTarget] = useState<FulfillmentExport | null>(null);
  const [returnDownloading, setReturnDownloading] = useState<string | null>(null);
  const [resending, setResending] = useState<string | null>(null);
  const [stopTarget, setStopTarget] = useState<FulfillmentExport | null>(null);
  const [stopReason, setStopReason] = useState('');
  const [stopSubmitting, setStopSubmitting] = useState(false);

  /** 人工重发（#84）：只登记新 delivery + 任务，发送由后台 Worker 执行。 */
  const handleWecomResend = async (record: FulfillmentExport) => {
    if (!record.wecom) return;
    setResending(record.id);
    try {
      await fulfillmentExportsApi.wecomResend(record.id, {
        expected_version: record.wecom.version,
      });
      message.success('已登记重发，文件将重新发送到该履约方企微群');
      list.reload();
    } catch (e) {
      message.error(errorMessage(e));
    } finally {
      setResending(null);
    }
  };

  /** 人工停止（#84）：持久化 operator/reason/time，停止后不再自动提醒。 */
  const handleWecomStop = async () => {
    if (!stopTarget?.wecom) return;
    if (!stopReason.trim()) {
      message.warning('请填写停止理由');
      return;
    }
    setStopSubmitting(true);
    try {
      await fulfillmentExportsApi.wecomStop(stopTarget.id, {
        expected_version: stopTarget.wecom.version,
        reason: stopReason.trim(),
      });
      message.success('已停止该导出的企微发送与周期提醒');
      setStopTarget(null);
      setStopReason('');
      list.reload();
    } catch (e) {
      message.error(errorMessage(e));
    } finally {
      setStopSubmitting(false);
    }
  };

  const providers = useAsync(() => providersApi.list(), []);
  const providerName = useMemo(() => {
    const map = new Map((providers.data ?? []).map((p) => [p.id, p.provider_name]));
    return (id?: string) => (id ? map.get(id) ?? id : '—');
  }, [providers.data]);

  const list = useAsync(
    () => fulfillmentExportsApi.list({ page, size, provider_id: providerId, usage_status: usage }),
    [page, size, providerId, usage],
  );

  const detail = useAsync<FulfillmentExportDetail | null>(
    () => (selected ? fulfillmentExportsApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );

  const [downloading, setDownloading] = useState(false);
  const handleDownload = async (r: FulfillmentExport) => {
    setDownloading(true);
    try {
      await fulfillmentExportsApi.downloadFile(r.id, r.export_batch_no);
      list.reload();
    } catch (e) {
      message.error(errorMessage(e));
    } finally {
      setDownloading(false);
    }
  };

  const handleSourceReturnDownload = async (record: FulfillmentExport) => {
    setReturnDownloading(record.id);
    try {
      // 文件路由：回传导入批次带 generated_source_return_export_ids；
      // SDK 直连路由：履约导出行无 tracking_import_batch_id，按导出来源批次直接取回填表。
      if (record.tracking_import_batch_id) {
        const tracking = await fileOperationsApi.getTrackingBatch(record.tracking_import_batch_id);
        if ((tracking.generated_source_return_export_ids?.length ?? 0) === 0) {
          message.info('当前批次尚未生成来源回填文件');
          return;
        }
        for (const returnId of tracking.generated_source_return_export_ids ?? []) {
          await fileOperationsApi.downloadSourceReturn(returnId);
        }
      } else if (record.import_batch_id) {
        // 一个批次可能有多版回填表（分批回运单会追加版本）；只下终版，
        // 避免运营拿到已作废版本回传给来源平台——文件名是 SHA-256，肉眼分不出新旧。
        const exports = await fileOperationsApi.sourceReturns(record.import_batch_id);
        const finals = exports.filter((item) => item.is_final);
        if (finals.length === 0) {
          message.info('当前批次尚未生成来源回填文件');
          return;
        }
        for (const item of finals) {
          await fileOperationsApi.downloadSourceReturn(item.id);
        }
      } else {
        message.info('当前批次尚未生成来源回填文件');
      }
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setReturnDownloading(null);
    }
  };

  const columns: ColumnsType<FulfillmentExport> = [
    { title: '导出批次号', dataIndex: 'export_batch_no', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '履约方', dataIndex: 'provider_id', width: 150, render: (v?: string) => providerName(v) },
    { title: '导出类型', dataIndex: 'export_kind', width: 110 },
    { title: '模板版本', dataIndex: 'template_version', width: 110, render: (v?: string) => v ?? '—' },
    { title: '生成时间', dataIndex: 'generated_at', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    {
      title: '回传截止',
      dataIndex: 'tracking_due_at',
      width: 170,
      render: (v?: string) => (v ? <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> : '—'),
    },
    {
      title: '使用状态',
      dataIndex: 'usage_status',
      width: 130,
      render: (v: ExportUsageStatus) => <Tag color={EXPORT_USAGE_SEMANTIC[v]}>{USAGE_LABELS[v]}</Tag>,
    },
    {
      title: '下载次数',
      dataIndex: 'download_audit',
      width: 90,
      align: 'right',
      render: (d?: { download_count?: number }) => d?.download_count ?? 0,
    },
    {
      title: '企微通知',
      dataIndex: 'wecom',
      width: 130,
      render: (wecom?: FulfillmentExportWecomState) => {
        if (!wecom) {
          return <Tag>未纳入</Tag>;
        }
        const semantic = WECOM_STATUS_LABELS[wecom.status];
        return (
          <Tooltip
            title={
              wecom.status === 'FAILED' || wecom.status === 'UNKNOWN'
                ? wecom.last_error ?? semantic.text
                : undefined
            }
          >
            <Tag color={semantic.color}>{semantic.text}</Tag>
          </Tooltip>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 330,
      fixed: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" type="link" icon={<DownloadOutlined />} loading={downloading} onClick={() => handleDownload(r)}>
            下载
          </Button>
          <Button
            size="small"
            type="link"
            icon={<UploadOutlined />}
            disabled={!canReceiveTracking(r.export_kind, r.usage_status)}
            onClick={() => setTrackingTarget(r)}
          >
            回传
          </Button>
          {r.tracking_import_batch_id || r.import_batch_id ? (
            <Button
              size="small"
              type="link"
              icon={<DownloadOutlined />}
              loading={returnDownloading === r.id}
              onClick={() => handleSourceReturnDownload(r)}
            >
              来源回填
            </Button>
          ) : null}
          {r.wecom && r.wecom.status !== 'LEGACY' ? (
            <>
              <Popconfirm
                title="重新发送该导出文件到企微群？"
                description={r.wecom.status === 'COMPLETED'
                  ? '该导出运单已收齐，无需重发。'
                  : '将重新上传并发送文件消息到该履约方登记的企微群，发送后提醒时间线以新发送时刻重置。'}
                okText="重新发送"
                cancelText="取消"
                disabled={r.wecom.status === 'COMPLETED'}
                onConfirm={() => handleWecomResend(r)}
              >
                <Button
                  size="small"
                  type="link"
                  icon={<CloudSyncOutlined />}
                  loading={resending === r.id}
                  disabled={r.wecom.status === 'COMPLETED'}
                >
                  重发
                </Button>
              </Popconfirm>
              {r.wecom.status === 'MANUALLY_STOPPED' || r.wecom.status === 'COMPLETED' ? null : (
                <Button size="small" type="link" danger onClick={() => setStopTarget(r)}>
                  停止
                </Button>
              )}
            </>
          ) : null}
          <Typography.Link onClick={() => setSelected(r)}>明细</Typography.Link>
        </Space>
      ),
    },
  ];

  const err = list.error || providers.error;

  return (
    <PageShell
      title="销售出库"
      description="履约导出 = 发货前交给履约方（京东/第三方）的发货指令文件；下载后进入回传闭环，文件一旦生成即形成履约承诺。"
      actions={<Button icon={<ReloadOutlined />} onClick={list.reload}>刷新</Button>}
    >
      <SourceImportPanel onCompleted={list.reload} />

      <FilterBar>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>履约方</Typography.Text>
        <Select style={{ width: 200 }} placeholder="全部履约方" allowClear value={providerId} onChange={setProviderId}
          options={(providers.data ?? []).map((p) => ({ value: p.id, label: p.provider_name }))} />
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>使用状态</Typography.Text>
        <Select style={{ width: 160 }} placeholder="全部" allowClear value={usage} onChange={setUsage}
          options={(Object.keys(USAGE_LABELS) as ExportUsageStatus[]).map((k) => ({ value: k, label: USAGE_LABELS[k] }))} />
      </FilterBar>

      <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
        <DataTable<FulfillmentExport>
          rowKey="id"
          columns={columns}
          dataSource={list.data?.items ?? []}
          loading={list.loading}
          error={err}
          onRetry={list.reload}
          errorTitle="销售出库加载失败"
          size="middle"
          scroll={{ x: 1300 }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: list.data?.total_elements ?? 0,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p - 1);
              setSize(s);
            },
          }}
        />
      </Card>

      <Drawer
        title={`导出批次 ${selected?.export_batch_no ?? ''} 明细`}
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        width={680}
        styles={{ body: { padding: '16px 20px' } }}
      >
        {detail.data ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Space wrap>
              <Tag color={EXPORT_USAGE_SEMANTIC[detail.data.usage_status]}>{USAGE_LABELS[detail.data.usage_status]}</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                履约方：{providerName(detail.data.provider_id)} · 模板 {detail.data.template_version} · 生成 {detail.data.generated_at}
              </Typography.Text>
            </Space>
            {detail.data.wecom ? <WecomTimeline wecom={detail.data.wecom} /> : null}
            <Table
              rowKey="export_line_no"
              size="small"
              pagination={false}
              scroll={{ x: 600 }}
              columns={[
                { title: '行号', dataIndex: 'export_line_no', width: 70, align: 'right' },
                { title: '出库单号', dataIndex: 'outbound_order_no', width: 130, render: (v?: string) => v ?? '—' },
                { title: '履约方 SKU', dataIndex: 'provider_sku_code', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
                { title: '指令数量', dataIndex: 'instructed_quantity', width: 90, align: 'right', render: num },
                { title: '单位', dataIndex: 'unit', width: 70 },
                { title: '金额', dataIndex: 'item_amount', width: 100, align: 'right', render: num },
              ]}
              dataSource={detail.data.lines ?? []}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无明细行" /> }}
            />
          </Space>
        ) : detail.loading ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="加载中…" />
        ) : detail.error ? (
          <Alert type="error" showIcon message={errorMessage(detail.error)} />
        ) : null}
      </Drawer>

      <Modal
        title={`停止企微通知 · ${stopTarget?.export_batch_no ?? ''}`}
        open={Boolean(stopTarget)}
        onCancel={() => {
          if (stopSubmitting) return;
          setStopTarget(null);
          setStopReason('');
        }}
        onOk={handleWecomStop}
        okText="确认停止"
        cancelText="取消"
        okButtonProps={{ danger: true, loading: stopSubmitting }}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          message="停止后不再自动发送与周期提醒"
          description="停止将持久化操作人、理由与时间（可追溯）；已入队的发送/提醒任务会幂等跳过。如需恢复，可对该导出执行「重发」。"
        />
        <Input.TextArea
          rows={3}
          style={{ marginTop: 12 }}
          placeholder="请填写停止理由（必填，将随停止记录审计）"
          value={stopReason}
          onChange={(event) => setStopReason(event.target.value)}
          maxLength={500}
        />
      </Modal>

      <TrackingUploadModal
        target={trackingTarget}
        open={Boolean(trackingTarget)}
        onClose={() => setTrackingTarget(null)}
        onCompleted={list.reload}
      />
    </PageShell>
  );
}
