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
 * - 数据库：git 跟踪的迁移里版本号最大的那次 task_type 取值集合重定义（见下）;
 * - 执行器：BusinessFollowUpCustomerAssignmentExecutor 的 `"X".equals(target.taskType())` 分派分支;
 * - 投影器：BusinessFollowUpAssignmentApplication 对本表 task_type 的**全部**写入点（写入侧，断言为子集）。
 *
 * 门禁自身的鲁棒性（票 02 评审 M1/M2/M3——门禁的全部价值就在于它对未来漂移的鲁棒性）：
 * - M1 投影器有两个写入点：projectAssignment 的 `String taskType = ...`（SQL 里以 ? 绑定）与
 *   persistProjectionFailure 的 INSERT 里硬编码的 'KEHUZX_CUSTOMER_LINK'。因此提取按语句结构走：
 *   逐条 `INSERT INTO app.business_followup_assignments` 解析列清单，定位 task_type 的列序，
 *   再取 VALUES 元组同一位置的写法——字面量直接计入，`?` 记为绑定位并要求存在对应的 Java 变量赋值。
 *   任何一处解析不出来就显式失败，不会「解析不到 = 没有写入」。
 * - M2 迁移侧不再用「文件里第一处 task_type IN (...)」这种启发式：先按顶层分号切语句、只看提到
 *   business_followup_assignments 的语句，再在 CHECK 的配对括号体里找**以 task_type 开头**的那种
 *   （值域定义），并强制它是 `task_type IN (...)` 形态。改成 `= ANY (ARRAY[...])`、WHERE 条件先出现、
 *   同文件里另一张表先命中——这三种情况要么被正确跳过，要么显式失败，绝不静默回退到旧集合。
 *   代价是保守：将来若真需要别的合法写法，请一并教会这里的提取器，而不是让门禁猜。
 * - M3 迁移清单用 `git ls-files`（与同目录 enumLabelReconciliation.test.ts 同口径）：保证可复现，
 *   且不把别人工作区里未跟踪的在制品迁移算进来——本仓多会话共用，工作区常挂着他人在制品。
 */

