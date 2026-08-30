/**
 * 客户跟进 Assignment 的 task_type 取值集合跨源对账（票 02）。
 *
 * 背景：这一个取值集合同时被四处独立声明——对外契约 docs/openapi.yaml、前端类型
 * frontend/src/api/types.ts、数据库 CHECK 约束（Flyway 迁移）、后端执行器的分派分支。
 * 四处任何一处先行（例如迁移与执行器已支持「创建新客户」，契约与前端仍只声明「关联既有客户」），
 * 消费方就必须绕过类型系统才能表达真实存在的那条路径。本文件把四处取值集合抽出来直接比对，
 * 让漂移在构建期失败，而不是等到运行期被 CHECK 约束拒绝。
 *
 * 提取规则（都基于仓库源码，新增取值后自动覆盖）：
 * - 契约：docs/openapi.yaml 里 BusinessFollowUpAssignmentDto.task_type 的 enum;
 * - 前端：frontend/src/api/types.ts 里 BusinessFollowUpAssignment.task_type 的字面量联合;
 * - 数据库：提到 business_followup_assignments 的迁移里版本号最大的那条 `task_type IN (...)`
 *   （生效的是最后一次重定义，即 V70 覆盖 V68 建表时的内联 CHECK）;
 * - 执行器：BusinessFollowUpCustomerAssignmentExecutor 的 `"X".equals(target.taskType())` 分派分支;
 * - 投影器：BusinessFollowUpAssignmentApplication 决定 task_type 的那次赋值（写入侧，断言为子集）。
 */

import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const MIGRATION_DIR = 'backend/src/main/resources/db/migration';
const FOLLOWUP_JAVA = 'backend/src/main/java/cn/zimu/fulfillment/followup';

function read(relativePath: string): string {
  return readFileSync(REPO_ROOT + relativePath, 'utf8');
}

function sorted(values: string[]): string[] {
  return [...values].sort();
}

function unquote(value: string): string {
  return value.trim().replace(/^['"]|['"]$/g, '');
}

/** 取 openapi.yaml 里某个 schema 的正文行（schema 名在 4 空格缩进，属性在更深缩进）。 */
function openApiSchemaBody(spec: string, schemaName: string): string[] {
  const lines = spec.split('\n');
  const start = lines.indexOf(`    ${schemaName}:`);
  assert.notEqual(start, -1, `docs/openapi.yaml 缺少 schema ${schemaName}`);
  const body: string[] = [];
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() !== '' && !line.startsWith('     ')) break;
    body.push(line);
  }
  return body;
}

/** 契约声明的取值集合。 */
function contractTaskTypes(): string[] {
  const body = openApiSchemaBody(read('docs/openapi.yaml'), 'BusinessFollowUpAssignmentDto');
  const declaration = body.find((line) => line.trim().startsWith('task_type:'));
  assert.ok(declaration, '契约的 BusinessFollowUpAssignmentDto 未声明 task_type');
  const enumeration = /enum:\s*\[([^\]]*)\]/.exec(declaration);
  assert.ok(enumeration, `契约的 task_type 未声明 enum：${declaration.trim()}`);
  return enumeration[1].split(',').map(unquote).filter(Boolean);
}

/** 前端类型声明的取值集合。 */
function frontendTaskTypes(): string[] {
  const source = read('frontend/src/api/types.ts');
  const start = source.indexOf('export interface BusinessFollowUpAssignment {');
  assert.notEqual(start, -1, 'frontend/src/api/types.ts 缺少 BusinessFollowUpAssignment');
  const block = source.slice(start, source.indexOf('\n}', start));
  const declaration = /^\s*task_type:\s*([^;]+);/m.exec(block);
  assert.ok(declaration, 'BusinessFollowUpAssignment 未声明 task_type');
  return declaration[1].split('|').map(unquote).filter(Boolean);
}

/** 数据库 CHECK 约束生效的取值集合（版本号最大的那次重定义）。 */
function migrationTaskTypes(): string[] {
  const files = readdirSync(REPO_ROOT + MIGRATION_DIR)
    .filter((file) => /^V\d+__.+\.sql$/.test(file))
    .sort((left, right) => Number(/^V(\d+)__/.exec(left)![1]) - Number(/^V(\d+)__/.exec(right)![1]));
  let effective: string[] | null = null;
  let effectiveFile = '';
  for (const file of files) {
    const sql = read(`${MIGRATION_DIR}/${file}`);
    if (!sql.includes('business_followup_assignments')) continue;
    const check = /task_type\s+IN\s*\(([^)]*)\)/i.exec(sql);
    if (!check) continue;
    effective = check[1].split(',').map(unquote).filter(Boolean);
    effectiveFile = file;
  }
  assert.ok(effective, `${MIGRATION_DIR} 里找不到 business_followup_assignments 的 task_type CHECK`);
  assert.ok(effectiveFile.length > 0);
  return effective;
}

/** 后端执行器实际分派的取值集合。 */
function executorTaskTypes(): string[] {
  const source = read(`${FOLLOWUP_JAVA}/BusinessFollowUpCustomerAssignmentExecutor.java`);
  const branches = [...source.matchAll(/"([A-Z][A-Z0-9_]+)"\.equals\(target\.taskType\(\)\)/g)]
    .map((match) => match[1]);
  assert.ok(branches.length > 0, '执行器里找不到 task_type 分派分支');
  return branches;
}

/** 投影器写入的取值集合（写入侧，允许是契约集合的子集）。 */
function projectorTaskTypes(): string[] {
  const source = read(`${FOLLOWUP_JAVA}/BusinessFollowUpAssignmentApplication.java`);
  const assignment = /String taskType\s*=\s*([^;]+);/.exec(source);
  assert.ok(assignment, '投影器里找不到 task_type 的赋值');
  const literals = [...assignment[1].matchAll(/"([A-Z][A-Z0-9_]+)"/g)].map((match) => match[1]);
  assert.ok(literals.length > 0, `投影器的 task_type 赋值不含字面量：${assignment[1].trim()}`);
  return literals;
}

test('契约的 task_type 同时覆盖关联既有客户与创建新客户两条路径', () => {
  assert.deepEqual(
    sorted(contractTaskTypes()),
    sorted(['KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_CREATE']),
    '契约必须同时接受关联既有客户（LINK）与创建新客户（CREATE）',
  );
});

test('契约、数据库 CHECK 与后端执行器三处 task_type 取值集合一致', () => {
  const contract = sorted(contractTaskTypes());
  assert.deepEqual(sorted(migrationTaskTypes()), contract, '数据库 CHECK 与契约的取值集合漂移');
  assert.deepEqual(sorted(executorTaskTypes()), contract, '后端执行器与契约的取值集合漂移');
});

test('前端类型与契约的 task_type 取值集合一致', () => {
  assert.deepEqual(sorted(frontendTaskTypes()), sorted(contractTaskTypes()), '前端类型与契约的取值集合漂移');
});

test('投影器写入的 task_type 都在契约取值集合内', () => {
  const contract = new Set(contractTaskTypes());
  for (const taskType of projectorTaskTypes()) {
    assert.ok(contract.has(taskType), `投影器会写入契约未声明的 task_type：${taskType}`);
  }
});
