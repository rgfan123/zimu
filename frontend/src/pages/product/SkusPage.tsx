/**
 * 商品档案：以可履约 SKU 为运营主记录，京东 EMG 编号后展示成本表 A..AU 原序列。
 */

import { useCallback, useMemo, useState } from 'react';
import { Alert, App, Button, Checkbox, Dropdown, Input, Select, Space, Tag, theme, Typography } from 'antd';
import { CloudUploadOutlined, DownloadOutlined, SettingOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { Link } from 'react-router-dom';
import MasterDataCrud, { attr, type CrudField } from '@/pages/shared/MasterDataCrud';
import { productArchiveSheetsApi, skusApi } from '@/api/endpoints';
import type { MasterDataRecord } from '@/api/types';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { useAsync } from '@/hooks/useAsync';
import { useCategoryOptions, useProviderOptions } from './masterOptions';
import {
  COMMERCIAL_PRICE_PATTERN,
  buildProductWithInitialSkuBody,
  buildSkuUpdateBody,
} from './skuCommercialPrice';
import PlatformUploadModal from './PlatformUploadModal';
import ProductArchiveSheetDrawer from './ProductArchiveSheetDrawer';
import {
  ARCHIVE_COLUMN_OPTIONS,
  DEFAULT_ARCHIVE_COLUMNS,
  productArchiveColumnGroups,
  productArchiveTableScrollX,
  productArchivesByProduct,
  type ArchiveColumn,
} from './productArchiveTable';

export default function SkusPage() {
  const [providerId, setProviderId] = useState<string | undefined>();
  const [searchQuery, setSearchQuery] = useState<string | undefined>();
  const [platformUploadOpen, setPlatformUploadOpen] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [archiveSheetOf, setArchiveSheetOf] = useState<MasterDataRecord | null>(null);
  const [visibleArchiveColumns, setVisibleArchiveColumns] = useState<ArchiveColumn[]>(
    () => [...DEFAULT_ARCHIVE_COLUMNS],
  );
  const { token } = theme.useToken();
  const { message } = App.useApp();
  const providerOptions = useProviderOptions();
  const categoryOptions = useCategoryOptions();
  const {
    data: archivePage,
    loading: archiveLoading,
    error: archiveError,
    reload: reloadArchive,
  } = useAsync(() => productArchiveSheetsApi.list({ page: 0, size: 200 }), []);
  const archiveByProduct = useMemo(
    () => productArchivesByProduct(archivePage?.items ?? []),
    [archivePage],
  );
  const matchedArchiveCount = archivePage?.items.reduce(
    (count, row) => count + (row.matched_product_id === null ? 0 : 1),
    0,
  ) ?? 0;
  const fetchSkuPage = useCallback(
    (query: { page: number; size: number }) => skusApi.list({
      ...query,
      provider_id: providerId,
      query: searchQuery,
    }),
    [providerId, searchQuery],
  );
  const exportArchive = useCallback(async () => {
    setExporting(true);
    try {
      await skusApi.exportFile();
    } catch {
      message.error('商品档案导出失败，请重试');
    } finally {
      setExporting(false);
    }
  }, [message]);

  const columns: ColumnsType<MasterDataRecord> = [
    { title: '商品 / SKU', key: 'identity', width: 210, render: (_, r) => <ProductIdentity name={r.name} code={r.code} /> },
    {
      title: '京东EMG编号',
      key: 'jd_emg_no',
      width: 160,
      render: (_, r) => {
        const emg = attr(r, 'jd_emg_no');
        return emg ? <Tag style={{ marginInlineEnd: 0 }}>{String(emg)}</Tag> : '—';
      },
    },
    ...productArchiveColumnGroups(archiveByProduct, visibleArchiveColumns),
    {
      title: '成本档案', key: 'archive_sheet', width: 90,
      render: (_, r) => (
        <Button type="link" size="small" style={{ padding: 0 }} onClick={() => setArchiveSheetOf(r)}>
          查看
        </Button>
      ),
    },
  ];

  const createFields: CrudField[] = [
    { name: 'product_code', label: '商品编码', required: false, placeholder: '留空自动生成' },
    { name: 'product_name', label: '商品名称', required: true },
    { name: 'category_id', label: '品类', required: true, type: 'select', options: categoryOptions },
    { name: 'provider_id', label: '履约方', required: true, type: 'select', options: providerOptions },
    { name: 'specification', label: '规格', required: true, placeholder: '如 500g*2袋' },
    { name: 'unit', label: '单位', required: true, placeholder: '如 盒 / 袋' },
    { name: 'barcode', label: '条码', placeholder: '可选' },
    {
      name: 'purchase_price',
      label: '进货价（元）',
      placeholder: '来自成本核算表 AI 线下供货成本/份，可人工覆盖',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'retail_price',
      label: '零售价（元）',
      placeholder: '来自成本核算表 AJ 售价，可人工覆盖',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  const updateFields: CrudField[] = [
    { name: 'specification', label: '规格', required: true },
    { name: 'unit', label: '单位', required: true, placeholder: '如 盒 / 袋' },
    { name: 'barcode', label: '条码', placeholder: '可选（清空则删除）' },
    {
      name: 'purchase_price',
      label: '进货价（元）',
      placeholder: '来自成本核算表 AI 线下供货成本/份，可人工覆盖',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'retail_price',
      label: '零售价（元）',
      placeholder: '来自成本核算表 AJ 售价，可人工覆盖',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  return (
    <>
      {archiveError ? (
        <Alert
          type="error"
          showIcon
          message="成本表挂接情况读取失败"
          description="当前无法确认挂接率，列表成本列暂以 — 展示。"
          action={<Button size="small" onClick={reloadArchive}>重试</Button>}
          style={{ marginBottom: 16 }}
        />
      ) : archiveLoading || archivePage === null ? (
        <Alert
          type="info"
          showIcon
          message="正在读取成本表挂接情况…"
          style={{ marginBottom: 16 }}
        />
      ) : (
        <Alert
          type={matchedArchiveCount < archivePage.total_elements ? 'warning' : 'success'}
          showIcon
          message={`成本表挂接率：已挂接 ${matchedArchiveCount} / 成本表共 ${archivePage.total_elements} 行`}
          description="未挂接行需人工挂接；未挂接 SKU 的成本列显示为 —。"
          style={{ marginBottom: 16 }}
        />
      )}
      <MasterDataCrud
        filters={
          <Space wrap>
          <Input.Search
            style={{ width: 260 }}
            placeholder="搜索 SKU 编码 / 商品名称 / 条码"
            allowClear
            onSearch={(value) => setSearchQuery(value.trim() || undefined)}
          />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>履约方</Typography.Text>
          <Select
            style={{ width: 200 }}
            placeholder="全部履约方"
            allowClear
            value={providerId}
            onChange={setProviderId}
            options={providerOptions}
          />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            新建会同时创建商品及首个 SKU；后续可在基础信息中维护完整商品资料。
          </Typography.Text>
          <Dropdown
            trigger={['click']}
            placement="bottomRight"
            popupRender={() => (
              <Checkbox.Group<ArchiveColumn>
                value={visibleArchiveColumns}
                options={ARCHIVE_COLUMN_OPTIONS}
                onChange={setVisibleArchiveColumns}
                style={{
                  width: 680,
                  maxWidth: 'calc(100vw - 32px)',
                  maxHeight: 420,
                  overflowY: 'auto',
                  padding: 16,
                  background: token.colorBgElevated,
                  borderRadius: token.borderRadiusLG,
                  boxShadow: token.boxShadowSecondary,
                  display: 'grid',
                  gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
                  gap: '8px 16px',
                }}
              />
            )}
          >
            <Button size="small" icon={<SettingOutlined />}>
              列设置（{visibleArchiveColumns.length} / 47）
            </Button>
          </Dropdown>
          <Button
            size="small"
            icon={<DownloadOutlined />}
            loading={exporting}
            aria-label="导出表格"
            onClick={exportArchive}
          >
            导出表格
          </Button>
          <Button size="small" type="primary" ghost icon={<CloudUploadOutlined />}
                  onClick={() => setPlatformUploadOpen(true)}>
            上架
          </Button>
          <Button size="small"><Link to="/product/products">管理商品名称</Link></Button>
          <Button size="small"><Link to="/product/categories">管理品类</Link></Button>
          </Space>
        }
        extraQuery={{ provider_id: providerId, query: searchQuery }}
        fetchPage={fetchSkuPage}
        create={(v) => skusApi.createWithProduct(buildProductWithInitialSkuBody(v))}
        update={(id, v) => skusApi.update(id, buildSkuUpdateBody(v))}
        columns={columns}
        createFields={createFields}
        updateFields={updateFields}
        updateFormNotice={(
          <span className="zs-admin-form-notice__text">
            商品名和品类属于商品资料，请前往「<Link to="/product/products">管理商品名称</Link>」修改。
          </span>
        )}
        tableScrollX={productArchiveTableScrollX(visibleArchiveColumns)}
      />
      <PlatformUploadModal
        open={platformUploadOpen}
        onClose={() => setPlatformUploadOpen(false)}
        query={searchQuery}
        providerId={providerId}
      />
      <ProductArchiveSheetDrawer
        open={archiveSheetOf !== null}
        productId={archiveSheetOf ? String(attr(archiveSheetOf, 'product_id') ?? '') : null}
        title={archiveSheetOf?.name ?? ''}
        onClose={() => setArchiveSheetOf(null)}
      />
    </>
  );
}
