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
    },
  }),
);
