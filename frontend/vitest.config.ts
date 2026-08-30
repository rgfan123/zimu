/**
 * 组件级测试配置（vitest + jsdom + Testing Library）。
 *
 * 与既有 `node --test` 逻辑单测并存，二者按文件后缀分工，互不收集：
 * - `test/*.test.ts`  → node:test 纯逻辑单测（npm run test:unit）
 * - `test/*.test.tsx` → vitest 组件测试（npm run test:component）
 *
 * 复用 vite.config.ts 的 react 插件与 `@` 别名，避免测试与构建两套模块解析规则。
 */

import { mergeConfig, defineConfig } from 'vite';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // 只收集 .tsx 组件测试：既有 .test.ts 用 node:test 运行器，不能被 vitest 收集。
      include: ['test/**/*.test.tsx'],
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./test/setup/componentTestSetup.ts'],
      restoreMocks: true,
      // vitest 默认 5000ms 对这套测试不够用：单个用例渲染整页 antd（成本表抽屉 47 列、
      // 结构化执行计划表单）再走多轮 userEvent + findBy，空载就要 1.5~4.5s，只剩不到 10%
      // 余量。开发机同时在跑 `mvn test` 时，最慢的几个用例会稳定越过 5s 而报
      // `Test timed out in 5000ms` —— 断言本身从未失败（把预算放宽到 60s 时 17/17 全绿）。
      // 这类红是预算不足，不是被测代码有问题；30s 给最慢用例 2 倍以上余量。
      // 注意：这只消除假红，用例本身仍慢（collect ≈ 15s），真正的提速见下方 TODO。
      // TODO(前端组件测试提速)：用例导入整页组件，模块图收集就占了约 15s；
      //   考虑按更小的组件接缝测试，或对 echarts/重依赖做 alias 打桩。
      testTimeout: 30_000,
    },
  }),
);
