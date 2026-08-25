/**
 * 履约中心 · 京东序列号查询（只读 SDK 作业面，GET /api/v1/jd-serial/*）。
 * 四个只读查询：序列号查询（mall）/ 条件查询（condition）/ 流向查询（flow）/ 内部查询（inside）。
 * 页面仅作为“系统管理 → 京东工具”的原始查询入口，不作为库存一级业务板块。
 * 后端 MOCK 模式（app.jd.client-mode=MOCK，默认）返回稳定假数据，business_code=MOCK_SUCCESS。
 * 未授权（如业务码 2001）时明确提示「权限未开通」，不当作系统错误。
 *
 * issue #40：骨架收敛到共享 JdQueryPage（配置驱动），本文件只声明配置。
 */

import { SearchOutlined } from '@ant-design/icons';
import { Space, Tag } from 'antd';
import { READ_ONLY_TAG_COLOR } from '@/pages/shared/semanticStatus';
import JdQueryPage from '@/pages/shared/JdQueryPage';
import type { JdQueryPageConfig } from '@/pages/shared/JdQueryPage';
import { jdSerialQueryPrefill, type JdSerialQueryKind } from './jdQueryPrefill';

/** 白名单：normalized 字段名（去符号、小写）→ 中文标签。页面只展示这些已确认的业务字段。 */
const FIELD_LABELS: Record<string, string> = {
  sku: 'SKU',
  sn: '序列号',
  serial: '序列号',
  ownerno: '事业部编码',
  ownername: '事业部名称',
  orderno: '订单号',
  operatetime: '操作时间',
  state: '状态',
  packagenumber: '包裹号',
  enterpriseorderno: '企业订单号',
  totalnum: '总条数',
  goodsno: '商品编码',
  biztype: '业务类型',
  biztypename: '业务类型名称',
  warehouseno: '仓库编码',
  warehousename: '仓库名称',
  createtime: '创建时间',
  outorderno: '出库单号',
  outwarehouseno: '出库仓编码',
  outwarehousename: '出库仓名称',
  outordertype: '出库单类型',
  outtime: '出库时间',
  intoorderno: '入库单号',
  intowarehouseno: '入库仓编码',
  intowarehousename: '入库仓名称',
  inordertype: '入库单类型',
  intotime: '入库时间',
  status: '状态',
  currentpage: '当前页',
  pagesize: '每页条数',
  serialnos: '序列号列表',
};

const KIND_WHITELISTS: Record<JdSerialQueryKind, Set<string>> = {
  mall: new Set(['sku', 'sn', 'ownerno', 'ownername', 'orderno', 'operatetime', 'state', 'packagenumber', 'enterpriseorderno', 'totalnum']),
  condition: new Set(['orderno', 'goodsno', 'serial', 'biztype', 'biztypename', 'warehouseno', 'warehousename', 'createtime', 'totalnum']),
  flow: new Set(['goodsno', 'serial', 'outorderno', 'outwarehouseno', 'outwarehousename', 'outordertype', 'outtime', 'intoorderno', 'intowarehouseno', 'intowarehousename', 'inordertype', 'intotime', 'status']),
  inside: new Set(['totalnum', 'currentpage', 'pagesize', 'serialnos']),
};

function whitelistOf(kind: JdSerialQueryKind): Record<string, string> {
  return Object.fromEntries([...KIND_WHITELISTS[kind]].map((key) => [key, FIELD_LABELS[key]]));
}

