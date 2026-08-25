/**
 * 京东工具 · 专业单据查询（GET /api/v1/jd-order/*，全部只读）。
 * 8 个查询：调整单 / 销毁单 / 异常单 / 采购单 / 加工单 / 作业关联 / 配送时效 / 同城轨迹。
 * 结果只展示白名单字段（收件人、电话、地址等个人字段不出现）；
 * 业务码 2001 或消息含权限字样时明确提示「权限未开通」，不当作系统错误。
 * 后端 MOCK 模式（app.jd.client-mode=MOCK，默认）返回稳定假数据，business_code=MOCK_SUCCESS。
 *
 * issue #40：骨架收敛到共享 JdQueryPage（配置驱动），本文件只声明配置。
 */

import { FileSearchOutlined } from '@ant-design/icons';
import { Card, Space, Tag, Typography } from 'antd';
import { READ_ONLY_TAG_COLOR, TOOL_CATEGORY_TAG_COLOR } from '@/pages/shared/semanticStatus';
import JdQueryPage from '@/pages/shared/JdQueryPage';
import type { JdQueryPageConfig } from '@/pages/shared/JdQueryPage';

type QueryKind =
  | 'adjustment'
  | 'destroy'
  | 'exception'
  | 'purchase'
  | 'processed'
  | 'operateRelation'
  | 'deliveryTime'
  | 'cityTrack';

/**
 * 结果展示白名单：键名归一化（去符号 + 小写）后按查询类型匹配，未收录字段一律不展示。
 * 字段名同时覆盖 Mock 响应（如 status、promiseTime、eclpNo）与真实 SDK DTO 响应；
 * 收件人、电话、地址等个人字段（receiverInfo / transporterPhone 等）不在白名单内。
 */
const FIELD_LABELS: Record<QueryKind, Record<string, string>> = {
  adjustment: {
    adjustmentno: '调整单号',
    erpadjustmentno: 'ERP 调整单号',
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    status: '状态',
    biztype: '业务类型',
    createtime: '创建时间',
  },
  destroy: {
    destroyno: '销毁单号',
    erpdestroyno: 'ERP 销毁单号',
    status: '状态',
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    deliverymode: '配送方式',
    destroytype: '销毁类型',
    destroymode: '销毁方式',
    destroyreason: '销毁原因',
    destroycompanyno: '销毁公司编码',
    createuser: '创建人',
  },
  exception: {
    totalnum: '总条数',
    orderno: '订单号',
    erporderno: 'ERP 订单号',
    exceptioncode: '异常编码',
    exceptionmessage: '异常信息',
    exceptionreason: '异常原因',
    solution: '解决方案',
    pausetime: '暂停时间',
    erpcreatetime: 'ERP 创建时间',
    createtime: '创建时间',
    ordertype: '单据类型',
    sellername: '商家名称',
    ownername: '事业部名称',
    warehousename: '仓库名称',
    status: '状态',
  },
  purchase: {
    purchaseno: '采购单号',
    erppurchaseno: 'ERP 采购单号',
    status: '状态',
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    supplierno: '供应商编码',
    purchaseorderstatus: '采购单状态',
    createtime: '创建时间',
    completetime: '完成时间',
    storagestatus: '入库状态',
    productname: '商品名称',
    billingmode: '计费方式',
    receiveboxnumber: '收货箱数',
    totalapplyprice: '申请总金额',
    totalrealprice: '实付总金额',
    createuser: '创建人',
    erpwarehouseno: 'ERP 仓库编码',
    grossweight: '毛重',
    volume: '体积',
  },
  processed: {
    processedno: '加工单号',
    erpprocessedno: 'ERP 加工单号',
    status: '状态',
    processedtype: '加工类型',
    ownerno: '事业部编码',
    ownername: '事业部名称',
    warehouseno: '仓库编码',
    warehousename: '仓库名称',
    sellerno: '商家编码',
    sellername: '商家名称',
    processstatus: '加工状态',
    updatetime: '更新时间',
  },
  operateRelation: {
    eclpno: 'ECLP 单号',
    orderno: '订单号',
    erporderno: 'ERP 订单号',
    ordertype: '单据类型',
  },
  deliveryTime: {
    waybillno: '运单号',
    promisetime: '承诺时效',
    trendspredicttime: '预测时效',
  },
  cityTrack: {
    deliveryno: '配送单号',
    waybillno: '运单号',
    city: '城市',
    status: '状态',
    transportername: '配送员',
    longitude: '经度',
    latitude: '纬度',
  },
};


