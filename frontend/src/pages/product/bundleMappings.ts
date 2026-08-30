/**
 * 主数据 · 来源礼包映射页签的纯逻辑：礼包下拉选项、列表展示字段、写请求载荷。
 *
 * 与页面组件拆开放在这里，是为了让 node:test 能直接跑（test/bundleMappings.test.ts）。
 * 因此本文件只允许 type-only 导入——测试用 --experimental-strip-types 直接加载 .ts，
 * 运行期的 `@/` 别名在那里解析不了。
 */

import type { MasterDataRecord, ProductBundleRecord, SourceChannel } from '@/api/types';

export interface BundleOption {
  value: string;
  label: string;
}

export interface BundleMappingPresentation {
  /** 来源渠道；后端投影缺字段时为空串，由调用方决定怎么显示。 */
  sourceChannel: SourceChannel | '';
  /** 来源礼包编号（大者这类没有编号的渠道按商品名称填）。 */
  sourceBundleRef: string;
  /**
   * 来源礼包名称。后端投影在映射没存自定义名称时回落到礼包名，这里拿到的就是那个
   * 回落值——两者在响应里区分不出来，编辑时原样带出，保存即把显示的名字存成自定义名称。
   */
  sourceBundleName: string;
  /** 目标礼包 id；缺失时为空串。 */
  bundleId: string;
}

export interface BundleMappingCreateBody {
  source_channel: string;
  source_bundle_ref: string;
  source_bundle_name?: string;
  bundle_id: string;
  active?: boolean;
}

export interface BundleMappingUpdateBody {
  expected_version: number;
  bundle_id?: string;
  source_bundle_name?: string;
  active?: boolean;
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function attribute(record: MasterDataRecord, key: string): unknown {
  if (key in record) return (record as unknown as Record<string, unknown>)[key];
  return record.attributes?.[key];
}

/**
 * 可绑定的礼包下拉选项：只列 ACTIVE 礼包。
 * 后端对草稿/停用礼包一律 422（BUNDLE_NOT_ACTIVE），在这里就不给选，省得填完才被拒。
 */
export function activeBundleOptions(bundles: ProductBundleRecord[]): BundleOption[] {
  return bundles
    .filter((bundle) => bundle.attributes?.status === 'ACTIVE')
    .map((bundle) => ({ value: bundle.id, label: `${bundle.name}（${bundle.code}）` }));
}

/**
 * 礼包 id → 展示名的查表，列表里把目标礼包 id 翻成人看得懂的名字。
 * 覆盖全部礼包（含非 ACTIVE）：历史映射可能指向已停用礼包，列表还是要照实显示。
 */
export function bundleLabelById(bundles: ProductBundleRecord[]): Record<string, string> {
  return Object.fromEntries(bundles.map((bundle) => [bundle.id, `${bundle.name}（${bundle.code}）`]));
}

/** 把来源礼包映射记录摊平成列表要用的展示字段。 */
export function bundleMappingPresentation(record: MasterDataRecord): BundleMappingPresentation {
  const channel = text(attribute(record, 'source_channel'));
  return {
    sourceChannel: (channel as SourceChannel) || '',
    sourceBundleRef: text(attribute(record, 'source_bundle_ref')) || record.code,
    sourceBundleName: record.name,
    bundleId: text(attribute(record, 'bundle_id')),
  };
}

/**
 * 新建请求体。包装乘数不传——一期恒为 1，由后端落库，前端多传一个只会传错。
 */
export function bundleMappingCreateBody(values: Record<string, unknown>): BundleMappingCreateBody {
  const sourceBundleName = text(values.source_bundle_name);
  return {
    source_channel: text(values.source_channel),
    source_bundle_ref: text(values.source_bundle_ref),
    ...(sourceBundleName ? { source_bundle_name: sourceBundleName } : {}),
    bundle_id: text(values.bundle_id),
    ...(typeof values.active === 'boolean' ? { active: values.active } : {}),
  };
}

/**
 * 更新请求体：expected_version 必带（乐观锁），其余字段没填就不传，避免把「没改」
 * 发成「改成空」。名称是例外——表单里出现过就照原样带上，空串是明确的清空语义。
 */
export function bundleMappingUpdateBody(values: Record<string, unknown>): BundleMappingUpdateBody {
  const bundleId = text(values.bundle_id);
  return {
    expected_version: Number(values.expected_version),
    ...(bundleId ? { bundle_id: bundleId } : {}),
    ...(typeof values.source_bundle_name === 'string'
      ? { source_bundle_name: values.source_bundle_name.trim() }
      : {}),
    ...(typeof values.active === 'boolean' ? { active: values.active } : {}),
  };
}
