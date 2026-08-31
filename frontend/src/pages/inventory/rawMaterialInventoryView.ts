/**
 * 原料库存页的呈现口径（票 06 落骨架，票 09 接真数据；spec `unified-business-frontend` D6、D7）。
 *
 * 票 08 的远端只读网关落地后，后端已提供 `GET /api/v1/raw-material-inventory/stock`：
 * 模块开放即有取数路径，票 06 时代的「已接通但没接上取数」中间态不复存在，状态机据此收敛为
 * checking / ready / unavailable 三态。页面的价值仍然首先落在措辞上——
 *
 * **「读不到原料」不是「没有原料」。** 运营看见空白或 0 会当成库存耗尽并据此下采购决定，
 * 那是把故障读成了事实。因此本模块规定：取数失败时页面一个结存数字都不显示，既不显示 0，
 * 也不显示「暂无数据」的空表，只显示一句说清「读不到」的话。反过来，**读取成功但在库物料
 * 为零是合法业务事实**（「读到了没有」），必须用与失败可区分的措辞说出来。
 *
 * 失败原因分四类（spec D2 要求远端只读网关给出各自独立的稳定错误码）：未配置 / 不可用 /
 * 鉴权失败 / 契约漂移。四类分开呈现不是为了好看，是因为处置不同——「未配置」要去做部署，
 * 「鉴权失败」要换令牌，「契约漂移」是上游改了结构、必须停在这里而不是猜着解析出一份可能
 * 错的结存，「不可用」才是那个可以稍后重试的。
 *
 * **本模块负责「码表 → 原因 → 怎么说」。** 票 06 预告的接线点在此兑现：后端错误信封的
 * `business_code`（RAW_MATERIAL_*）由 {@link rawMaterialReadFailureReasonFromBusinessCode}
 * 映射到四类原因，四类的措辞不再变动；认不出的码一律归 UNKNOWN——仍是「读不到」，不猜含义。
 */

import { ApiError } from '../../api/client.ts';
import type { BusinessModuleStatus } from '../../components/layout/useBusinessModules.ts';

/** 取数失败的原因分类；每一类都是「读不到原料」，一类都不得被当成「没有原料」。 */
export type RawMaterialReadFailureReason =
  /** 未配置：本部署还没有打开原料库存的只读接入（与后端模块开放清单同一判据）。 */
  | 'NOT_CONFIGURED'
  /** 不可用：上游连不上或超时，取数没拿到事实。 */
  | 'UNAVAILABLE'
  /** 鉴权失败：上游拒绝了本系统的只读令牌。 */
  | 'UNAUTHORIZED'
  /** 契约漂移：上游返回结构与约定不一致，宁可停下也不猜着解析。 */
  | 'CONTRACT_DRIFT'
  /** 未识别的失败：仍然是「读不到」，不是「没有」。 */
  | 'UNKNOWN';

/** 页面状态：模块清单只裁定「有没有取数路径」；取数本身的成败由页面按码表另行归类。 */
export type RawMaterialInventoryState =
  /** 模块开放清单还没落定：没有证据，不对用户断言任何接通结论。 */
  | { kind: 'checking' }
  /** 拿不到原料事实，附带可区分的原因。 */
  | { kind: 'unavailable'; reason: RawMaterialReadFailureReason }
  /** 模块已开放：取数路径存在（票 09），页面据此调用结存端点。 */
  | { kind: 'ready' };

export interface RawMaterialNotice {
  tone: 'info' | 'warning' | 'error';
  title: string;
  description: string;
}

/** 清单未落定时的措辞：只说在确认，不说「未接通」——那是一句此刻拿不出证据的话。 */
export const RAW_MATERIAL_CHECKING_HINT = '正在确认原料库存是否已接通…';

/** 取数进行中的措辞：还没有答案，既不断言失败也不预先渲染任何结存。 */
export const RAW_MATERIAL_LOADING_HINT = '正在读取原料结存…';

