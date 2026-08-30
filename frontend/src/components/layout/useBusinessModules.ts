/**
 * 外壳启动时读取「当前已开放的业务模块」清单（票 03）。
 *
 * 保守策略是硬要求：读到之前、读取失败、载荷畸形——一律按空集处理，未接通的模块不出现在菜单。
 * 反过来（读不到就全放行）会让菜单重新承诺系统给不出的能力，正是本票要消除的问题。
 */

import { useEffect, useState } from 'react';
import { businessModulesApi } from '@/api/endpoints';
import {
  NO_OPEN_BUSINESS_MODULES,
  parseOpenBusinessModules,
  type BusinessModuleId,
} from '@/businessModules';

/** 已开放的业务模块；整个外壳生命周期只读一次——模块开放与否是部署事实，不会在会话中途变化。 */
export function useOpenBusinessModules(): ReadonlySet<BusinessModuleId> {
  const [openModules, setOpenModules] = useState<ReadonlySet<BusinessModuleId>>(NO_OPEN_BUSINESS_MODULES);

  useEffect(() => {
    const controller = new AbortController();
    businessModulesApi
      .open({ signal: controller.signal })
      .then((body) => {
        if (!controller.signal.aborted) setOpenModules(parseOpenBusinessModules(body));
      })
      .catch(() => {
        // 读不到清单时外壳照常可用：保持保守的空集，不把未接通的模块放出来。
      });
    return () => controller.abort();
  }, []);

  return openModules;
}
