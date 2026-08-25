/**
 * 履约中心 · 京东退货退供查询（GET /api/v1/jd-return/*，只读）。
 * 退货入库单列表 / 详情、退供单查询：接口选择 + 参数表单 + 白名单字段结果展示。
 * 联系方式等个人信息由后端在 HTTP 边界脱敏，本页只展示白名单业务字段；
 * 未授权（业务码 2001）时明确提示「权限未开通」，不当作系统错误。
 * 导航由主 agent 接线，本文件不注册路由。
 *
 * issue #40：骨架收敛到共享 JdQueryPage（配置驱动），本文件只声明配置；
 * 列表行「系统退货入库单号」点击直达详情通过 JdQueryPageHandle.switchTo 实现。
 */

import { useCallback, useMemo, useRef } from 'react';
import { SwapOutlined } from '@ant-design/icons';
import { Space, Tag, Typography } from 'antd';
import { apiRequest } from '@/api/client';
import type { QueryValue } from '@/api/client';
import { jdWarehouseApi } from '@/api/endpoints';
import type { JdQueryResult } from '@/api/types';
import { READ_ONLY_TAG_COLOR } from '@/pages/shared/semanticStatus';
import JdQueryPage, { isJdBusinessCodeDenied, normalizeKey } from '@/pages/shared/JdQueryPage';
import type { JdQueryField, JdQueryPageConfig, JdQueryPageHandle } from '@/pages/shared/JdQueryPage';

type QueryKind = 'rtwList' | 'rtwDetail' | 'returnToSupplier';

/** 参数表单按接口切换；空值会被 apiRequest 忽略，不发给后端。 */
const KIND_FIELDS: Record<QueryKind, JdQueryField[]> = {
  rtwList: [
    { name: 'return_to_warehouse_no', label: '退货入库单号', width: 240 },
    { name: 'erp_return_to_warehouse_no', label: '系统退货入库单号', width: 240 },
    { name: 'delivery_no', label: '送货单号', width: 240 },
    { name: 'out_store_no', label: '出库单号', width: 240 },
  ],
  rtwDetail: [
    {
      name: 'erp_return_to_warehouse_no',
      label: '系统退货入库单号',
      required: true,
      requiredMessage: '请输入系统退货入库单号',
      placeholder: '如 ZM-RTW-001（列表行的系统退货入库单号可点击直达）',
      width: 240,
    },
  ],
  returnToSupplier: [
    {
      name: 'erp_return_to_supplier_no',
      label: '系统退供单号',
      required: true,
      requiredMessage: '请输入系统退供单号',
      placeholder: '如 ZM-RTS-001',
      width: 240,
    },
  ],
};

const FLAG_OPTIONS = [
  { value: 1, label: '返回' },
  { value: 0, label: '不返回' },
];

/** 「返回明细」选项：列表 / 退供单接口可切换，详情接口由后端默认返回明细与批次。 */
const FLAG_FIELD: JdQueryField = {
  name: 'return_detail_flag',
  label: '返回明细',
  kind: 'flag',
  options: FLAG_OPTIONS,
  tip: '不填则使用京东接口默认行为；详情 / 退供单后端默认返回明细与批次。',
};

/** 结果展示字段白名单：归一化后的字段名（小写、去下划线）→ 中文标签。 */
const FIELD_LABELS: Record<string, string> = {
  // 退货入库单（列表 / 详情）
  returntowarehouseno: '退货入库单号',
  erpreturntowarehouseno: '系统退货入库单号',
  deliveryno: '送货单号',
  outstoreno: '出库单号',
  ownerno: '事业部编码',
  warehouseno: '仓库编码',
  source: '来源',
  returnreason: '退货原因',
  status: '状态',
  createtime: '创建时间',
  updatetime: '更新时间',
  createuser: '创建人',
  productscode: '品类编码',
  billingmode: '计费方式',
  receiveboxnum: '收货箱数',
  logicalinventoryfactor: '逻辑库存系数',
  erpdeliveryno: '系统送货单号',
  twicewaybill: '二次运单号',
  packageno: '包裹号',
  salesplatformno: '销售平台编码',
  salesplatformname: '销售平台名称',
  erpshopname: '系统店铺名称',
  goodsno: '商品编码',
  erpgoodsno: '系统商品编码',
  orderline: '订单行',
  goodslevel: '商品等级',
  planquantity: '计划数量',
  realquantity: '实际数量',
  receivedweight: '实收重量',
  // 退供单
  returntosupplierno: '退供单号',
  erpreturntosupplierno: '系统退供单号',
  supplierno: '供应商编码',
  deliverymode: '交货方式',
  operatortime: '操作时间',
  operatoruser: '操作人',
  remark: '备注',
  productsname: '品类名称',
  billingmodename: '计费方式名称',
  tcorderno: '运输中心单号',
  batchno: '批次号',
  serialno: '序列号',
};

