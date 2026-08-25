/**
 * 履约中心 · 京东基础信息查询（GET /api/v1/jd-basicinfo/*）。
 * 只读查询页：客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖范围 7 个接口。
 * 服务端已剔除联系人、电话、地址等个人信息，本页只展示白名单业务字段；
 * 未授权（业务码 2001）明确提示「权限未开通」，不当作系统错误。
 *
 * issue #40：骨架收敛到共享 JdQueryPage（配置驱动），本文件只声明配置。
 */

import { CloudServerOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import { jdBasicInfoApi, type BasicInfoQuery } from '@/api/basicinfoEndpoints';
import { jdConnectionSemantic } from '@/pages/shared/semanticStatus';
import JdQueryPage, { isJdBusinessCodeDenied } from '@/pages/shared/JdQueryPage';
import type { JdQueryPageConfig } from '@/pages/shared/JdQueryPage';

/** 与 backend 白名单字段展示口径一致：服务端脱敏后，页面只透出这些业务字段。 */
const QUERIES: JdQueryPageConfig['options'] = [
  {
    key: 'customers',
    label: '客户查询',
    description: '按客户编码/名称分页查询客户档案（联系人、电话、地址已在服务端剔除）。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'customer_no', label: '客户编码', placeholder: '精确匹配客户编码' },
      { name: 'customer_name', label: '客户名称', placeholder: '模糊匹配客户名称' },
      { name: 'page_size', label: '每页条数', kind: 'number' },
      { name: 'current_page', label: '页码', kind: 'number' },
    ],
    run: (q) => jdBasicInfoApi.customers(q as BasicInfoQuery),
    whitelist: {
      customerno: '客户编码',
      customername: '客户名称',
      ownerno: '事业部编码',
      sellerno: '商家编码',
      sellername: '商家名称',
      customertype: '客户类型',
      transfertype: '移库类型',
      warehousename: '仓库名称',
      isdirectdelivery: '是否直发',
      remark: '备注',
      licenseunitno: '证照单位编码',
      licenseunit: '证照单位',
    },
  },
  {
    key: 'sellers',
    label: '商家查询',
    description: '按当前授权商家（pin）查询商家关联的店铺与仓库。',
    fields: [],
    run: () => jdBasicInfoApi.sellers(),
    whitelist: {
      ownerno: '事业部编码',
      shopnos: '店铺编码列表',
      warehousenos: '仓库编码列表',
    },
  },
  {
    key: 'shops',
    label: '店铺查询',
    description: '按店铺编码/ERP 店铺编码查询店铺信息（店铺地址等个人信息已剔除）。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'shop_no', label: '店铺编码', placeholder: '京东店铺编码' },
      { name: 'erp_shop_no', label: 'ERP 店铺编码', placeholder: 'ERP 侧店铺编码' },
    ],
    run: (q) => jdBasicInfoApi.shops(q as BasicInfoQuery),
    whitelist: {
      ownerno: '事业部编码',
      shopno: '店铺编码',
      shopname: '店铺名称',
      erpshopno: 'ERP 店铺编码',
      salesplatformsourceno: '销售平台来源编码',
      type: '店铺类型',
      status: '状态',
      salesplatformshopno: '销售平台店铺编码',
      customercode: '客户编码',
      outboundrules: '出库规则',
      biztype: '业务类型',
    },
  },
  {
    key: 'shop-goods',
    label: '店铺商品查询',
    description: '按店铺/商品编码分页查询店铺商品映射关系。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'shop_no', label: '店铺编码', placeholder: '京东店铺编码' },
      { name: 'goods_no', label: '商品编码', placeholder: '京东商品编码' },
      { name: 'erp_goods_no', label: 'ERP 商品编码', placeholder: 'ERP 侧商品编码' },
      { name: 'sales_platform_goods_no', label: '销售平台商品编码', placeholder: '销售平台商品编码' },
      { name: 'shop_goods_no_min', label: '店铺商品编码起始', placeholder: '从该编码开始向后取' },
      { name: 'page_size', label: '每页条数', kind: 'number' },
      { name: 'current_page', label: '页码', kind: 'number' },
    ],
    run: (q) => jdBasicInfoApi.shopGoods(q as BasicInfoQuery),
    whitelist: {
      goodsno: '商品编码',
      shopgoodsno: '店铺商品编码',
      erpgoodssign: 'ERP 商品标识',
      erpgoodsno: 'ERP 商品编码',
      ownerno: '事业部编码',
      salesplatformgoodsno: '销售平台商品编码',
      shopname: '店铺名称',
      shopgoodsname: '店铺商品名称',
      shopno: '店铺编码',
    },
  },
  {
    key: 'suppliers',
    label: '供应商查询',
    description: '按供应商编码列表查询供应商档案（联系人、电话、地址已在服务端剔除）。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'supplier_nos', label: '供应商编码', placeholder: '多个用英文逗号分隔' },
      { name: 'isv_supplier_nos', label: 'ISV 供应商编码', placeholder: '多个用英文逗号分隔' },
    ],
    run: (q) => jdBasicInfoApi.suppliers(q as BasicInfoQuery),
    whitelist: {
      ownerno: '事业部编码',
      ownername: '事业部名称',
      erpsupplierno: 'ERP 供应商编码',
      supplierno: '供应商编码',
      suppliername: '供应商名称',
      suppliertype: '供应商类型',
      status: '状态',
      medicineenterprisenature: '医药企业性质',
      socialcreditcode: '统一社会信用代码',
    },
  },
  {
    key: 'goods-categories',
    label: '商品类目查询',
    description: '按类目编码逐级查询商品类目（一级/二级/三级）。',
    fields: [
      { name: 'first_category_code', label: '一级类目编码', kind: 'number' },
      { name: 'second_category_code', label: '二级类目编码', kind: 'number' },
      { name: 'third_category_code', label: '三级类目编码', kind: 'number' },
    ],
    run: (q) => jdBasicInfoApi.goodsCategories(q as BasicInfoQuery),
    whitelist: {
      firstcategorycode: '一级类目编码',
      firstcategoryname: '一级类目名称',
      secondcategorycode: '二级类目编码',
      secondcategoryname: '二级类目名称',
      thirdcategorycode: '三级类目编码',
      thirdcategoryname: '三级类目名称',
    },
  },
  {
    key: 'warehouse-coverages',
    label: '仓库覆盖范围',
    description: '按收货区域查询可覆盖的仓库编码。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'province', label: '省', placeholder: '如 浙江省' },
      { name: 'city', label: '市', placeholder: '如 杭州市' },
      { name: 'county', label: '区/县', placeholder: '如 西湖区' },
      { name: 'town', label: '镇/街道', placeholder: '如 文新街道' },
      { name: 'detail_address', label: '详细地址', placeholder: '可选，用于精确匹配覆盖范围' },
    ],
    run: (q) => jdBasicInfoApi.warehouseCoverages(q as BasicInfoQuery),
    whitelist: {
      warehouseno: '仓库编码',
    },
  },
];

