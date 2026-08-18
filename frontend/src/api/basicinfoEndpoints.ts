/**
 * 京东 ISC 基础信息查询域（只读）—— 客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖范围。
 * 与 backend JdBasicInfoController（/api/v1/jd-basicinfo）一一对应；主 agent 接线时可按需并入 endpoints.ts。
 */

import { apiRequest, type QueryValue } from './client';
import type { JdQueryResult } from './types';

/** 各查询接口共用的可选查询参数；空值会被 apiRequest 忽略。 */
export interface BasicInfoQuery {
  owner_no?: string;
  customer_no?: string;
  customer_name?: string;
  shop_no?: string;
  erp_shop_no?: string;
  goods_no?: string;
  erp_goods_no?: string;
  sales_platform_goods_no?: string;
  shop_goods_no_min?: string;
  supplier_nos?: string;
  isv_supplier_nos?: string;
  first_category_code?: number;
  second_category_code?: number;
  third_category_code?: number;
  province?: string;
  city?: string;
  county?: string;
  town?: string;
  detail_address?: string;
  page_size?: number;
  current_page?: number;
}

export interface JdBasicInfoStatus {
  client_mode: 'MOCK' | 'REAL';
  credentials_configured: boolean;
  live_ready: boolean;
}

export const jdBasicInfoApi = {
  status: () => apiRequest<JdBasicInfoStatus>('/api/v1/jd-basicinfo/status'),
  customers: (q: BasicInfoQuery = {}) =>
    apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/customers', { params: q as Record<string, QueryValue> }),
  sellers: () => apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/sellers'),
  shops: (q: BasicInfoQuery = {}) =>
    apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/shops', { params: q as Record<string, QueryValue> }),
  shopGoods: (q: BasicInfoQuery = {}) =>
    apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/shop-goods', { params: q as Record<string, QueryValue> }),
  suppliers: (q: BasicInfoQuery = {}) =>
    apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/suppliers', { params: q as Record<string, QueryValue> }),
  goodsCategories: (q: BasicInfoQuery = {}) =>
    apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/goods-categories', { params: q as Record<string, QueryValue> }),
  warehouseCoverages: (q: BasicInfoQuery = {}) =>
    apiRequest<JdQueryResult>('/api/v1/jd-basicinfo/warehouse-coverages', { params: q as Record<string, QueryValue> }),
};