/**
 * 所有「拿不到数」的措辞共用的收尾句。
 *
 * 它是本票的核心交付物：把「读不到」与「没有」显式分开，并禁止把空白当成零库存。
 * 每一种拿不到数的情形都必须带上它——少带一处，那一处就会被读成「原料没了」。
 */
export const RAW_MATERIAL_NOT_A_ZERO_NOTICE =
  '这是「读不到原料」，不是「没有原料」：接入恢复前本页不显示任何结存数字，请不要按零库存理解。';

/** 本期范围：只读，且不回答「这单原料够不够」——没有连接键就没有可计算的基础（spec D6）。 */
export const RAW_MATERIAL_SCOPE_NOTE =
  '本页是原料库存的只读视图，只呈现原料、批次与结存事实：不做出入库写入，也不回答「这单原料够不够」。'
  + '子牧的 SKU 与上游原料之间目前没有任何连接键——商品档案上的商品原料是自由文本，静态礼包不单独计库存，'
  + '在映射建立之前，任何原料占用与消耗的推断都没有可计算的基础，本页因此不做这类推断。';

/** 各原因的措辞主体；收尾句由 `rawMaterialInventoryNotice` 统一追加，保证一处都不漏。 */
interface RawMaterialFailureCopy {
  tone: RawMaterialNotice['tone'];
  title: string;
  body: string;
}

const FAILURE_COPY: Readonly<Record<RawMaterialReadFailureReason, RawMaterialFailureCopy>> = {
  NOT_CONFIGURED: {
    tone: 'warning',
    title: '原料库存未接通',
    body: '本部署还没有配置原料库存（yuanliaokc）的只读接入，系统此刻读不到任何原料与批次。'
      + '接通后侧边栏「商品与主数据」里会重新出现「原料库存」入口。',
  },
  UNAVAILABLE: {
    tone: 'error',
    title: '原料库存暂时读不到',
    body: '上游原料库存连不上或响应超时，本次取数没有拿到任何原料事实。'
      + '可稍后重试；持续失败请联系管理员确认上游服务状态。',
  },
  UNAUTHORIZED: {
    tone: 'error',
    title: '原料库存鉴权被拒',
    body: '上游拒绝了本系统的只读令牌，取数没有执行。请联系管理员核对并更换读取令牌——'
      + '本页不会绕过鉴权取数。',
  },
  CONTRACT_DRIFT: {
    tone: 'error',
    title: '原料库存返回结构与约定不一致',
    body: '上游返回的结构与本系统约定的契约对不上，系统按契约漂移停在这里，'
      + '而不是猜着解析出一份可能错的结存。请联系管理员核对上游版本。',
  },
  UNKNOWN: {
    tone: 'error',
    title: '原料库存读取失败',
    body: '取数失败，但失败原因不在已知分类里，本系统不替它猜含义。请联系管理员按后端日志定位。',
  },
};

/**
 * 码表（票 06 预告、票 09 兑现的接线点）：后端错误信封 `business_code` → 失败原因。
 *
 * 只认后端契约里的四个稳定码；缺码、空码或认不出的码一律 UNKNOWN——那仍是「读不到」，
 * 呈现层不得因为认不出码就退化成空表或零结存。
 */
const REASON_BY_BUSINESS_CODE: Readonly<Record<string, RawMaterialReadFailureReason>> = {
  RAW_MATERIAL_NOT_CONFIGURED: 'NOT_CONFIGURED',
  RAW_MATERIAL_UNAVAILABLE: 'UNAVAILABLE',
  RAW_MATERIAL_UNAUTHORIZED: 'UNAUTHORIZED',
  RAW_MATERIAL_CONTRACT_DRIFT: 'CONTRACT_DRIFT',
};

export function rawMaterialReadFailureReasonFromBusinessCode(
  businessCode: string | null | undefined,
): RawMaterialReadFailureReason {
  if (!businessCode) return 'UNKNOWN';
  return REASON_BY_BUSINESS_CODE[businessCode] ?? 'UNKNOWN';
}

