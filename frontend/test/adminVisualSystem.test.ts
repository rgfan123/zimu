import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { ApiError } from '../src/api/client.ts';
import {
  createAdminVisualSystem,
  permissionFailurePresentation,
} from '../src/pages/shared/adminVisualCore.ts';
import { saasVisualTokens } from '../src/theme/saasTheme.ts';

const visualSystem = createAdminVisualSystem(saasVisualTokens);
const adminStatusPresentation = visualSystem.status;
const adminCategoryColor = visualSystem.category;

function relativeLuminance(hex: string): number {
  const channels = hex
    .replace('#', '')
    .match(/.{2}/g)!
    .map((channel) => Number.parseInt(channel, 16) / 255)
    .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4));
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

function contrastRatio(foreground: string, background: string): number {
  const foregroundLuminance = relativeLuminance(foreground);
  const backgroundLuminance = relativeLuminance(background);
  return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
    / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
}

test('admin operational states pair a stable semantic role with visible text and icon', () => {
  assert.deepEqual(adminStatusPresentation('PENDING'), {
    label: '待处理',
    tone: 'warning',
    color: saasVisualTokens.semantic.warning,
    icon: 'clock',
  });
  assert.deepEqual(adminStatusPresentation('SUCCESS'), {
    label: '已补齐',
    tone: 'success',
    color: saasVisualTokens.semantic.success,
    icon: 'check',
  });
  assert.deepEqual(adminStatusPresentation('FAILED'), {
    label: '失败',
    tone: 'error',
    color: saasVisualTokens.semantic.error,
    icon: 'warning',
  });
  assert.deepEqual(adminStatusPresentation('CANCELLED'), {
    label: '已取消',
    tone: 'neutral',
    color: saasVisualTokens.neutral[500],
    icon: 'stop',
  });
  assert.deepEqual(adminStatusPresentation('MATCHED'), {
    label: '可确认',
    tone: 'success',
    color: saasVisualTokens.semantic.success,
    icon: 'check',
  });
  assert.deepEqual(adminStatusPresentation('CONFLICT'), {
    label: '存在冲突',
    tone: 'error',
    color: saasVisualTokens.semantic.error,
    icon: 'warning',
  });
});

