import assert from 'node:assert/strict';
import test from 'node:test';
import {
  BUSINESS_MODULE_IDS,
  NO_OPEN_BUSINESS_MODULES,
  parseOpenBusinessModules,
  type BusinessModuleId,
} from '../src/businessModules.ts';
import {
  flattenNavigationLeaves,
  moduleVisibleNavigationTree,
  navigationTrail,
  routableNavigationLeaves,
  visibleNavigationTree,
  type NavigationNode,
} from '../src/navigation.ts';
import { railGroupsForRole } from '../src/components/layout/shellRail.ts';

/**
 * 票 03：外壳运行期模块可见性。
 *
 * 运行期清单只做**过滤**——不新增节点、不改写路径，因此 URL 直达、侧边栏归属与隐藏页
 * 面包屑全部不受影响。清单读不到时按保守策略处理：未接通的模块一律不显示。
 */

const CUSTOMER_CENTER: BusinessModuleId = 'customer-center';
const ALL_OPEN: ReadonlySet<BusinessModuleId> = new Set(BUSINESS_MODULE_IDS);

/** 含受控节点的小树：生产导航树本票尚无受控节点（受控是票 04），机制仍必须可被端到端验证。 */
const sampleNavigation = [
  {
    path: '/workbench',
    label: '我的工作台',
    children: [
      { path: '/workbench/always', label: '常驻入口' },
      { path: '/workbench/gated', label: '受控入口', requiresModule: CUSTOMER_CENTER },
      { path: '/workbench/gated-hidden', label: '受控隐藏页', requiresModule: CUSTOMER_CENTER, hideInMenu: true },
    ],
  },
  {
    path: '/gated-section',
    label: '整组受控',
    requiresModule: CUSTOMER_CENTER,
    children: [{ path: '/gated-section/leaf', label: '组内叶子' }],
  },
  {
    path: '/only-gated',
    label: '只有受控子项的组',
    children: [{ path: '/only-gated/leaf', label: '受控叶子', requiresModule: CUSTOMER_CENTER }],
  },
] as const satisfies readonly NavigationNode[];

function visibleMenuPaths(routes: readonly NavigationNode[], open: ReadonlySet<BusinessModuleId>): string[] {
  return flattenNavigationLeaves(visibleNavigationTree(moduleVisibleNavigationTree(routes, open))).map(
    ({ path }) => path,
  );
}

test('未开放的模块：其导航节点整支不出现在菜单里', () => {
  assert.deepEqual(visibleMenuPaths(sampleNavigation, NO_OPEN_BUSINESS_MODULES), ['/workbench/always']);
});

test('模块开放后，受控节点按导航树原样出现（过滤不改写路径也不新增节点）', () => {
  assert.deepEqual(visibleMenuPaths(sampleNavigation, ALL_OPEN), [
    '/workbench/always',
    '/workbench/gated',
    '/gated-section/leaf',
    '/only-gated/leaf',
  ]);
  assert.deepEqual(moduleVisibleNavigationTree(sampleNavigation, ALL_OPEN), sampleNavigation as unknown);
});

test('受控节点的隐藏子项与整组受控：模块关闭时随本支一起从菜单消失', () => {
  const filtered = moduleVisibleNavigationTree(sampleNavigation, NO_OPEN_BUSINESS_MODULES);

  assert.deepEqual(
    filtered.map(({ path }) => path),
    ['/workbench'],
    '整组受控与「只有受控子项」的空组都不留在菜单树里',
  );
  assert.deepEqual(
    flattenNavigationLeaves(filtered).map(({ path }) => path),
    ['/workbench/always'],
    '受控的隐藏页同样不留在菜单树里（它本就不在菜单，但不得被过滤逻辑漏成可见）',
  );
});

test('过滤只作用于菜单：URL 直达与侧边栏归属仍从完整导航树解析', () => {
  assert.deepEqual(
    routableNavigationLeaves(sampleNavigation).map(({ path }) => path),
    ['/workbench/always', '/workbench/gated', '/workbench/gated-hidden', '/gated-section/leaf', '/only-gated/leaf'],
    '路由注册取完整导航树，模块未开放不得让既有 URL 变 404',
  );
  assert.deepEqual(
    navigationTrail(sampleNavigation, '/gated-section/leaf').map(({ label }) => label),
    ['整组受控', '组内叶子'],
    '直达受控页时侧边栏展开与顶栏归属照常工作',
  );
});

test('侧栏轨道按运行期清单过滤（外壳接线，不只是纯函数各自正确）', () => {
  const closed = railGroupsForRole(null, NO_OPEN_BUSINESS_MODULES, sampleNavigation);
  const open = railGroupsForRole(null, ALL_OPEN, sampleNavigation);

  assert.deepEqual(
    closed.map((group) => [group.key, group.items.map((item) => item.path)]),
    [['/workbench', ['/workbench/always']]],
  );
  assert.deepEqual(
    open.map((group) => [group.key, group.items.map((item) => item.path)]),
    [
      ['/workbench', ['/workbench/always', '/workbench/gated']],
      ['/gated-section', ['/gated-section/leaf']],
      ['/only-gated', ['/only-gated/leaf']],
    ],
  );
});

test('清单解析对畸形载荷保守取值：读不到就当作没有模块开放', () => {
  assert.deepEqual([...parseOpenBusinessModules({ modules: ['customer-center'] })], ['customer-center']);
  assert.deepEqual([...parseOpenBusinessModules({ modules: [] })], []);
  for (const payload of [null, undefined, 'customer-center', { modules: 'customer-center' }, {}, []]) {
    assert.deepEqual([...parseOpenBusinessModules(payload)], [], `畸形载荷必须解析成空集: ${JSON.stringify(payload)}`);
  }
});

test('未知模块标识被忽略，不因为后端多下发了什么就点亮本版本的菜单', () => {
  assert.deepEqual([...parseOpenBusinessModules({ modules: ['raw-material-inventory', 42, null] })], []);
  assert.deepEqual(
    [...parseOpenBusinessModules({ modules: ['raw-material-inventory', 'customer-center'] })],
    ['customer-center'],
  );
});
