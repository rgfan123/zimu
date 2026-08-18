import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import type { ChannelMessageDetail, MessageSubmissionDetail } from '../src/api/types.ts';
import {
  currentChannelMessageDetail,
  intentDisplay,
  interpretationVersionRows,
  safeChannelMessageRows,
  safeSubmissionSummary,
} from '../src/pages/workbench/channelMessageView.ts';

test('channel message detail rendering is an explicit allowlist', () => {
  const message = {
    id: '1',
    corp_id: 'ww-corp',
    connection_id: 'business-relay',
    bot_id: 'bot-1',
    message_id: 'msg-1',
    chat_id: 'group-1',
    chat_type: 'group',
    sender_user_id: 'user-1',
    message_type: 'text',
    content: '客户需求',
    raw_payload_ref: 'channel-message-payload:1',
    received_at: '2026-08-12T08:00:00Z',
    response_url: 'https://temporary-response.example/secret',
    unknown_secret: 'must-not-be-rendered',
  } as ChannelMessageDetail & Record<string, unknown>;

  const rendered = JSON.stringify(safeChannelMessageRows(message));

  assert.match(rendered, /channel-message-payload:1/);
  assert.doesNotMatch(rendered, /temporary-response/);
  assert.doesNotMatch(rendered, /unknown_secret|must-not-be-rendered/);
});

test('channel message detail never reuses evidence from the previously selected row', () => {
  const previous = {
    id: '1',
    message_id: 'msg-1',
  } as ChannelMessageDetail;

  assert.equal(currentChannelMessageDetail(previous, '2'), null);
  assert.equal(currentChannelMessageDetail(previous, '1'), previous);
});

test('intent display is a whitelist that never renders raw model values', () => {
  assert.equal(intentDisplay('CUSTOMER_ORDER').intentLabel, '客户订单');
  assert.equal(intentDisplay('NEED_REVIEW').intentLabel, '待人工判断');
  assert.equal(intentDisplay(null).intentLabel, '待人工判断');
  assert.equal(intentDisplay('CUSTOMER_ORDER').intentColor, 'blue');
  assert.equal(intentDisplay('NON_BUSINESS').intentColor, 'default');
});

test('submission summary shows only whitelisted operational fields', () => {
  const submission = {
    id: '9',
    submission_no: 'SUB-9',
    status: 'INTERPRETED',
    source_message_id: '8',
    current_intent: 'NEED_REVIEW',
    latest_error: null,
    interpretations: [],
    latest_task: {
      id: '3',
      task_type: 'INTERPRET_MESSAGE',
      status: 'FAILED',
      attempts: 3,
      max_attempts: 3,
      last_error: 'model down',
      created_at: '2026-08-12T08:00:00Z',
    },
    created_at: '2026-08-12T08:00:00Z',
  } as MessageSubmissionDetail;

  const rendered = JSON.stringify(safeSubmissionSummary(submission));
  assert.match(rendered, /SUB-9/);
  assert.match(rendered, /已解释/);
  assert.match(rendered, /待人工判断/);
  assert.match(rendered, /3\/3/);
  assert.doesNotMatch(rendered, /raw_payload|response_url|token|secret/);
});

test('submission summary names FINALIZING as a recoverable terminalization state', () => {
  const submission = {
    id: '10',
    submission_no: 'SUB-10',
    status: 'FAILED',
    source_message_id: '9',
    current_intent: 'NEED_REVIEW',
    latest_error: 'MODEL_CALL_FAILED',
    interpretations: [],
    latest_task: {
      id: '4',
      task_type: 'INTERPRET_MESSAGE',
      status: 'FINALIZING',
      attempts: 3,
      max_attempts: 3,
      last_error: 'MODEL_CALL_FAILED',
      created_at: '2026-08-14T08:00:00Z',
    },
    created_at: '2026-08-14T08:00:00Z',
  } as MessageSubmissionDetail;

  const rendered = JSON.stringify(safeSubmissionSummary(submission));

  assert.match(rendered, /失败收口中（不会再次调用模型）/);
  assert.doesNotMatch(rendered, /"value":"FINALIZING"/);
});

test('interpretation history rows expose versions and errors without leaking internals', () => {
  const submission = {
    id: '9',
    submission_no: 'SUB-9',
    status: 'INTERPRETED',
    source_message_id: '8',
    current_intent: 'CUSTOMER_ORDER',
    interpretations: [
      {
        version: 2,
        intent: 'CUSTOMER_ORDER',
        provider: 'provider-a',
        model: 'model-b',
        prompt_version: 'pv-1',
        created_at: '2026-08-12T08:00:01Z',
      },
      {
        version: 1,
        intent: 'NEED_REVIEW',
        provider: 'provider-a',
        model: 'model-b',
        prompt_version: 'pv-1',
        error: 'MODEL_NOT_CONFIGURED',
        created_at: '2026-08-12T08:00:00Z',
      },
    ],
    latest_task: null,
    created_at: '2026-08-12T08:00:00Z',
  } as MessageSubmissionDetail;

  const rows = interpretationVersionRows(submission);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].version, 'v2');
  assert.equal(rows[0].intent, '客户订单');
  assert.equal(rows[1].intent, '待人工判断');
  assert.equal(rows[1].error, 'MODEL_NOT_CONFIGURED');
  assert.equal(rows[0].error, null);
});

test('submission and interpretation views fail closed on unknown historical error text', () => {
  const sentinel = 'raw provider exception contains secret=abc123';
  const submission = {
    id: '11',
    submission_no: 'SUB-11',
    status: 'FAILED',
    source_message_id: '10',
    current_intent: 'NEED_REVIEW',
    latest_error: sentinel,
    interpretations: [
      {
        version: 1,
        intent: 'NEED_REVIEW',
        provider: 'provider-a',
        model: 'model-b',
        prompt_version: 'pv-1',
        error: sentinel,
        created_at: '2026-08-14T08:00:00Z',
      },
    ],
    latest_task: {
      id: '5',
      task_type: 'INTERPRET_MESSAGE',
      status: 'FAILED',
      attempts: 3,
      max_attempts: 3,
      last_error: sentinel,
      created_at: '2026-08-14T08:00:00Z',
    },
    created_at: '2026-08-14T08:00:00Z',
  } as MessageSubmissionDetail;

  const rendered = JSON.stringify({
    summary: safeSubmissionSummary(submission),
    interpretations: interpretationVersionRows(submission),
  });

  assert.match(rendered, /MODEL_CALL_FAILED/);
  assert.doesNotMatch(rendered, /raw provider exception|secret=abc123/);
});

test('OpenAPI closes message task states and public failure codes', () => {
  const openapi = readFileSync(new URL('../../docs/openapi.yaml', import.meta.url), 'utf8');

  assert.match(
    openapi,
    /status: \{ type: string, enum: \[PENDING, RUNNING, FINALIZING, SUCCEEDED, FAILED\] \}/,
  );
  assert.match(
    openapi,
    /error: \{ type: string, enum: \[MODEL_NOT_CONFIGURED, MODEL_CALL_FAILED, MODEL_OUTPUT_INVALID\], nullable: true \}/,
  );
  assert.match(
    openapi,
    /last_error: \{ type: string, enum: \[MODEL_NOT_CONFIGURED, MODEL_CALL_FAILED, MODEL_OUTPUT_INVALID\], nullable: true \}/,
  );
});
