/**
 * 京东工具 · 京东库存原始查询（GET /api/v1/jd-stock/*，全部只读）。
 * 7 个查询：库存快照 / 库存汇总 / 批次异动 / 级别异动 / 效期商品 / 效期库存 / 店铺库存流水。
 * 结果只展示白名单字段；业务码 2001（权限未开通）单独提示，不当作系统错误。
 *
 * issue #40：骨架收敛到共享 JdQueryPage（配置驱动），本文件只声明配置。
 */

import { CloudServerOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Space, Tag, Typography } from 'antd';
import { apiRequest } from '@/api/client';
import type { JdClientStatus } from '@/api/types';
import { jdConnectionSemantic } from '@/pages/shared/semanticStatus';
import JdQueryPage, { isJdBusinessCodeDenied } from '@/pages/shared/JdQueryPage';
import type { JdQueryPageConfig } from '@/pages/shared/JdQueryPage';
import { jdStockQueryPrefill, type JdStockQueryKind } from './jdQueryPrefill';

/** 结果展示白名单：键名归一化（去符号 + 小写）后按查询类型匹配，未收录字段一律不展示。 */
const FIELD_LABELS: Record<JdStockQueryKind, Record<string, string>> = {
  snapshot: {
    ownerno: '事业部编码',
    ownername: '事业部名称',
    warehouseno: '仓库编码',
    warehousename: '仓库名称',
    goodsno: '商品编码',
    goodsname: '商品名称',
    goodslevel: '商品级别',
    stocktype: '库存类型',
    availablequantity: '可用数量',
    onwayquantity: '在途数量',
    occupiedquantity: '占用数量',
    totalquantity: '总数量',
    snapshotstatus: '快照状态',
    snapshottime: '快照时间',
    cursor: '游标',
    total: '总条数',
    totalnum: '总条数',
  },
  summary: {
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    warehousename: '仓库名称',
    goodsno: '商品编码',
    goodsname: '商品名称',
    goodslevel: '商品级别',
    stocktype: '库存类型',
    availablequantity: '可用数量',
    totalquantity: '总数量',
    updatetime: '更新时间',
    total: '总条数',
    totalnum: '总条数',
  },
  batchChanges: {
    batchchangeno: '批次异动单号',
    ownerno: '事业部编码',
    ownername: '事业部名称',
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    goodslevel: '商品级别',
    changenum: '异动数量',
    prechangelot: '异动前批次',
    prechangeproductdate: '异动前生产日期',
    prechangeexpiredate: '异动前效期',
    changedboxno: '异动后箱号',
    changedlot: '异动后批次',
    changedproductdate: '异动后生产日期',
    changedexpiredate: '异动后效期',
    changetime: '异动时间',
    total: '总条数',
    totalnum: '总条数',
  },
  levelChanges: {
    orderno: '级别异动单号',
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    prechangelevel: '异动前级别',
    changedlevel: '异动后级别',
    changetime: '异动时间',
    total: '总条数',
    totalnum: '总条数',
  },
  shelfLifeGoods: {
    checkorderno: '效期盘点单号',
    warehouseno: '仓库编码',
    ownerno: '事业部编码',
    createtime: '创建时间',
    goodsno: '商品编码',
    goodsname: '商品名称',
    expiredate: '到期日期',
    productdate: '生产日期',
    status: '状态',
    total: '总条数',
    totalnum: '总条数',
  },
  shelfLifeInventory: {
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    goodsname: '商品名称',
    goodslevel: '商品级别',
    lotno: '批次号',
    productdate: '生产日期',
    expiredate: '到期日期',
    availablequantity: '可用数量',
    status: '状态',
    total: '总条数',
    totalnum: '总条数',
  },
  shopStockFlow: {
    shopno: '店铺编码',
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    shopgoodsno: '店铺商品编码',
    erpgoodsno: 'ERP 商品编码',
    salesplatformgoodsno: '平台商品编码',
    salesplatformorderno: '平台订单号',
    bizno: '业务单号',
    biztype: '业务类型',
    stocknum: '库存数量',
    occupynum: '占用数量',
    stockchangenum: '库存变动',
    occupystockchangenum: '占用变动',
    createtime: '流水时间',
    total: '总条数',
    totalnum: '总条数',
  },
};