const CONFIG: JdQueryPageConfig = {
  title: '京东基础信息查询',
  subtitle: '只读查询客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖范围；结果已脱敏，只展示白名单业务字段。',
  icon: <CloudServerOutlined />,
  options: QUERIES,
  queryBar: 'inline',
  selectWidth: 220,
  isPermissionDenied: isJdBusinessCodeDenied,
  mock: ({ mode }) => mode === 'MOCK',
  result: {
    requestId: 'top',
    emptyText: '本次结果没有可公开展示的业务字段。',
    successTitle: ({ label, mock }) => `${label}完成${mock ? '（模拟数据，不代表真实权限）' : ''}`,
    denied: {
      description: () =>
        '当前京东账号未开通该查询接口的权限（业务码 2001），请联系管理员在京东物流开放平台申请后重试；这不是系统故障。',
    },
    failed: {
      alertType: 'warning',
      description: (r) => (r.message ? `业务码 ${r.business_code}：${r.message}` : `业务码 ${r.business_code}`),
    },
  },
  feedback: {
    success: (label) => `${label}完成`,
    denied: () => '该接口权限未开通',
    failed: () => '查询未完成',
  },
  status: {
    load: () => jdBasicInfoApi.status(),
    usePageState: true,
    errorTitle: '连接状态加载失败',
    disableQueryUntilReady: true,
    renderTag: (data) => {
      const mode = data?.client_mode;
      const liveReady = data?.live_ready ?? false;
      if (mode === 'REAL' && liveReady) return <Tag color={jdConnectionSemantic(true, mode)}>真实连接已就绪</Tag>;
      if (mode === 'REAL') return <Tag color={jdConnectionSemantic(false, mode)}>真实连接未就绪</Tag>;
      if (mode === 'MOCK') return <Tag>模拟模式（不代表真实权限）</Tag>;
      return <Tag>连接状态未知</Tag>;
    },
  },
};

export default function JdBasicInfoQueryPage() {
  return <JdQueryPage config={CONFIG} />;
}
