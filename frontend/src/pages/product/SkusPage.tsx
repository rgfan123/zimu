/**
 * 商品档案：以可履约 SKU 为运营主记录，同时展示所属商品、品类、履约方、规格与价格。
 */

import { useCallback, useEffect, useState } from 'react';
import { Button, Input, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link } from 'react-router-dom';
import MasterDataCrud, { attr, type CrudField } from '@/pages/shared/MasterDataCrud';
import { MainImageThumb } from '@/pages/shared/MainImage';
import { productsApi, skusApi } from '@/api/endpoints';
import type { MasterDataRecord } from '@/api/types';
import PlatformUploadModal from './PlatformUploadModal';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { useCategoryOptions, useProductOptions, useProviderOptions } from './masterOptions';
import { displaySkuSpecification } from './productArchive';
import {
  COMMERCIAL_PRICE_PATTERN,
  buildSkuCreateBody,
  buildSkuUpdateBody,
  commercialPriceLabel,
} from './skuCommercialPrice';
import {
  LEAD_TIME_HOURS_PATTERN,
  buildProductCreateBody,
  buildProductCreateValues,
  buildProductFieldsUpdateBody,
  leadTimeLabel,
  listingPeriodLabel,
  marginLabel,
} from './productArchiveFields';

export default function SkusPage() {
  const [providerId, setProviderId] = useState<string | undefined>();
  /** 输入框当前文本：受控于本页 state，组件树重挂载后仍在。searchQuery 才是已提交的查询条件。 */
  const [searchInput, setSearchInput] = useState('');
  const [searchQuery, setSearchQuery] = useState<string | undefined>();
  const [platformUploadOpen, setPlatformUploadOpen] = useState(false);
  const providerOptions = useProviderOptions();
  const productOptions = useProductOptions();
  const categoryOptions = useCategoryOptions();
  const providerLabels = new Map(providerOptions.map(({ value, label }) => [String(value), label]));
  const categoryLabels = new Map(categoryOptions.map(({ value, label }) => [String(value), label]));
  const [tagCandidates, setTagCandidates] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    productsApi
      .tags()
      .then((tags) => {
        if (!cancelled) setTagCandidates(tags);
      })
      .catch(() => {
        // 候选加载失败不阻塞新建；用户仍可自由输入标签。
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // fetchPage 只随已提交的查询条件变化：输入框打字只改 searchInput，不重建 fetchPage，
  // 从而不会每次按键都触发 MasterDataCrud 重新拉取（queryKey 里已含 query/provider_id）。
  const fetchPage = useCallback(
    (q: { page: number; size: number }) => skusApi.list({ ...q, provider_id: providerId, query: searchQuery }),
    [providerId, searchQuery],
  );

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
    {
      title: '主图',
      key: 'main_image',
      width: 70,
      render: (_, r) => <MainImageThumb ref={attr(r, 'product_main_image_ref') as string | null | undefined} />,
    },
    { title: '品类', key: 'category', width: 150, render: (_, r) => categoryLabels.get(String(attr(r, 'category_id'))) ?? '—' },
    { title: '规格', key: 'spec', width: 110, render: (_, r) => displaySkuSpecification(attr(r, 'specification')) },
    { title: '单位', key: 'unit', width: 70, render: (_, r) => String(attr(r, 'unit') ?? '—') },
    { title: '履约方', key: 'provider', width: 170, render: (_, r) => providerLabels.get(String(attr(r, 'provider_id'))) ?? '—' },
    {
      title: '毛利', key: 'margin', width: 100, align: 'right',
      render: (_, r) => marginLabel(attr(r, 'margin')),
    },
    {
      title: '标签', key: 'tags', width: 200,
      render: (_, r) => {
        const tags = attr(r, 'product_tags');
        if (!Array.isArray(tags) || tags.length === 0) return '—';
        return (
          <span>
            {tags.map((tag) => (
              <Tag key={String(tag)} style={{ marginInlineEnd: 4 }}>{String(tag)}</Tag>
            ))}
          </span>
        );
      },
    },
    {
      title: '原料', key: 'ingredients', width: 160, ellipsis: true,
      render: (_, r) => String(attr(r, 'product_ingredients') ?? '—'),
    },
    {
      title: '上市周期', key: 'listing_period', width: 180,
      render: (_, r) => listingPeriodLabel(attr(r, 'product_listed_from'), attr(r, 'product_listed_until')),
    },
    {
      title: '发货时效', key: 'lead_time', width: 110,
      render: (_, r) => leadTimeLabel(attr(r, 'product_lead_time_hours')),
    },
    {
      title: '进货价', key: 'purchase_price', width: 90, align: 'right',
      render: (_, r) => commercialPriceLabel(attr(r, 'purchase_price')),
    },
    {
      title: '零售价', key: 'retail_price', width: 90, align: 'right',
      render: (_, r) => commercialPriceLabel(attr(r, 'retail_price')),
    },
    {
      title: '条码', key: 'barcode', width: 130,
      render: (_, r) => {
        const barcode = attr(r, 'barcode');
        return barcode ? <Tag style={{ marginInlineEnd: 0 }}>{String(barcode)}</Tag> : '—';
      },
    },
  ];

  const createModeOptions = [
    { value: 'NEW', label: '新建商品' },
    { value: 'EXIST', label: '选择现有商品' },
  ];
  const newProductVisible = (values: Record<string, unknown>) => values.product_mode !== 'EXIST';
  const existProductVisible = (values: Record<string, unknown>) => values.product_mode === 'EXIST';
  const productTagsOptions = tagCandidates.map((tag) => ({ value: tag, label: tag }));

  const createFields: CrudField[] = [
    { name: 'provider_id', label: '履约方', required: true, type: 'select', options: providerOptions },
    { name: 'product_mode', label: '商品', required: true, type: 'select', options: createModeOptions },
    // —— 新建商品模式：先建商品再建 SKU ——
    { name: 'product_code', label: '商品编码', required: true, placeholder: '如 P-1001', visible: newProductVisible },
    { name: 'product_name', label: '商品名称', required: true, visible: newProductVisible },
    {
      name: 'category_id', label: '品类', required: true, type: 'select', options: categoryOptions,
      visible: newProductVisible,
    },
    {
      name: 'ingredients', label: '原料', type: 'textarea',
      placeholder: '如 羔羊肉、孜然、食用盐', visible: newProductVisible,
    },
    {
      name: 'tags', label: '商品标签', type: 'tags', options: productTagsOptions,
      placeholder: '输入后回车，可复用已有标签', visible: newProductVisible,
    },
    { name: 'listing_period', label: '上市周期', type: 'date-range', visible: newProductVisible },
    {
      name: 'lead_time_hours', label: '发货时效（小时）', placeholder: '如 24 / 48',
      pattern: LEAD_TIME_HOURS_PATTERN, patternMessage: '请输入正整数小时数', visible: newProductVisible,
    },
    {
      name: 'product_purchase_price', label: '商品进货价（元）', placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN, patternMessage: '请输入非负金额，最多两位小数', visible: newProductVisible,
    },
    {
      name: 'product_retail_price', label: '商品零售价（元）', placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN, patternMessage: '请输入非负金额，最多两位小数', visible: newProductVisible,
    },
    {
      name: 'product_other_cost', label: '商品其他成本（元）', placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN, patternMessage: '请输入非负金额，最多两位小数', visible: newProductVisible,
    },
    { name: 'main_image_ref', label: '主图', type: 'upload', visible: newProductVisible },
    // —— 选择现有商品模式 ——
    { name: 'product_id', label: '商品', required: true, type: 'select', options: productOptions, visible: existProductVisible },
    // —— SKU 层 ——
    { name: 'specification', label: '规格', required: true, placeholder: '如 500g*2袋' },
    { name: 'unit', label: '单位', required: true, placeholder: '如 盒 / 袋' },
    { name: 'barcode', label: '条码', placeholder: '可选' },
    {
      name: 'purchase_price',
      label: '进货价（元）',
      placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'retail_price',
      label: '零售价（元）',
      placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  const updateFields: CrudField[] = [
    { name: 'specification', label: '规格', required: true },
    { name: 'barcode', label: '条码', placeholder: '可选（清空则删除）' },
    {
      name: 'purchase_price',
      label: '进货价（元）',
      placeholder: '清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'retail_price',
      label: '零售价（元）',
      placeholder: '清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    { name: 'active', label: '启用', type: 'switch' },
    // —— 商品档案字段（商品层，保存时 PATCH /products/{product_id}）——
    {
      name: 'ingredients',
      label: '原料',
      type: 'textarea',
      placeholder: '商品层字段：清空则删除',
      loadValue: (r) => attr(r, 'product_ingredients'),
    },
    {
      name: 'tags',
      label: '商品标签',
      type: 'tags',
      placeholder: '商品层字段：清空全部标签即删除',
      loadValue: (r) => attr(r, 'product_tags'),
    },
    {
      name: 'listing_period',
      label: '上市周期',
      type: 'date-range',
      loadValue: (r) => {
        const from = attr(r, 'product_listed_from');
        const to = attr(r, 'product_listed_until');
        if (!from && !to) return undefined;
        return { ...(from ? { from: String(from) } : {}), ...(to ? { to: String(to) } : {}) };
      },
    },
    {
      name: 'lead_time_hours',
      label: '发货时效（小时）',
      placeholder: '商品层字段：清空则删除',
      pattern: LEAD_TIME_HOURS_PATTERN,
      patternMessage: '请输入正整数小时数',
      loadValue: (r) => attr(r, 'product_lead_time_hours'),
    },
    {
      name: 'product_purchase_price',
      label: '商品进货价（元）',
      placeholder: '商品层字段：清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'product_retail_price',
      label: '商品零售价（元）',
      placeholder: '商品层字段：清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'product_other_cost',
      label: '商品其他成本（元）',
      placeholder: '商品层字段：清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'main_image_ref',
      label: '主图',
      type: 'upload',
      loadValue: (r) => attr(r, 'product_main_image_ref'),
    },
  ];

  return (
    <>
      <MasterDataCrud
      filters={
        <Space wrap>
          <Input.Search
            style={{ width: 260 }}
            placeholder="搜索 SKU 编码 / 商品名称"
            allowClear
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onSearch={(value) => setSearchQuery(value.trim() || undefined)}
          />
          {searchQuery ? (
            <Button size="small" onClick={() => { setSearchInput(''); setSearchQuery(undefined); }}>
              清除搜索
            </Button>
          ) : null}
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
            商品名称与品类使用基础信息维护；规格、履约方和价格在本档案维护。
          </Typography.Text>
          <Button size="small"><Link to="/product/products">管理商品名称</Link></Button>
          <Button size="small"><Link to="/product/categories">管理品类</Link></Button>
          <Button size="small" type="primary" ghost onClick={() => setPlatformUploadOpen(true)}>
            上架
          </Button>
        </Space>
      }
      extraQuery={{ provider_id: providerId, query: searchQuery }}
      fetchPage={fetchPage}
      create={async (v) => {
        // 「新建商品」模式：先建商品（含商品层字段），再用新商品建 SKU；「选择现有商品」直接建 SKU。
        let productId = String(v.product_id ?? '');
        if (String(v.product_mode ?? 'NEW') !== 'EXIST') {
          const product = await productsApi.create(buildProductCreateBody(buildProductCreateValues(v)));
          productId = product.id;
        }
        return skusApi.create(buildSkuCreateBody({ ...v, product_id: productId }));
      }}
      update={async (id, v, record) => {
        // SKU 层字段走 PATCH /skus；商品层字段走 PATCH /products（同商品多 SKU 共享）。
        const skuResult = await skusApi.update(id, buildSkuUpdateBody(v));
        const productId = attr(record, 'product_id');
        const productBody = buildProductFieldsUpdateBody({
          ...v,
          product_version: attr(record, 'product_version'),
        });
        if (productId && productBody) {
          await productsApi.update(String(productId), productBody);
        }
        return skuResult;
      }}
      createInitialValues={{ product_mode: 'NEW' }}
      columns={columns}
      createFields={createFields}
      updateFields={updateFields}
      />
      <PlatformUploadModal
        open={platformUploadOpen}
        onClose={() => setPlatformUploadOpen(false)}
        query={searchQuery}
        providerId={providerId}
      />
    </>
  );
}
