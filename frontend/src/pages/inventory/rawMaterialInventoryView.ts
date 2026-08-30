/**
 * 原料库存页的呈现口径（票 06 / spec `unified-business-frontend` D6、D7）。
 *
 * 这条链路的第一颗曳光弹只打通入口、路由与状态：上游原料库存（yuanliaokc）今天只有 stdio
 * MCP 面、没有 HTTP 接口，子牧后端在容器里够不着（D7），所以本页**没有任何取数**。
 * 也正因为没有数据，页面的全部价值都落在措辞上——
 *
 * **「读不到原料」不是「没有原料」。** 运营看见空白或 0 会当成库存耗尽并据此下采购决定，
 * 那是把故障读成了事实。因此本模块规定：接入不通时页面一个结存数字都不显示，既不显示 0，
 * 也不显示「暂无数据」的空表，只显示一句说清「读不到」的话。
 *
 * 失败原因分四类（spec D2 要求远端只读网关给出各自独立的稳定错误码）：未配置 / 不可用 /
 * 鉴权失败 / 契约漂移。四类分开呈现不是为了好看，是因为处置不同——「未配置」要去做部署，
 * 「鉴权失败」要换令牌，「契约漂移」是上游改了结构、必须停在这里而不是猜着解析出一份可能
 * 错的结存，「不可用」才是那个可以稍后重试的。
 *
 * **本模块只负责「原因 → 怎么说」。** 「网关稳定错误码 → 原因」的映射留给票 08/09：
 * 网关和它的错误码此刻还不存在，照着不存在的契约先写一张码表，只会写出一张必然漂移的表。
 * 票 08 落地时把码表接在这里，四类的措辞不必再动。
 */

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

/** 页面状态：三种都不含结存数据——本票不接真实数据，接上是票 09 的事。 */
export type RawMaterialInventoryState =
  /** 模块开放清单还没落定：没有证据，不对用户断言任何接通结论。 */
  | { kind: 'checking' }
  /** 拿不到原料事实，附带可区分的原因。 */
  | { kind: 'unavailable'; reason: RawMaterialReadFailureReason }
  /** 上游已接通，但本版本还没有接上取数（票 08 落地到票 09 之间的真实中间态）。 */
  | { kind: 'connected-without-read-path' };

export interface RawMaterialNotice {
  tone: 'info' | 'warning' | 'error';
  title: string;
  description: string;
}

/** 清单未落定时的措辞：只说在确认，不说「未接通」——那是一句此刻拿不出证据的话。 */
export const RAW_MATERIAL_CHECKING_HINT = '正在确认原料库存是否已接通…';

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

const CONNECTED_WITHOUT_READ_PATH: RawMaterialNotice = {
  tone: 'warning',
  title: '原料库存已接通，但本页还没有接上取数',
  description: '入口与状态先行落地，原料、批次与结存的读取要等只读网关接线完成。'
    + RAW_MATERIAL_NOT_A_ZERO_NOTICE,
};

/**
 * 页面状态只由「模块接通与否」决定——判据取外壳读到的那份后端清单（`useBusinessModuleStatus`），
 * 不在页面里另立标准：菜单里有没有入口、页面上说没说接通，必须是同一句话的两种呈现。
 */
export function rawMaterialInventoryState(module: BusinessModuleStatus): RawMaterialInventoryState {
  if (module === 'pending') return { kind: 'checking' };
  // 后端只在只读网关配置齐备时才把本模块列进开放清单，因此「清单里没有」= 未配置。
  if (module === 'closed') return { kind: 'unavailable', reason: 'NOT_CONFIGURED' };
  return { kind: 'connected-without-read-path' };
}

/** 状态 → 提示；`checking` 返回 null（页面此时显示加载态，不作任何断言）。 */
export function rawMaterialInventoryNotice(state: RawMaterialInventoryState): RawMaterialNotice | null {
  if (state.kind === 'checking') return null;
  if (state.kind === 'connected-without-read-path') return CONNECTED_WITHOUT_READ_PATH;
  const { tone, title, body } = FAILURE_COPY[state.reason];
  return { tone, title, description: body + RAW_MATERIAL_NOT_A_ZERO_NOTICE };
}
