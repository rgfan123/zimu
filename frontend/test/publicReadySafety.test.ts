import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, errorMessage } from '../src/api/client.ts';
import {
  auditOperationLabel,
  auditServiceLabel,
  displayOperator,
  jdQueryPresentation,
  reviewCaseSummary,
  safeReviewDetailRows,
  safeAuditPayloadRows,
  safeEventPayloadRows,
} from '../src/presentation/publicReady.ts';

test('API errors never expose backend field paths or raw 4xx messages', () => {
  const validation = new ApiError(400, {
    business_code: 'VALIDATION_ERROR',
    message: '请求参数校验失败',
    http_status: 400,
    field_errors: [{ field: 'receiver.phone', code: 'NotBlank', message: 'must not be blank' }],
  });
  const fileRead = new ApiError(422, {
    business_code: 'FILE_READ_FAILED',
    message: '无法读取上传文件: Zip bomb detected in entry xl/sharedStrings.xml',
    http_status: 422,
  });

  assert.equal(validation.message, '服务暂时不可用，请稍后重试');
  assert.equal(errorMessage(validation), '提交内容有误，请检查必填项和格式后重试');
  assert.equal(errorMessage(fileRead), '文件内容或格式不符合要求，请使用正确模板，核对内容后重新上传');
  assert.doesNotMatch(errorMessage(validation), /receiver\.phone|NotBlank|must not be blank/);
  assert.doesNotMatch(errorMessage(fileRead), /Zip bomb|sharedStrings|无法读取上传文件/);
});

test('business follow-up intent errors have actionable Chinese messages', () => {
  const invalidPlan = new ApiError(400, {
    business_code: 'FOLLOWUP_EXECUTION_PLAN_INVALID',
    message: 'quantity_per_unit must be NUMERIC(14,3)',
    http_status: 400,
  });
  const invalidKind = new ApiError(400, {
    business_code: 'FOLLOWUP_BUSINESS_KIND_INVALID',
    message: 'business_kind only accepts server enums',
    http_status: 400,
  });

  assert.equal(
    errorMessage(invalidPlan),
    '执行计划不符合要求，请检查必填项、格式、数量和日期后重试',
  );
  assert.equal(
    errorMessage(invalidKind),
    '业务类型不受支持，请重新选择普通跟进、样品请求或正式订单',
  );
  assert.doesNotMatch(`${errorMessage(invalidPlan)}${errorMessage(invalidKind)}`, /NUMERIC|server enums/);
});

test('tracking-draft business rejections stay actionable without exposing backend text', () => {
  const ambiguous = new ApiError(422, {
    business_code: 'TASK_SHIPMENT_AMBIGUOUS',
    message: 'raw internal SQL detail',
    http_status: 422,
  });
  const invalidCarrier = new ApiError(422, {
    business_code: 'CARRIER_INVALID',
    message: 'raw carrier config',
    http_status: 422,
  });

  assert.equal(errorMessage(ambiguous), '该任务有多个待回传发货批次，请先消除歧义');
  assert.equal(errorMessage(invalidCarrier), '所选物流公司已失效，请刷新后重新核对');
  assert.doesNotMatch(`${errorMessage(ambiguous)}${errorMessage(invalidCarrier)}`, /SQL|config/);
});

test('concurrent tracking outcomes use refresh guidance instead of a generic retry loop', () => {
  const duplicate = new ApiError(409, {
    business_code: 'TRACKING_DUPLICATE',
    message: 'raw tracking constraint',
    http_status: 409,
  });
  const completedTask = new ApiError(409, {
    business_code: 'TASK_NOT_PENDING',
    message: 'raw task state',
    http_status: 409,
  });
  const closedDraft = new ApiError(409, {
    business_code: 'DRAFT_NOT_OPEN',
    message: 'raw draft state',
    http_status: 409,
  });

  assert.equal(errorMessage(duplicate), '该运单已被接收，请刷新后核对现有运单事实');
  assert.equal(errorMessage(completedTask), '该发货任务已被处理，请刷新后核对最新状态');
  assert.equal(errorMessage(closedDraft), '该草稿已被处理，请刷新后查看只读终态');
});