const jdReturnApi = {
  rtwOrders: (params: Record<string, QueryValue>) =>
    apiRequest<JdQueryResult>('/api/v1/jd-return/rtw-orders', { params }),
  rtwOrderDetail: (erpReturnToWarehouseNo: string) =>
    apiRequest<JdQueryResult>(
      `/api/v1/jd-return/rtw-orders/${encodeURIComponent(erpReturnToWarehouseNo)}`,
    ),
  returnToSupplier: (erpReturnToSupplierNo: string) =>
    apiRequest<JdQueryResult>(
      `/api/v1/jd-return/return-to-suppliers/${encodeURIComponent(erpReturnToSupplierNo)}`,
    ),
};

function buildConfig(openDetail: (erpReturnToWarehouseNo: string) => void): JdQueryPageConfig {
  return {
    title: '京东退货退供查询',
    subtitle: '退货入库单列表 / 详情、退供单查询；只读，不创建或修改任何单据。',
    icon: <SwapOutlined />,
    options: [
      {
        key: 'rtwList',
        label: '退货入库单列表',
        fields: [...KIND_FIELDS.rtwList, FLAG_FIELD],
        run: (v) => jdReturnApi.rtwOrders(v as Record<string, QueryValue>),
        whitelist: FIELD_LABELS,
        view: 'table',
        renderCell: (key, value) =>
          normalizeKey(key) === 'erpreturntowarehouseno' && String(value) ? (
            <Typography.Link onClick={() => openDetail(String(value))}>{String(value)}</Typography.Link>
          ) : (
            String(value)
          ),
      },
      {
        key: 'rtwDetail',
        label: '退货入库单详情',
        fields: KIND_FIELDS.rtwDetail,
        run: (v) => jdReturnApi.rtwOrderDetail(String(v.erp_return_to_warehouse_no ?? '')),
        whitelist: FIELD_LABELS,
      },
      {
        key: 'returnToSupplier',
        label: '退供单查询',
        fields: [...KIND_FIELDS.returnToSupplier, FLAG_FIELD],
        run: (v) => jdReturnApi.returnToSupplier(String(v.erp_return_to_supplier_no ?? '')),
        whitelist: FIELD_LABELS,
      },
    ],
    queryBar: 'form',
    selectWidth: 200,
    selectLabel: '接口',
    isPermissionDenied: isJdBusinessCodeDenied,
    collect: { maxRows: 100000, prefixNested: true, skipUnlisted: true, indexArrays: true, includeNull: true },
    result: {
      container: 'card',
      requestId: 'card-extra',
      bordered: true,
      emptyText: '本次结果没有可展示的业务字段。',
      successTitle: ({ result }) => `查询成功（业务码 ${result.business_code}）`,
      cardExtra: (r) => (r.request_id ? `requestId：${r.request_id}` : null),
      denied: {
        description: (r) => (
          <Space direction="vertical" size={4}>
            <Typography.Text>
              当前应用尚未在京东开放平台开通该查询接口的访问权限（业务码 2001）。
            </Typography.Text>
            <Typography.Text type="secondary">
              请联系管理员在京东开放平台申请开通相应接口权限后重试；这不是系统故障。
            </Typography.Text>
            {r.request_id ? (
              <Typography.Text type="secondary" style={{ fontVariantNumeric: 'tabular-nums' }}>
                requestId：{r.request_id}
              </Typography.Text>
            ) : null}
          </Space>
        ),
      },
      failed: {
        title: () => '查询失败',
        description: (r) => (
          <Space direction="vertical" size={4}>
            <Typography.Text>{r.message || '京东服务暂时不可用，请稍后重试'}</Typography.Text>
            <Typography.Text type="secondary">
              业务码：{r.business_code ?? '未知'}
              {r.request_id ? `；requestId：${r.request_id}` : ''}
            </Typography.Text>
          </Space>
        ),
      },
    },
    feedback: { enabled: false },
    hint: (
      <Typography.Text type="secondary">
        结果只展示白名单业务字段；联系方式等个人信息已由后端脱敏，不在此页展示。
      </Typography.Text>
    ),
    status: {
      load: () => jdWarehouseApi.status(),
      usePageState: false,
      renderTag: (data) =>
        data ? (
          <Tag color={data.client_mode === 'REAL' ? READ_ONLY_TAG_COLOR : 'default'}>
            {data.client_mode === 'REAL' ? '真实连接' : '模拟模式'}
          </Tag>
        ) : null,
    },
  };
}

export default function JdReturnQueryPage() {
  const handleRef = useRef<JdQueryPageHandle>(null);
  const openDetail = useCallback((erpReturnToWarehouseNo: string) => {
    handleRef.current?.switchTo('rtwDetail', { erp_return_to_warehouse_no: erpReturnToWarehouseNo }, true);
  }, []);
  const config = useMemo(() => buildConfig(openDetail), [openDetail]);

  return <JdQueryPage ref={handleRef} config={config} />;
}
