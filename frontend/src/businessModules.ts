/**
 * 业务模块 = 「这块业务能力今天接通了吗」（票 03 / spec unified-business-frontend D3）。
 *
 * 后端 `GET /api/v1/business-modules` 按各模块自己的接通开关下发**已开放**清单，
 * 外壳启动时读取它并据此过滤导航树，让菜单不再承诺系统给不出的能力。
 *
 * **与 MCP 的 `MCP_MODULES` 不是同一件事**：那个划的是「哪些 MCP 工具暴露给外部 Agent」的
 * 访问面，这里答的是「功能可用性」。两者取值空间不同、判据不同，不得互相推导或合并。
 */

/** 已知业务模块标识；与后端 `BusinessModule` 枚举一一对应，改名等同于破坏契约。 */
export const BUSINESS_MODULE_IDS = [
  /** 客户中心（kehuzx）：客户档案与客户跟进的权威来源。 */
  'customer-center',
] as const;

export type BusinessModuleId = (typeof BUSINESS_MODULE_IDS)[number];

/** 保守默认：还没读到清单、或读取失败时的开放集合——空集，未接通的模块一律不显示。 */
export const NO_OPEN_BUSINESS_MODULES: ReadonlySet<BusinessModuleId> = new Set<BusinessModuleId>();

const KNOWN_IDS: ReadonlySet<string> = new Set<string>(BUSINESS_MODULE_IDS);

function isKnownModuleId(value: unknown): value is BusinessModuleId {
  return typeof value === 'string' && KNOWN_IDS.has(value);
}

/**
 * 解析 `GET /api/v1/business-modules` 的载荷。
 *
 * 边界校验从保守侧从严：载荷不是预期形状就当作「没有模块开放」，而不是猜一个更宽松的解释——
 * 把读不懂的响应当成「全开」会让菜单重新承诺系统给不出的能力。未知标识同样忽略：
 * 后端多下发一个本版本还不认识的模块，不该点亮本版本的任何菜单。
 */
export function parseOpenBusinessModules(payload: unknown): ReadonlySet<BusinessModuleId> {
  if (typeof payload !== 'object' || payload === null) return NO_OPEN_BUSINESS_MODULES;
  const modules = (payload as { modules?: unknown }).modules;
  if (!Array.isArray(modules)) return NO_OPEN_BUSINESS_MODULES;
  return new Set(modules.filter(isKnownModuleId));
}