const QUERIES: JdQueryPageConfig['options'] = [
  {
    key: 'mall',
    label: '序列号查询',
    description: '按订单/时间范围分页查询京东商城序列号明细（queryJDMallSerialByPage）。',
    path: '/api/v1/jd-serial/mall',
    fields: [
      { name: 'order_no', label: '订单号', width: 190, placeholder: '京东订单号或系统单号' },
      { name: 'enterprise_order_no', label: '企业订单号', width: 190, placeholder: '可选' },
      { name: 'owner_no', label: '事业部编码', width: 190, placeholder: '留空使用配置的事业部' },
      { name: 'start_date', label: '开始日期', width: 190, placeholder: 'yyyy-MM-dd' },
      { name: 'end_date', label: '结束日期', width: 190, placeholder: 'yyyy-MM-dd' },
      { name: 'page_size', label: '每页条数', kind: 'number', width: 130 },
      { name: 'current_page', label: '当前页', kind: 'number', width: 130 },
    ],
    whitelist: whitelistOf('mall'),
  },
  {
    key: 'condition',
    label: '序列号条件查询',
    description: '按事业部、仓库、业务类型等条件分页查询序列号（queryPageSerialByOwnerNoAndCondition）。',
    path: '/api/v1/jd-serial/condition',
    fields: [
      { name: 'biz_type', label: '业务类型', kind: 'number', width: 130, placeholder: '如 10=出库' },
      { name: 'query_type', label: '查询类型', kind: 'number', width: 130 },
      { name: 'owner_no', label: '事业部编码', width: 190, placeholder: '留空使用配置的事业部' },
      { name: 'warehouse_no', label: '仓库编码', width: 190 },
      { name: 'start_date', label: '开始日期', width: 190, placeholder: 'yyyy-MM-dd' },
      { name: 'end_date', label: '结束日期', width: 190, placeholder: 'yyyy-MM-dd' },
      { name: 'current_page', label: '当前页', kind: 'number', width: 130 },
      { name: 'page_size', label: '每页条数', kind: 'number', width: 130 },
    ],
    whitelist: whitelistOf('condition'),
  },
  {
    key: 'flow',
    label: '序列号流向查询',
    description: '按商品编码 + 序列号查询出入库流向（querySerialBySkuAndSerial）。',
    path: '/api/v1/jd-serial/flow',
    fields: [
      { name: 'goods_no', label: '商品编码', width: 190, placeholder: '必填', required: true },
      { name: 'serial_no', label: '序列号', width: 190, placeholder: '必填', required: true },
      { name: 'query_type', label: '查询类型', kind: 'number', width: 130 },
    ],
    whitelist: whitelistOf('flow'),
  },
  {
    key: 'inside',
    label: '序列号内部查询',
    description: '按商品编码分页查询在库序列号（queryInStockSidBySku）。',
    path: '/api/v1/jd-serial/inside',
    fields: [
      { name: 'goods_no', label: '商品编码', width: 190, placeholder: '必填', required: true },
      { name: 'query_type', label: '查询类型', kind: 'number', width: 130 },
      { name: 'page_size', label: '每页条数', kind: 'number', width: 130 },
      { name: 'current_page', label: '当前页', kind: 'number', width: 130 },
    ],
    whitelist: whitelistOf('inside'),
  },
];

const DEFAULT_VALUES: Record<string, Record<string, unknown>> = {
  mall: { page_size: 20, current_page: 1 },
  condition: { current_page: 1, page_size: 20 },
  inside: { page_size: 20, current_page: 1 },
};

const CONFIG: JdQueryPageConfig = {
  title: '京东序列号查询',
  subtitle: '只读查询域：序列号查询 / 条件查询 / 流向查询 / 内部查询；不会在此页面发起任何写操作。',
  icon: <SearchOutlined />,
  options: QUERIES,
  queryBar: 'row',
  remountForm: true,
  defaults: DEFAULT_VALUES,
  prefill: (params) => jdSerialQueryPrefill(params),
  headerTags: <Tag color={READ_ONLY_TAG_COLOR}>只读</Tag>,
  collect: { maxRows: 24, dedupe: true, arrayJoin: ', ' },
  result: {
    requestId: 'none',
    emptyText: '本次结果没有可展示的业务字段。',
    successTitle: ({ label, mock }) => (
      <Space size={8}>
        <span>{label}完成</span>
        {mock ? <Tag>模拟数据（不代表真实权限）</Tag> : null}
      </Space>
    ),
    denied: {
      description: () =>
        '当前京东账号尚未开通该序列号查询接口的调用权限（业务码 2001）。请在京东商家后台或联系管理员完成接口授权后重试；这不是系统故障。',
    },
    failed: {
      title: (label) => `${label}未完成`,
      description: (r) =>
        `业务码 ${r.business_code}：${r.message ?? '未知错误'}。请核对查询条件后重试；如持续失败请联系管理员。`,
    },
  },
  feedback: {
    success: (label) => `${label}完成`,
    denied: () => '权限未开通，请先为当前京东账号开通序列号查询接口权限',
    failed: (label, r) => `${label}未完成（业务码 ${r.business_code}）`,
    clearResultOnError: false,
  },
};

export default function JdSerialQueryPage() {
  return <JdQueryPage config={CONFIG} />;
}