test('an incomplete source batch confirmation directs the operator to the review workspace', () => {
  const incomplete = new ApiError(409, {
    business_code: 'IMPORT_BATCH_EXPORT_INCOMPLETE',
    message: 'raw provider candidate details',
    http_status: 409,
  });

  assert.equal(
    errorMessage(incomplete),
    '批次中仍有订单未完成复核或尚未形成履约行，请先前往复核工作台处理',
  );
  assert.doesNotMatch(errorMessage(incomplete), /provider|candidate|raw/);
});

test('timeline and review summaries only expose approved business fields', () => {
  const timeline = safeEventPayloadRows({
    product_name: '子牧羊小腿',
    quantity: 2,
    token: 'secret-token',
    raw_response: { code: 500, stack: 'internal' },
    unknown_internal_field: 'do-not-render',
  });
  const review = reviewCaseSummary({
    reason_code: 'SKU_UNMAPPED',
    detail: {
      source_customer_ref: 'CUST-01',
      missing_source_sku_refs: ['JD-001', 'JD-002'],
      raw_payload: { sql: 'select *' },
    },
  });

  assert.deepEqual(timeline, [
    { label: '商品', value: '子牧羊小腿' },
    { label: '数量', value: '2' },
  ]);
  assert.equal(review, '来源客户编号：CUST-01；待映射来源商品：JD-001、JD-002');
  assert.doesNotMatch(review, /raw_payload|select \*/);
  assert.deepEqual(
    safeReviewDetailRows({
      shipment_id: '31',
      check_run_no: 'JD-SKU-CHK-01',
      source_customer_ref: 'CUST-01',
      quantity_multiplier: '2.000',
      remark: '人工核对完成',
      internal_stack: { message: 'do-not-render' },
    }),
    [
      { label: '发货批次编号', value: '31' },
      { label: '映射核对批次', value: 'JD-SKU-CHK-01' },
      { label: '来源客户编号', value: 'CUST-01' },
      { label: '数量换算', value: '2.000' },
      { label: '处理依据', value: '人工核对完成' },
    ],
  );
  assert.equal(displayOperator('seed-runner'), '系统');
});

test('analytics review tooltips use the approved review summary instead of raw detail JSON', () => {
  const tooltip = reviewCaseSummary({
    reason_code: 'SKU_MAPPING_REQUIRED',
    detail: {
      source_sku_ref: 'FX-SKU-009',
      product_name: '子牧羊小腿',
      receiver_phone: '13800000000',
      raw_payload: { access_token: 'do-not-render' },
    },
  });

  assert.equal(tooltip, '来源商品编号：FX-SKU-009；商品：子牧羊小腿');
  assert.doesNotMatch(tooltip, /13800000000|access_token|do-not-render|raw_payload/);
});

test('JD query presentation labels mock results honestly and never renders raw responses', () => {
  const view = jdQueryPresentation('MOCK', 'owners', {
    success: true,
    business_code: 'JD_OWNER_QUERY_OK',
    message: 'debug from SDK',
    request_id: 'trace-123',
    data: {
      ownerNo: 'OWN-01',
      ownerName: '上海事业部',
      token: 'secret-token',
      raw_response: { responseCode: 0 },
    },
  });

  assert.equal(view.title, '模拟事业部查询完成（不代表真实权限）');
  assert.deepEqual(view.rows, [
    { label: '事业部编码', value: 'OWN-01' },
    { label: '事业部名称', value: '上海事业部' },
  ]);
  assert.doesNotMatch(JSON.stringify(view), /JD_OWNER_QUERY_OK|debug from SDK|trace-123|secret-token|raw_response/);
});

test('audit presentation uses named operations and a narrow payload whitelist', () => {
  assert.equal(auditServiceLabel('jd.isc'), '京东仓配');
  assert.equal(auditOperationLabel('source-orders.upload'), '来源订单导入');
  assert.equal(auditOperationLabel('unknown.internal.operation'), '其他业务操作');

  const rows = safeAuditPayloadRows({
    order_no: 'ORDER-001',
    product_name: '子牧羊小腿',
    receiver_phone: '***',
    business_code: 'INTERNAL_CODE',
    raw_response: { stack: 'internal' },
  });
  assert.deepEqual(rows, [
    { label: '订单号', value: 'ORDER-001' },
    { label: '商品', value: '子牧羊小腿' },
  ]);
});
