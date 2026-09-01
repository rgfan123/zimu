import { apiRequest } from '../../api/client';
import { trustedWriteHeaders } from '../../api/writeHeaders';
import type {
  ConfirmTrackingDraftCommand,
  TrackingDraftDetail,
} from './trackingDraftReview';

export interface TrackingDraftBatchLine {
  draft_id: string;
  idempotency_key: string;
  expected_draft_revision: number;
  expected_case_version: number;
  task_id?: string | null;
  carrier_code?: string | null;
  actual_quantity?: number | null;
  remark?: string;
}

export interface TrackingDraftBatchLineResult {
  draft_id: string;
  success: boolean;
  replayed?: boolean;
  detail?: TrackingDraftDetail;
  http_status?: number;
  business_code?: string;
  message?: string;
}

export interface TrackingDraftBatchConfirmResult {
  results: TrackingDraftBatchLineResult[];
  success_count: number;
  failure_count: number;
}

export const trackingDraftReviewApi = {
  detail: (draftId: string) =>
    apiRequest<TrackingDraftDetail>(`/api/v1/tracking-drafts/${draftId}`),
  listBySubmission: (submissionId: string) =>
    apiRequest<{ items: TrackingDraftDetail[] }>('/api/v1/tracking-drafts', {
      params: { submission_id: submissionId, status: 'OPEN', page: 0, size: 200 },
    }),
  confirm: (draftId: string, command: ConfirmTrackingDraftCommand) =>
    apiRequest<TrackingDraftDetail>(`/api/v1/tracking-drafts/${draftId}/confirm`, {
      method: 'POST',
      body: command,
      headers: trustedWriteHeaders(),
    }),
  batchConfirm: (lines: TrackingDraftBatchLine[]) =>
    apiRequest<TrackingDraftBatchConfirmResult>('/api/v1/tracking-drafts/batch-confirm', {
      method: 'POST',
      body: { lines },
      headers: trustedWriteHeaders(),
    }),
};