const QUERIES: JdQueryPageConfig['options'] = [
  {
    key: 'snapshot',
    label: '库存快照',
    path: '/api/v1/jd-stock/snapshot',
    fields: [
      { name: 'goods_no', label: '商品编码', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'goods_level', label: '商品级别', kind: 'list' },
      { name: 'isv_sku', label: 'ISV SKU', kind: 'list' },
      { name: 'seller_goods_sign', label: '商家商品标识', kind: 'list' },
      { name: 'stock_type', label: '库存类型', kind: 'list' },
      { name: 'above_zero', label: '仅查大于 0', kind: 'number', min: 0 },
      { name: 'cursor', label: '游标（翻页）' },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.snapshot,
  },
  {
    key: 'summary',
    label: '库存汇总',
    path: '/api/v1/jd-stock/summary',
    fields: [
      { name: 'goods_no', label: '商品编码', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'goods_level', label: '商品级别', kind: 'list' },
      { name: 'isv_sku', label: 'ISV SKU', kind: 'list' },
      { name: 'stock_type', label: '库存类型', kind: 'list' },
      { name: 'above_zero', label: '仅查大于 0', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.summary,
  },
  {
    key: 'batchChanges',
    label: '批次异动',
    path: '/api/v1/jd-stock/batch-changes',
    fields: [
      { name: 'warehouse_no', label: '仓库编码' },
      { name: 'batch_change_no', label: '批次异动单号', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'start_date', label: '开始日期', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'number', min: 0 },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.batchChanges,
  },
  {
    key: 'levelChanges',
    label: '级别异动',
    path: '/api/v1/jd-stock/level-changes',
    fields: [
      { name: 'order_no', label: '级别异动单号', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'pre_change_level', label: '异动前级别' },
      { name: 'changed_level', label: '异动后级别' },
      { name: 'start_date', label: '开始日期', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'number', min: 0 },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.levelChanges,
  },
  {
    key: 'shelfLifeGoods',
    label: '效期商品',
    path: '/api/v1/jd-stock/shelf-life-goods',
    fields: [
      { name: 'order_type', label: '单据类型' },
      { name: 'check_order_no', label: '效期盘点单号' },
      { name: 'start_time', label: '开始时间', placeholder: '如 2026-08-01 00:00:00' },
      { name: 'end_time', label: '结束时间', placeholder: '如 2026-08-13 23:59:59' },
      { name: 'current_page', label: '页码', kind: 'number', min: 0 },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.shelfLifeGoods,
  },
  {
    key: 'shelfLifeInventory',
    label: '效期库存',
    path: '/api/v1/jd-stock/shelf-life-inventory',
    fields: [
      { name: 'warehouse_no', label: '仓库编码' },
      { name: 'goods_no', label: '商品编码' },
      { name: 'erp_goods_no', label: 'ERP 商品编码' },
      { name: 'goods_level', label: '商品级别' },
      { name: 'status', label: '状态', kind: 'number', min: 0 },
      { name: 'current_page', label: '页码', kind: 'number', min: 0 },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.shelfLifeInventory,
  },
  {
    key: 'shopStockFlow',
    label: '店铺库存流水',
    path: '/api/v1/jd-stock/shop-stock-flow',
    fields: [
      { name: 'shop_no', label: '店铺编码' },
      { name: 'warehouse_no', label: '仓库编码' },
      { name: 'goods_no', label: '商品编码' },
      { name: 'start_date', label: '开始日期', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'number', min: 0 },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.shopStockFlow,
  },
];

const CONFIG: JdQueryPageConfig = {
  title: '京东库存原始查询',
  subtitle: '库存快照 / 汇总 / 批次异动 / 级别异动 / 效期商品 / 效期库存 / 店铺库存流水；全部为只读查询，不产生任何写操作。',
  icon: <CloudServerOutlined />,
  options: QUERIES,
  formLayout: 'vertical',
  queryBar: 'compact',
  remountForm: true,
  prefill: (params) => jdStockQueryPrefill(params),
  isPermissionDenied: isJdBusinessCodeDenied,
  mock: ({ mode }) => mode === 'MOCK',
  collect: { arrayJoin: '、' },
  result: {
    requestId: 'bottom',
    emptyText: '本次结果没有可公开展示的业务字段。',
    successTitle: ({ mock }) => (mock ? '模拟查询完成（不代表真实权限）' : '查询完成'),
    denied: {
      description: () =>
        '该账号尚未开通本查询对应的京东物流 ISC 接口权限（业务码 2001）。请在京东物流开放平台开通后再试，或联系管理员处理；这不是系统故障。',
    },
    failed: {
      description: (r) => (
        <Space direction="vertical" size={4}>
          <Typography.Text>业务码：{r.business_code ?? '未知'}</Typography.Text>
          {r.message ? <Typography.Text>{r.message}</Typography.Text> : null}
        </Space>
      ),
    },
  },
  feedback: {
    success: () => '查询完成',
    denied: () => '库存查询权限未开通',
    failed: () => '查询未完成，请查看下方提示',
  },
  status: {
    load: () => apiRequest<JdClientStatus>('/api/v1/jd-stock/status'),
    usePageState: true,
    errorTitle: '京东库存查询连接状态加载失败',
    renderTag: (data) =>
      data ? (
        <Tag color={jdConnectionSemantic(Boolean(data.live_ready), data.client_mode)}>
          {data.live_ready
            ? '真实连接已就绪'
            : data.client_mode === 'REAL'
              ? '真实连接未就绪'
              : data.client_mode === 'MOCK'
                ? '模拟模式（不代表真实权限）'
                : '连接状态未知'}
        </Tag>
      ) : null,
    warning: (data) =>
      data.client_mode === 'REAL' && !data.live_ready ? (
        <Alert
          type="warning"
          showIcon
          message="真实连接尚未就绪"
          description="京东授权或租户信息尚未完整，请联系管理员完成配置后再试。"
        />
      ) : null,
  },
  footer: ({ status }) => (
    <Card size="small">
      <Space>
        <Typography.Text type="secondary">
          结果仅展示白名单业务字段；页面为调试与对账用，业务调用请走受审计的履约用例。
        </Typography.Text>
        <div style={{ flex: 1 }} />
        <Button icon={<ReloadOutlined />} onClick={status.reload}>
          刷新连接状态
        </Button>
      </Space>
    </Card>
  ),
};

export default function JdStockQueryPage() {
  return <JdQueryPage config={CONFIG} />;
}