const QUERIES: JdQueryPageConfig['options'] = [
  {
    key: 'adjustment',
    label: '调整单',
    path: '/api/v1/jd-order/adjustments',
    fields: [
      { name: 'adjustment_no', label: '调整单号' },
      { name: 'erp_adjustment_no', label: 'ERP 调整单号' },
      { name: 'start_time', label: '开始时间', placeholder: '如 2026-08-01 00:00:00' },
      { name: 'end_time', label: '结束时间', placeholder: '如 2026-08-13 23:59:59' },
      { name: 'status', label: '状态', kind: 'number', min: 0 },
      { name: 'biz_type', label: '业务类型', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.adjustment,
  },
  {
    key: 'destroy',
    label: '销毁单',
    path: '/api/v1/jd-order/destroy-orders',
    fields: [
      { name: 'destroy_no', label: '销毁单号' },
      { name: 'erp_destroy_no', label: 'ERP 销毁单号' },
      { name: 'destroy_item_list_flag', label: '返回销毁明细标志', kind: 'number', min: 0 },
      { name: 'destroy_batch_item_list_flag', label: '返回批次明细标志', kind: 'number', min: 0 },
      { name: 'return_destroy_data_flag', label: '返回销毁数据标志', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.destroy,
  },
  {
    key: 'exception',
    label: '异常单',
    path: '/api/v1/jd-order/exceptions',
    fields: [
      { name: 'order_type', label: '单据类型' },
      { name: 'biz_type', label: '业务类型' },
      { name: 'erp_order_no', label: 'ERP 订单号' },
      { name: 'order_no', label: '订单号' },
      { name: 'exception_code', label: '异常编码' },
      { name: 'start_date', label: '开始日期', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'number', min: 0 },
      { name: 'page_size', label: '每页条数', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.exception,
  },
  {
    key: 'purchase',
    label: '采购单',
    path: '/api/v1/jd-order/purchase-orders',
    fields: [
      { name: 'purchase_no', label: '采购单号' },
      { name: 'erp_purchase_no', label: 'ERP 采购单号' },
      { name: 'batch_purchase_no', label: '批次采购单号' },
      { name: 'purchase_item_flag', label: '采购明细标志', kind: 'number', min: 0 },
      { name: 'quality_inspection_item_flag', label: '质检明细标志', kind: 'number', min: 0 },
      { name: 'quality_inspection_err_item_flag', label: '质检异常明细标志', kind: 'number', min: 0 },
      { name: 'purchase_bat_attr_flag', label: '批次属性标志', kind: 'number', min: 0 },
      { name: 'purchase_item_reject_flag', label: '拒收明细标志', kind: 'number', min: 0 },
      { name: 'serial_no_model_flag', label: '序列号标志', kind: 'number', min: 0 },
      { name: 'purchase_book_flag', label: '采购册标志', kind: 'number', min: 0 },
    ],
    whitelist: FIELD_LABELS.purchase,
  },
  {
    key: 'processed',
    label: '加工单',
    path: '/api/v1/jd-order/processed-orders',
    fields: [
      { name: 'processed_no', label: '加工单号' },
      { name: 'erp_processed_no', label: 'ERP 加工单号' },
    ],
    whitelist: FIELD_LABELS.processed,
  },
  {
    key: 'operateRelation',
    label: '作业关联',
    path: '/api/v1/jd-order/operate-relations',
    fields: [
      { name: 'erp_order_no', label: 'ERP 订单号' },
      { name: 'order_type', label: '单据类型' },
    ],
    whitelist: FIELD_LABELS.operateRelation,
  },
  {
    key: 'deliveryTime',
    label: '配送时效',
    path: '/api/v1/jd-order/delivery-times',
    fields: [
      { name: 'waybill_no', label: '运单号' },
      { name: 'customer_code', label: '客户编码' },
      { name: 'shunt', label: '分单标识' },
      { name: 'dynamic_time_flag', label: '动态时效标志' },
    ],
    whitelist: FIELD_LABELS.deliveryTime,
  },
  {
    key: 'cityTrack',
    label: '同城轨迹',
    path: '/api/v1/jd-order/city-tracks',
    fields: [
      { name: 'delivery_no', label: '配送单号' },
      { name: 'customer_code', label: '客户编码' },
    ],
    whitelist: FIELD_LABELS.cityTrack,
  },
];

const CONFIG: JdQueryPageConfig = {
  title: '京东专业单据查询',
  subtitle:
    '调整单 / 销毁单 / 异常单 / 采购单 / 加工单 / 作业关联 / 配送时效 / 同城轨迹；全部为只读渠道查询，不计入公司总订单，也不产生任何写操作。',
  icon: <FileSearchOutlined />,
  options: QUERIES,
  formLayout: 'vertical',
  queryBar: 'compact',
  remountForm: true,
  headerTags: (
    <Space size={4}>
      <Tag color={TOOL_CATEGORY_TAG_COLOR}>系统渠道工具</Tag>
      <Tag color={READ_ONLY_TAG_COLOR}>只读</Tag>
    </Space>
  ),
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
    denied: () => '订单查询权限未开通',
    failed: () => '查询未完成，请查看下方提示',
  },
  footer: (
    <Card size="small">
      <Typography.Text type="secondary">
        结果仅展示白名单业务字段，收件人、电话、地址等个人数据不会出现；页面为调试与对账用，业务调用请走受审计的履约用例。
      </Typography.Text>
    </Card>
  ),
};

export default function JdOrderQueryPage() {
  return <JdQueryPage config={CONFIG} />;
}
