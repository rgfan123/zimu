/**
 * 外壳启动时读取「当前已开放的业务模块」清单（票 03），并把同一份结果分发给页面（票 04）。
 *
 * 保守策略是硬要求：读到之前、读取失败、载荷畸形——一律按空集处理，未接通的模块不出现在菜单。
 * 反过来（读不到就全放行）会让菜单重新承诺系统给不出的能力，正是票 03 要消除的问题。
 *
 * 页面读的是**外壳读过的那一份**（React context），不再自己请求一次：受控入口「菜单里没有」
 * 与页面上「未接通」这两句话必须同源，否则两者会在读取失败等边界上各说各话。
 */

import { createContext, useContext, useEffect, useState } from 'react';
import { businessModulesApi } from '@/api/endpoints';
import {
  NO_OPEN_BUSINESS_MODULES,
  parseOpenBusinessModules,
  type BusinessModuleId,
} from '@/businessModules';

/** 外壳持有的清单读取结果。 */
export interface OpenBusinessModules {
  /** 已开放模块；未落定或读取失败时为空集（保守）。 */
  readonly modules: ReadonlySet<BusinessModuleId>;
  /**
   * 清单是否已落定（成功解析与读取失败都算落定）。
   *
   * 菜单过滤不看这一位——未落定即空集，宁可少显示。页面提示要看：还没有答案时不能对用户
   * 断言「未接通」，那是一句此刻拿不出证据的话（对应 `useBusinessModuleStatus` 的 `pending`）。
   */
  readonly settled: boolean;
}

/** 未落定态；模块级常量，引用稳定，可直接进 `useMemo` 依赖。 */
const PENDING_BUSINESS_MODULES: OpenBusinessModules = {
  modules: NO_OPEN_BUSINESS_MODULES,
  settled: false,
};

/** 读取失败：与「没有模块开放」同一处置（保守），但已落定——页面照常给出与菜单一致的提示。 */
const SETTLED_WITHOUT_OPEN_MODULES: OpenBusinessModules = {
  modules: NO_OPEN_BUSINESS_MODULES,
  settled: true,
};

/** 已开放的业务模块；整个外壳生命周期只读一次——模块开放与否是部署事实，不会在会话中途变化。 */
export function useOpenBusinessModules(): OpenBusinessModules {
  const [openModules, setOpenModules] = useState<OpenBusinessModules>(PENDING_BUSINESS_MODULES);

  useEffect(() => {
    const controller = new AbortController();
    businessModulesApi
      .open({ signal: controller.signal })
      .then((body) => {
        if (!controller.signal.aborted) {
          setOpenModules({ modules: parseOpenBusinessModules(body), settled: true });
        }
      })
      .catch(() => {
        // 读不到清单时外壳照常可用：保持保守的空集，不把未接通的模块放出来。
        if (!controller.signal.aborted) setOpenModules(SETTLED_WITHOUT_OPEN_MODULES);
      });
    return () => controller.abort();
  }, []);

  return openModules;
}

/**
 * 外壳把读到的清单分发给页面（票 04）。
 *
 * 默认值是**未落定**而不是「已落定的空集」：没有外壳的渲染环境同样没有清单证据，
 * 此时页面不该对用户断言任何模块未接通。
 */
const OpenBusinessModulesContext = createContext<OpenBusinessModules>(PENDING_BUSINESS_MODULES);

export const OpenBusinessModulesProvider = OpenBusinessModulesContext.Provider;

/**
 * 某个业务模块对当前部署的状态：
 * - `open`——清单里有它，功能可用，受控入口在菜单里；
 * - `closed`——清单已落定且不含它，功能不可用，受控入口已从菜单消失；
 * - `pending`——清单尚未落定，还没有证据，页面不作任何断言。
 */
export type BusinessModuleStatus = 'pending' | 'open' | 'closed';

export function useBusinessModuleStatus(id: BusinessModuleId): BusinessModuleStatus {
  const { modules, settled } = useContext(OpenBusinessModulesContext);
  if (modules.has(id)) return 'open';
  return settled ? 'closed' : 'pending';
}
