import type {
  ChannelMessageDetail,
  MessageFailureCode,
  MessageSubmissionDetail,
  MessageTaskFailureCode,
} from '@/api/types';

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

export interface IntentDisplay {
  intentLabel: string;
  intentColor: string;
}

/** 意图枚举的白名单文案；未知枚举值一律显示“待人工判断”，不直接渲染模型输出。 */
export function intentDisplay(intent: string | null | undefined): IntentDisplay {
  switch (intent) {
    case 'CUSTOMER_ORDER':
      return { intentLabel: '客户订单', intentColor: 'blue' };
    case 'SUPPLIER_TRACKING':
      return { intentLabel: '履约方运单', intentColor: 'cyan' };
    case 'ORDER_CHANGE':
      return { intentLabel: '改单', intentColor: 'orange' };
    case 'ORDER_CANCEL':
      return { intentLabel: '取消', intentColor: 'volcano' };
    case 'NON_BUSINESS':
      return { intentLabel: '非业务', intentColor: 'default' };
    case 'NEED_REVIEW':
      return { intentLabel: '待人工判断', intentColor: 'red' };
    default:
      return { intentLabel: '待人工判断', intentColor: 'red' };
  }
}

export interface SubmissionSummaryRow {
  label: string;
  value: string;
}

/** Public message views never render provider/SDK exception text, including historical rows. */
export function stableMessageFailureCode(error: string | null | undefined): MessageFailureCode | null {
  if (error == null || error.trim() === '') {
    return null;
  }
  switch (error) {
    case 'MODEL_NOT_CONFIGURED':
    case 'MODEL_CALL_FAILED':
    case 'MODEL_OUTPUT_INVALID':
      return error;
    default:
      return 'MODEL_CALL_FAILED';
  }
}

/** Dedicated task failures have their own stable allowlist; unknown text still fails closed. */
export function stableMessageTaskFailureCode(
  error: string | null | undefined,
  taskType?: string,
): MessageTaskFailureCode | null {
  if (error == null || error.trim() === '') {
    return null;
  }
  if (taskType === 'WECOM_TRACKING_FILE') {
    switch (error) {
      case 'WECOM_TRACKING_FILE_CHAT_UNSUPPORTED':
      case 'WECOM_TRACKING_FILE_PAYLOAD_INVALID':
      case 'WECOM_TRACKING_FILE_DOWNLOAD_FAILED':
      case 'WECOM_TRACKING_FILE_TOO_LARGE':
      case 'WECOM_TRACKING_FILE_INVALID':
      case 'WECOM_TRACKING_FILE_PROCESSING_FAILED':
        return error;
      default:
        return 'WECOM_TRACKING_FILE_PROCESSING_FAILED';
    }
  }
  switch (error) {
    case 'MODEL_NOT_CONFIGURED':
    case 'MODEL_CALL_FAILED':
    case 'MODEL_OUTPUT_INVALID':
      return error;
    default:
      return 'MODEL_CALL_FAILED';
  }
}

/** 提交状态的只读摘要；内部载荷（raw payload、密钥）永不进入页面。 */
export function safeSubmissionSummary(submission: MessageSubmissionDetail): SubmissionSummaryRow[] {
  const task = submission.latest_task;
  const isTrackingFile = task?.task_type === 'WECOM_TRACKING_FILE';
  const display = intentDisplay(submission.current_intent);
  const latestError = stableMessageFailureCode(submission.latest_error);
  const taskError = stableMessageTaskFailureCode(task?.last_error, task?.task_type);
  return [
    { label: '提交编号', value: submission.submission_no },
    { label: '提交状态', value: submissionStatusLabel(submission.status, task?.task_type) },
    ...(!isTrackingFile || submission.current_intent
      ? [{ label: '当前意图', value: display.intentLabel }]
      : []),
    ...(latestError
      ? [{ label: '解释错误', value: latestError }]
      : []),
    ...(task
      ? [
          { label: '任务状态', value: taskStatusLabel(task.status) },
          { label: '尝试次数', value: `${task.attempts}/${task.max_attempts}` },
          ...(taskError ? [{ label: '任务错误', value: taskError }] : []),
        ]
      : []),
  ];
}

export interface InterpretationVersionRow {
  version: string;
  intent: string;
  provider: string;
  model: string;
  promptVersion: string;
  createdAt: string;
  error: MessageFailureCode | null;
}

/** 解释历史的白名单投影：版本、意图、模型信息与错误。 */
export function interpretationVersionRows(submission: MessageSubmissionDetail): InterpretationVersionRow[] {
  return submission.interpretations.map((item) => ({
    version: `v${item.version}`,
    intent: intentDisplay(item.intent).intentLabel,
    provider: item.provider,
    model: item.model,
    promptVersion: item.prompt_version,
    createdAt: item.created_at,
    error: stableMessageFailureCode(item.error),
  }));
}

function submissionStatusLabel(status: string, taskType?: string): string {
  switch (status) {
    case 'RECEIVED':
      return taskType === 'WECOM_TRACKING_FILE' ? '已接收（运单文件处理中）' : '已接收（解释中）';
    case 'INTERPRETED':
      return '已解释';
    case 'FAILED':
      return taskType === 'WECOM_TRACKING_FILE' ? '运单文件处理失败' : '解释失败';
    case 'DRAFTED':
      return '已生成草稿';
    case 'CONFIRMED':
      return '已确认';
    case 'REJECTED':
      return '已拒绝';
    default:
      return status;
  }
}

function taskStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '等待执行';
    case 'RUNNING':
      return '执行中';
    case 'FINALIZING':
      return '失败收口中（不会再次调用模型）';
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '最终失败';
    default:
      return status;
  }
}
