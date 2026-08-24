import type {
  MessageFailureCode,
  MessageInterpretation,
  MessageTaskStatus,
  MessageTaskFailureCode,
  MessageTaskStatusCode,
} from '../src/api/types';

type Equal<Left, Right> =
  (<Value>() => Value extends Left ? 1 : 2) extends
  (<Value>() => Value extends Right ? 1 : 2)
    ? true
    : false;
type Expect<Value extends true> = Value;

export type MessageTaskStatusUsesClosedLifecycle = Expect<
  Equal<MessageTaskStatus['status'], MessageTaskStatusCode>
>;
export type TaskErrorsUseStableFailureCodes = Expect<
  Equal<MessageTaskStatus['last_error'], MessageTaskFailureCode | null | undefined>
>;
export type InterpretationErrorsUseStableFailureCodes = Expect<
  Equal<MessageInterpretation['error'], MessageFailureCode | null | undefined>
>;

const recoverableFinalization: MessageTaskStatus = {
  id: 'task-1',
  task_type: 'INTERPRET_MESSAGE',
  status: 'FINALIZING',
  attempts: 3,
  max_attempts: 3,
  last_error: 'MODEL_CALL_FAILED',
  created_at: '2026-08-14T08:00:00Z',
};

void recoverableFinalization;

const trackingFileFailure: MessageTaskStatus = {
  id: 'task-2',
  task_type: 'WECOM_TRACKING_FILE',
  status: 'FAILED',
  attempts: 3,
  max_attempts: 3,
  last_error: 'WECOM_TRACKING_FILE_INVALID',
  created_at: '2026-08-23T08:00:00Z',
};

void trackingFileFailure;
