import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function source(relative: string): string {
  return readFileSync(path.join(root, relative), 'utf8');
}

test('Business Follow-up is a real workbench route with a bound page', () => {
  assert.match(source('src/navigation.ts'), /\/workbench\/business-followups/);
  assert.match(source('src/routes.tsx'), /BusinessFollowUpsPage/);
  assert.match(source('src/routes.tsx'), /'\/workbench\/business-followups': <BusinessFollowUpsPage/);
});

test('Business Follow-up API exposes intake, list and explicit organize commands', () => {
  const endpoints = source('src/api/endpoints.ts');
  assert.match(endpoints, /businessFollowUpsApi/);
  assert.match(endpoints, /\/api\/v1\/business-followups/);
  assert.match(endpoints, /\/organize/);
  assert.match(endpoints, /writeHeaders/);
});

test('workbench shows stage and Agent processing as separate columns', () => {
  const page = source('src/pages/workbench/BusinessFollowUpsPage.tsx');
  assert.match(page, /业务阶段/);
  assert.match(page, /Agent 处理/);
  assert.match(page, /证据版本/);
  assert.match(page, /指定 \+1/);
  assert.match(page, /businessFollowUpsApi\.detail/);
  assert.match(page, /Button type="link"/);
});

test('channel evidence owns the follow-up creation entry and carries a string submission id', () => {
  const messages = source('src/pages/workbench/ChannelMessagesPage.tsx');
  const page = source('src/pages/workbench/BusinessFollowUpsPage.tsx');
  const types = source('src/api/types.ts');
  assert.match(messages, /创建客户跟进/);
  assert.match(messages, /submission_id=/);
  assert.match(page, /readOnly/);
  assert.match(types, /message_submission_id: string/);
});
