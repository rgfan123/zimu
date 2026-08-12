import type { ChannelMessageDetail } from '@/api/types';

export interface ChannelMessageFieldRow {
  label: string;
  value: string;
}

/** Prevent a previous request's evidence from being shown under a newly selected row. */
export function currentChannelMessageDetail(
  message: ChannelMessageDetail | null | undefined,
  selectedId: string | null | undefined,
): ChannelMessageDetail | null {
  return message && selectedId && message.id === selectedId ? message : null;
}

/** Explicit rendering allowlist: raw callback JSON and response_url never reach the page. */
export function safeChannelMessageRows(message: ChannelMessageDetail): ChannelMessageFieldRow[] {
  return [
    { label: '消息编号', value: message.message_id },
    { label: '发送人', value: message.sender_user_id },
    { label: '业务群', value: message.chat_id },
    { label: '机器人', value: message.bot_id },
    { label: '接入连接', value: message.connection_id },
    { label: '企业', value: message.corp_id },
    { label: '消息类型', value: message.message_type },
    { label: '接收时间', value: message.received_at },
    { label: '原始证据引用', value: message.raw_payload_ref },
  ].filter((row) => row.value !== '');
}