import assert from 'node:assert/strict';
import { execSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const MIGRATION_DIR = 'backend/src/main/resources/db/migration';
const FOLLOWUP_JAVA = 'backend/src/main/java/cn/zimu/fulfillment/followup';
const ASSIGNMENT_TABLE = 'business_followup_assignments';

function read(relativePath: string): string {
  return readFileSync(REPO_ROOT + relativePath, 'utf8');
}

function sorted(values: string[]): string[] {
  return [...values].sort();
}

function unquote(value: string): string {
  return value.trim().replace(/^['"]|['"]$/g, '');
}

// --- SQL 结构扫描的小工具（引号内的括号/逗号/分号不算结构） ---------------

/** 去掉 SQL 行注释：注释里的引号或分号会打乱后面的扫描。 */
function stripSqlComments(sql: string): string {
  let out = '';
  let quoted = false;
  for (let index = 0; index < sql.length; index += 1) {
    const char = sql[index];
    if (quoted) {
      out += char;
      if (char === "'") quoted = false;
      continue;
    }
    if (char === "'") {
      quoted = true;
      out += char;
      continue;
    }
    if (char === '-' && sql[index + 1] === '-') {
      while (index < sql.length && sql[index] !== '\n') index += 1;
      out += '\n';
      continue;
    }
    out += char;
  }
  return out;
}

/** 取 open 处 '(' 与其配对 ')' 之间的内容。 */
function parenBody(text: string, open: number, label: string): string {
  assert.equal(text[open], '(', `${label}：期望 ${open} 处是 '('`);
  let depth = 0;
  let quoted = false;
  for (let index = open; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === "'") quoted = false;
      continue;
    }
    if (char === "'") {
      quoted = true;
      continue;
    }
    if (char === '(') depth += 1;
    else if (char === ')') {
      depth -= 1;
      if (depth === 0) return text.slice(open + 1, index);
    }
  }
  return assert.fail(`${label}：括号不配对，无法解析`);
}

/** 顶层切分（括号内、引号内的分隔符不算）。 */
function splitTopLevel(text: string, separator: string): string[] {
  const parts: string[] = [];
  let current = '';
  let depth = 0;
  let quoted = false;
  for (const char of text) {
    if (quoted) {
      current += char;
      if (char === "'") quoted = false;
      continue;
    }
    if (char === "'") {
      quoted = true;
      current += char;
      continue;
    }
    if (char === '(') depth += 1;
    if (char === ')') depth -= 1;
    if (char === separator && depth === 0) {
      parts.push(current.trim());
      current = '';
      continue;
    }
    current += char;
  }
  parts.push(current.trim());
  return parts.filter((part) => part.length > 0);
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

/**
 * 迁移文件清单（按版本号升序），只取 git 跟踪的文件。
 * 与 enumLabelReconciliation.test.ts 同口径：可复现，且不把未跟踪的在制品迁移算进来。
 */
function migrationFiles(root: string, dir: string): string[] {
  const listing = execSync(`git -C '${root}' ls-files '${dir}/*.sql'`, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  })
    .trim()
    .split('\n')
    .filter(Boolean)
    .map((path) => path.slice(path.lastIndexOf('/') + 1));
  return listing
    .filter((file) => /^V\d+__.+\.sql$/.test(file))
    .sort((left, right) => Number(/^V(\d+)__/.exec(left)![1]) - Number(/^V(\d+)__/.exec(right)![1]));
}

/**
 * 单个迁移文件里对 business_followup_assignments.task_type 的取值集合定义（按出现顺序）。
 * 只认「提到本表的语句 → CHECK 的配对括号体 → 以 task_type 开头」这一形态；
 * 命中了却不是 `task_type IN (...)` 就显式失败，避免静默沿用上一版集合。
 */
function taskTypeCheckSets(sql: string, file: string): string[][] {
  const statements = splitTopLevel(stripSqlComments(sql), ';')
    .filter((statement) => statement.includes(ASSIGNMENT_TABLE));
  const sets: string[][] = [];
  for (const statement of statements) {
    for (const marker of statement.matchAll(/\bCHECK\s*\(/gi)) {
      const open = marker.index! + marker[0].length - 1;
      const body = parenBody(statement, open, `${file} 的 CHECK`).trim();
      // 值域定义以 task_type 打头；以 status 等打头的是结果一致性约束，不是取值集合。
      if (!/^task_type\b/i.test(body)) continue;
      const membership = /^task_type\s+IN\s*\((.*)\)$/is.exec(body);
      assert.ok(
        membership,
        `${file} 里 task_type 的 CHECK 不是预期的 IN (...) 形态，门禁无法确定生效取值集合：${body}`,
      );
      sets.push(membership[1].split(',').map(unquote).filter(Boolean));
    }
  }
  const dropsCheck = statements.some((statement) =>
    /DROP\s+CONSTRAINT\s+\w*task_type_check/i.test(statement));
  assert.ok(
    !dropsCheck || sets.length > 0,
    `${file} 丢弃了 task_type CHECK 却没有重新定义取值集合，门禁会静默沿用旧集合`,
  );
  return sets;
}

/** 数据库 CHECK 约束生效的取值集合（版本号最大的那次重定义；同文件内后写的语句覆盖先写的）。 */
function migrationTaskTypes(): string[] {
  const files = migrationFiles(REPO_ROOT, MIGRATION_DIR);
  assert.ok(files.length > 0, `git 跟踪的 ${MIGRATION_DIR} 里没有迁移文件`);
  let effective: string[] | null = null;
  let effectiveFile = '';
  for (const file of files) {
    const sets = taskTypeCheckSets(read(`${MIGRATION_DIR}/${file}`), file);
    if (sets.length === 0) continue;
    effective = sets[sets.length - 1];
    effectiveFile = file;
  }
  assert.ok(effective, `${MIGRATION_DIR} 里找不到 ${ASSIGNMENT_TABLE} 的 task_type CHECK`);
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

/**
 * 投影器对本表 task_type 的全部写入点（写入侧，允许是契约集合的子集）。
 * P1 Java 变量赋值（SQL 里以 ? 绑定）；P2 INSERT 里 task_type 列位置上的 SQL 字面量。
 */
function projectorTaskTypes(source: string): string[] {
  const literals = new Set<string>();
  let variableAssignments = 0;
  for (const assignment of source.matchAll(/\bString\s+(\w*[Tt]askType)\s*=\s*([^;]+);/g)) {
    const found = [...assignment[2].matchAll(/"([A-Z][A-Z0-9_]+)"/g)].map((match) => match[1]);
    assert.ok(found.length > 0, `投影器的 ${assignment[1]} 赋值不含字面量：${assignment[2].trim()}`);
    for (const value of found) literals.add(value);
    variableAssignments += 1;
  }

  let inserts = 0;
  let boundColumns = 0;
  const insertPattern = new RegExp(`INSERT\\s+INTO\\s+(?:app\\.)?${ASSIGNMENT_TABLE}\\s*\\(`, 'gi');
  for (const insert of source.matchAll(insertPattern)) {
    inserts += 1;
    const label = `投影器第 ${inserts} 处写 ${ASSIGNMENT_TABLE} 的 INSERT`;
    const columnsOpen = insert.index! + insert[0].length - 1;
    const columns = splitTopLevel(parenBody(source, columnsOpen, label), ',');
    const position = columns.indexOf('task_type');
    assert.notEqual(position, -1, `${label} 的列清单里没有 task_type：${columns.join(', ')}`);
    const valuesKeyword = source.indexOf('VALUES', columnsOpen);
    assert.notEqual(valuesKeyword, -1, `${label} 找不到 VALUES 子句`);
    const values = splitTopLevel(parenBody(source, source.indexOf('(', valuesKeyword), label), ',');
    assert.equal(values.length, columns.length, `${label} 的列数与 VALUES 元数不一致，无法定位 task_type`);
    const written = values[position];
    if (written === '?') {
      boundColumns += 1;
      continue;
    }
    const literal = /^'([A-Z][A-Z0-9_]+)'$/.exec(written);
    assert.ok(literal, `${label} 的 task_type 写入既不是绑定参数也不是可识别字面量：${written}`);
    literals.add(literal[1]);
  }

  assert.ok(inserts > 0, `投影器里找不到写 ${ASSIGNMENT_TABLE} 的 INSERT`);
  assert.ok(
    boundColumns === 0 || variableAssignments > 0,
    'INSERT 用 ? 绑定 task_type，却找不到对应的 Java 变量赋值，门禁看不到实际写入值',
  );
  assert.ok(literals.size > 0, '投影器的 task_type 写入点里提取不到任何字面量');
  return [...literals];
}

function projectorSource(): string {
  return read(`${FOLLOWUP_JAVA}/BusinessFollowUpAssignmentApplication.java`);
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

test('投影器全部写入点的 task_type 都在契约取值集合内', () => {
  const contract = new Set(contractTaskTypes());
  for (const taskType of projectorTaskTypes(projectorSource())) {
    assert.ok(contract.has(taskType), `投影器会写入契约未声明的 task_type：${taskType}`);
  }
});

// ---------------------------------------------------------------------------
// 门禁自身的回归用例：每条都对应一种「提取器有盲区时会假绿/假红」的漂移形态。
// 这几条在修 M1/M2/M3 之前全是红的——它们就是这次修复的可验证依据。
// ---------------------------------------------------------------------------

test('投影器提取覆盖 INSERT 里硬编码的 task_type 字面量', () => {
  const source = `
      String taskType = create ? "KEHUZX_CUSTOMER_CREATE" : "KEHUZX_CUSTOMER_LINK";
      jdbc.update("""
          INSERT INTO app.business_followup_assignments
              (followup_id, task_type, logical_target)
          VALUES (?, ?, ?)
          """);
      jdbc.update("""
          INSERT INTO app.business_followup_assignments
              (followup_id, task_type, logical_target)
          VALUES (?, 'KEHUZX_LEGACY_BACKFILL', ?)
          """);
  `;
  assert.deepEqual(
    sorted(projectorTaskTypes(source)),
    sorted(['KEHUZX_CUSTOMER_CREATE', 'KEHUZX_CUSTOMER_LINK', 'KEHUZX_LEGACY_BACKFILL']),
    '只取 Java 变量赋值会漏掉 SQL 里硬编码的写入点',
  );
});

test('投影器的 INSERT 用无法识别的写法写 task_type 时门禁显式失败', () => {
  const source = `
      jdbc.update("""
          INSERT INTO app.business_followup_assignments
              (followup_id, task_type, logical_target)
          VALUES (?, resolveTaskType(facts), ?)
          """);
  `;
  assert.throws(() => projectorTaskTypes(source), /既不是绑定参数也不是可识别字面量/);
});

test('迁移取值集合只认 CHECK 定义，不会把 WHERE 条件当成取值集合', () => {
  const sql = `
    UPDATE app.business_followup_assignments SET status='FAILED'
      WHERE task_type IN ('KEHUZX_CUSTOMER_LINK');
    ALTER TABLE app.business_followup_assignments
      DROP CONSTRAINT business_followup_assignments_task_type_check,
      ADD CONSTRAINT business_followup_assignments_task_type_check CHECK (
        task_type IN ('KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_CREATE', 'KEHUZX_CUSTOMER_MERGE')
      );
  `;
  const sets = taskTypeCheckSets(sql, 'V99__reorder.sql');
  assert.deepEqual(
    sorted(sets[sets.length - 1]),
    sorted(['KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_CREATE', 'KEHUZX_CUSTOMER_MERGE']),
    '先出现的 WHERE 条件被误当成了 CHECK 取值集合',
  );
});

test('迁移取值集合不会误取同文件里另一张表的 CHECK', () => {
  const sql = `
    CREATE TABLE app.other_assignments (
      task_type VARCHAR(64) NOT NULL CHECK (task_type IN ('OTHER_TASK'))
    );
    ALTER TABLE app.business_followup_assignments
      DROP CONSTRAINT business_followup_assignments_task_type_check,
      ADD CONSTRAINT business_followup_assignments_task_type_check CHECK (
        task_type IN ('KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_CREATE')
      );
  `;
  const sets = taskTypeCheckSets(sql, 'V99__two_tables.sql');
  assert.deepEqual(
    sorted(sets[sets.length - 1]),
    sorted(['KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_CREATE']),
    '同文件里先命中的另一张表的 CHECK 被当成了本表的取值集合',
  );
});

test('迁移改用 = ANY (ARRAY[...]) 重定义时门禁显式失败，不静默沿用旧集合', () => {
  const sql = `
    ALTER TABLE app.business_followup_assignments
      DROP CONSTRAINT business_followup_assignments_task_type_check,
      ADD CONSTRAINT business_followup_assignments_task_type_check CHECK (
        task_type = ANY (ARRAY['KEHUZX_CUSTOMER_LINK', 'KEHUZX_CUSTOMER_MERGE'])
      );
  `;
  assert.throws(() => taskTypeCheckSets(sql, 'V99__any_array.sql'), /不是预期的 IN \(\.\.\.\) 形态/);
});

test('迁移只丢弃 task_type CHECK 而不重建时门禁显式失败', () => {
  const sql = `
    ALTER TABLE app.business_followup_assignments
      DROP CONSTRAINT business_followup_assignments_task_type_check;
  `;
  assert.throws(() => taskTypeCheckSets(sql, 'V99__drop_only.sql'), /没有重新定义取值集合/);
});

test('迁移清单只取 git 跟踪的文件，忽略工作区里未跟踪的在制品迁移', () => {
  const root = mkdtempSync(join(tmpdir(), 'followup-task-type-gate-'));
  try {
    execSync(`git -C '${root}' init -q`, { stdio: 'pipe' });
    mkdirSync(join(root, 'db'), { recursive: true });
    writeFileSync(join(root, 'db', 'V1__tracked.sql'), '-- 已提交的迁移\n');
    writeFileSync(join(root, 'db', 'V99__untracked_wip.sql'), '-- 别人工作区里的在制品\n');
    execSync(`git -C '${root}' add db/V1__tracked.sql`, { stdio: 'pipe' });
    assert.deepEqual(
      migrationFiles(root, 'db'),
      ['V1__tracked.sql'],
      '未跟踪的在制品迁移不该进入门禁的迁移清单',
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
