import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

/**
 * ADR 0011 守门：AntD 只做交互控件，页面结构与展示层一律手写 zs- CSS。
 *
 * MIGRATED 是棘轮清单——只增不减。既有对象页迁移展示层后把自己加进来，
 * 从此不得回退到用 AntD 布局组件搭骨架。
 */

/** 禁止在已迁移模块里从 antd 引入的「布局 / 展示」组件。 */
const BANNED = ['Card', 'Space', 'Typography', 'Row', 'Col', 'Flex', 'Descriptions'];

/** 允许的交互控件（仅作文档说明，不参与断言）：Select/Form/Table/Drawer/Modal/DatePicker/Upload/Button/Alert/Tag/Empty/Spin。 */

const MIGRATED = [
  'src/components/layout/AppLayout.tsx',
  'src/components/layout/WorkbenchRoleSwitcher.tsx',
  'src/components/layout/GlobalSearchOverlay.tsx',
  'src/pages/workbench/ShippingWorkbenchPage.tsx',
  'src/pages/workbench/ProcurementWorkbenchPage.tsx',
  'src/pages/workbench/ProcurementSuggestionCard.tsx',
  'src/pages/workbench/ReconWorkbenchPage.tsx',
];

function sourceOf(relative: string): string {
  return readFileSync(fileURLToPath(new URL(`../${relative}`, import.meta.url)), 'utf8');
}

/** 抽出该文件从 'antd' 具名引入的标识符（含多行 import）。 */
function antdImports(source: string): string[] {
  const names: string[] = [];
  const pattern = /import\s*\{([\s\S]*?)\}\s*from\s*'antd'/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(source)) !== null) {
    for (const raw of match[1].split(',')) {
      const name = raw.trim().split(/\s+as\s+/)[0].trim();
      if (name) names.push(name);
    }
  }
  return names;
}

for (const relative of MIGRATED) {
  test(`ADR 0011：${relative} 不得用 AntD 布局/展示组件搭骨架`, () => {
    const imported = antdImports(sourceOf(relative));
    const offenders = imported.filter((name) => BANNED.includes(name));
    assert.deepEqual(
      offenders,
      [],
      `${relative} 从 antd 引入了布局/展示组件 ${offenders.join('、')}；页面结构请用 zs- 手写 CSS（ADR 0011）`,
    );
  });
}

test('ADR 0011：外壳与工作台的展示层由手写 CSS 承载', () => {
  // 手写样式表存在且非空——守门的另一半：不能既不用 antd 布局、也没有自己的样式。
  for (const stylesheet of ['src/components/layout/shell.css', 'src/pages/workbench/workbench.css']) {
    assert.ok(sourceOf(stylesheet).includes('.zs-'), `${stylesheet} 必须提供 zs- 前缀的展示层样式`);
  }
});

test('ADR 0011：守门清单只增不减（棘轮）', () => {
  // 清单被缩短意味着有模块「退出」了规矩——这是回退，必须显式改本断言才允许。
  assert.ok(MIGRATED.length >= 7, '已迁移模块清单不得缩短；新迁移的页面请追加进 MIGRATED');
});
