import assert from 'node:assert/strict';
import test from 'node:test';
import type { ChannelMessageDetail } from '../src/api/types.ts';
import {
  currentChannelMessageDetail,
  safeChannelMessageRows,
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
