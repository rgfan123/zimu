import { apiRequest } from '@/api/client';
import { trustedWriteHeaders } from '@/api/writeHeaders';
import type {
  ConfirmOrderDraftCommand,
  OrderDraftDetail,
  RejectOrderDraftCommand,
} from './orderDraftReview';

export const orderDraftReviewApi = {
  detail: (draftId: string) =>
    apiRequest<OrderDraftDetail>(`/api/v1/order-drafts/${draftId}`),
  confirm: (draftId: string, command: ConfirmOrderDraftCommand) =>
    apiRequest<OrderDraftDetail>(`/api/v1/order-drafts/${draftId}/confirm`, {
      method: 'POST',
      body: command,
      headers: trustedWriteHeaders(),
    }),
  reject: (draftId: string, command: RejectOrderDraftCommand) =>
    apiRequest<OrderDraftDetail>(`/api/v1/order-drafts/${draftId}/reject`, {
      method: 'POST',
      body: command,
      headers: trustedWriteHeaders(),
    }),
};