/**
 * 取数异常 → 失败原因：后端 ApiError 走码表；网络级失败与其他异常一律 UNKNOWN。
 * 网络失败不映射到 UNAVAILABLE——那句措辞断言的是「上游不可用」，而请求根本没到网关时
 * 我们并没有这个证据，宁可说「原因不在已知分类里」也不编一个更具体的原因。
 */
export function rawMaterialReadFailureReason(error: unknown): RawMaterialReadFailureReason {
  if (error instanceof ApiError) {
    return rawMaterialReadFailureReasonFromBusinessCode(error.body.business_code);
  }
  return 'UNKNOWN';
}

/**
 * 读取成功且 items 为空时的措辞：这是「读到了没有」，不是「读不到」。
 * 与失败措辞必须可区分——这里明说「读取已成功」，并且绝不携带失败收尾句。
 * 带关键词时空结果只说「无匹配」，不升级成「无在库物料」：搜索没搜到 ≠ 库房是空的。
 */
export function rawMaterialEmptyStockText(keyword?: string): string {
  const trimmed = keyword?.trim();
  if (trimmed) {
    return `本次读取已成功，但没有匹配「${trimmed}」的在库物料；可更换关键词或清空后查看全部。`;
  }
  return '当前无在库物料：本次读取已成功，上游此刻没有任何在库原料记录。';
}

/** 结存状态呈现；tone 词汇与 inventoryOverviewView 一致，由页面映射为 antd Tag 颜色。 */
export interface RawMaterialStockStatusPresentation {
  label: string;
  tone: 'neutral' | 'info' | 'success' | 'warning' | 'error';
}

/**
 * 上游 status 口径：normal / low / near_expiry / frozen。
 * near_expiry 与 low 按票面要求用警示色；frozen 意味着结存整体不可动用，给错误色级；
 * 认不出的值原文中性呈现——上游新增一档不该被本页翻译成任何更乐观或更悲观的含义。
 */
export function rawMaterialStockStatusPresentation(status: string): RawMaterialStockStatusPresentation {
  switch (status) {
    case 'normal':
      return { label: '正常', tone: 'success' };
    case 'low':
      return { label: '低库存', tone: 'warning' };
    case 'near_expiry':
      return { label: '临期', tone: 'warning' };
    case 'frozen':
      return { label: '冻结', tone: 'error' };
    default:
      return { label: status, tone: 'neutral' };
  }
}

/**
 * 页面状态只由「模块接通与否」决定——判据取外壳读到的那份后端清单（`useBusinessModuleStatus`），
 * 不在页面里另立标准：菜单里有没有入口、页面上说没说接通，必须是同一句话的两种呈现。
 */
export function rawMaterialInventoryState(module: BusinessModuleStatus): RawMaterialInventoryState {
  if (module === 'pending') return { kind: 'checking' };
  // 后端只在只读网关配置齐备时才把本模块列进开放清单，因此「清单里没有」= 未配置。
  if (module === 'closed') return { kind: 'unavailable', reason: 'NOT_CONFIGURED' };
  return { kind: 'ready' };
}

/**
 * 失败原因 → 提示（总是有话可说）：模块未开放与取数失败共用同一张措辞表——
 * 「清单里没有」和「取数拿到 RAW_MATERIAL_NOT_CONFIGURED」本就该说同一句话。
 */
export function rawMaterialReadFailureNotice(reason: RawMaterialReadFailureReason): RawMaterialNotice {
  const { tone, title, body } = FAILURE_COPY[reason];
  return { tone, title, description: body + RAW_MATERIAL_NOT_A_ZERO_NOTICE };
}

/** 状态 → 提示；`checking` 与 `ready` 返回 null（前者显示确认中，后者由取数结果说话）。 */
export function rawMaterialInventoryNotice(state: RawMaterialInventoryState): RawMaterialNotice | null {
  if (state.kind === 'checking' || state.kind === 'ready') return null;
  return rawMaterialReadFailureNotice(state.reason);
}