test('ordinary admin categories use the shared data family instead of semantic colors', () => {
  const semantic = new Set(Object.values(saasVisualTokens.semantic));
  const categories = ['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'WECOM', 'JD_WAREHOUSE', 'THIRD_PARTY'];
  const colors = categories.map(adminCategoryColor);

  assert.ok(colors.every((color) => Object.values(saasVisualTokens.data).includes(color)));
  assert.ok(colors.every((color) => !semantic.has(color)));
  assert.equal(adminCategoryColor('CAISHIXIAN'), adminCategoryColor('CAISHIXIAN'));
});

test('small admin tags keep readable text while soft data colors remain decorative accents', () => {
  for (const status of ['PENDING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED', 'ACTIVE', 'INACTIVE']) {
    assert.ok(contrastRatio(visualSystem.tagText(status), saasVisualTokens.neutral[50]) >= 4.5, status);
  }
  for (const category of ['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'WECOM', 'JD_WAREHOUSE', 'THIRD_PARTY']) {
    assert.ok(contrastRatio(visualSystem.categoryText(category), saasVisualTokens.neutral[50]) >= 4.5, category);
    assert.equal(visualSystem.categoryAccent(category), adminCategoryColor(category));
  }
});

test('admin loading, error and ready states are mutually exclusive', () => {
  assert.equal(visualSystem.pageState(true, new Error('late failure'), true), 'loading');
  assert.equal(visualSystem.pageState(false, new Error('failure'), true), 'error');
  assert.equal(visualSystem.pageState(false, null, false), 'ready-empty');
  assert.equal(visualSystem.pageState(false, null, true), 'ready-data');
});

test('admin data pages gate stale content and System Config retries both resources', () => {
  const files = [
    '../src/pages/shared/MasterDataCrud.tsx',
    '../src/pages/procurement/ProcurementTicketsPage.tsx',
    '../src/pages/system/ConnectorsPage.tsx',
    '../src/pages/system/SystemConfigPage.tsx',
  ];

  for (const relativePath of files) {
    const source = readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8');
    assert.match(source, /adminPageState\(/, `${relativePath} must explicitly gate loading, error and ready content`);
    assert.match(source, /AdminLoading/, `${relativePath} must expose a dedicated loading state`);
  }

  const systemConfig = readFileSync(
    fileURLToPath(new URL('../src/pages/system/SystemConfigPage.tsx', import.meta.url)),
    'utf8',
  );
  assert.match(systemConfig, /const reloadAll[\s\S]*connectors\.reload\(\)[\s\S]*providers\.reload\(\)/);
  assert.match(systemConfig, /onRetry=\{reloadAll\}/);
});

test('admin write flows preserve form wiring, themed feedback and disabled submit states', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../src/pages/shared/MasterDataCrud.tsx', import.meta.url)),
    'utf8',
  );

  assert.doesNotMatch(source, /<FieldControl\b/, 'a custom component must not swallow Form.Item value/onChange props');
  assert.match(source, /\{fieldControl\(f\)\}/);
  assert.match(source, /forceRender/);
  assert.match(source, /onCancel=\{\(\) => \{[\s\S]*form\.resetFields\(\)/);
  assert.match(source, /okButtonProps=\{\{ disabled: submitting \}\}/);

  for (const relativePath of [
    '../src/pages/shared/MasterDataCrud.tsx',
    '../src/pages/procurement/ProcurementTicketsPage.tsx',
    '../src/pages/system/ConnectorsPage.tsx',
    '../src/pages/product/SkuMappingsPage.tsx',
  ]) {
    const pageSource = readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8');
    assert.match(pageSource, /AntApp\.useApp\(\)/, `${relativePath} must keep feedback inside the active theme context`);
    assert.doesNotMatch(
      pageSource,
      /\bmessage\.(?:success|error|warning|info)\(/,
      `${relativePath} must not use static feedback outside the active theme context`,
    );
  }

  const procurement = readFileSync(
    fileURLToPath(new URL('../src/pages/procurement/ProcurementTicketsPage.tsx', import.meta.url)),
    'utf8',
  );
  const connectors = readFileSync(
    fileURLToPath(new URL('../src/pages/system/ConnectorsPage.tsx', import.meta.url)),
    'utf8',
  );
  const skuMappings = readFileSync(
    fileURLToPath(new URL('../src/pages/product/SkuMappingsPage.tsx', import.meta.url)),
    'utf8',
  );
  assert.match(procurement, /disabled: submitting \|\| !actionNote\.trim\(\)/);
  assert.match(procurement, /cancelButtonProps=\{\{ disabled: submitting \}\}/);
  assert.match(connectors, /okButtonProps=\{\{ disabled: submitting \}\}/);
  assert.match(connectors, /cancelButtonProps=\{\{ disabled: submitting \}\}/);
  assert.match(skuMappings, /disabled: confirming \|\| !skuId/);
  assert.match(skuMappings, /cancelButtonProps=\{\{ disabled: confirming \}\}/);
});

test('JD tool feedback stays inside the active application theme context', () => {
  for (const relativePath of [
    '../src/pages/fulfillment/JdWarehousePage.tsx',
    '../src/pages/fulfillment/JdOrderQueryPage.tsx',
  ]) {
    const source = readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8');
    assert.match(source, /AntApp\.useApp\(\)/, `${relativePath} must get feedback from the active App context`);
    assert.doesNotMatch(
      source,
      /\bmessage\.(?:success|error|warning|info)\(/,
      `${relativePath} must not use static feedback outside the active theme context`,
    );
  }
});

test('permission failures are distinguishable from system failures without exposing raw details', () => {
  const forbiddenError = new ApiError(403, { business_code: 'FORBIDDEN', message: 'raw policy detail', http_status: 403 });
  const forbidden = permissionFailurePresentation(forbiddenError.status);

  assert.deepEqual(forbidden, {
    kind: 'permission',
    alertType: 'warning',
    title: '暂无查看权限',
    description: '当前账号未获授权，请联系管理员确认权限。',
  });
  assert.equal(permissionFailurePresentation(500), null);
  assert.doesNotMatch(JSON.stringify(forbidden), /raw policy detail/);
});

test('admin production pages add no local hexadecimal or named decorative palette', () => {
  const files = [
    '../src/pages/shared/MasterDataCrud.tsx',
    '../src/pages/product/SkusPage.tsx',
    '../src/pages/product/SkuMappingsPage.tsx',
    '../src/pages/procurement/ProcurementTicketsPage.tsx',
    '../src/pages/system/ConnectorsPage.tsx',
    '../src/pages/system/SystemConfigPage.tsx',
  ];

  for (const relativePath of files) {
    const source = readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8');
    assert.doesNotMatch(source, /#[0-9a-f]{3,8}\b/i, `${relativePath} contains a local hex color`);
    assert.doesNotMatch(
      source,
      /(?:color|background)\s*=\s*["'](?:blue|cyan|purple|gold|green|orange|red)["']/i,
      `${relativePath} contains a named decorative palette color`,
    );
  }
});
