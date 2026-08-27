import { businessFollowUpsApi } from '../src/api/endpoints';
import type {
  BusinessFollowUpBusinessKind,
  BusinessFollowUpCreateInput,
  BusinessFollowUpExecutionPlan,
} from '../src/api/types';

const sampleRequest: BusinessFollowUpCreateInput = {
  message_submission_id: '91',
  employee_draft: '样品请求证据',
  business_kind: 'SAMPLE',
  execution_plan: {
    sample_name: '试吃样品',
    product_name: '牛肩切片',
    quantity_per_unit: 0.5,
    quantity_unit: 'kg',
    unit_count: 4,
    requested_date: '2026-09-01',
  },
};

void businessFollowUpsApi.create(sampleRequest);
void businessFollowUpsApi.detail('91').then((detail) => {
  const kind: BusinessFollowUpBusinessKind = detail.business_kind;
  const plan: BusinessFollowUpExecutionPlan | null = detail.execution_plan;
  return { kind, plan };
});
