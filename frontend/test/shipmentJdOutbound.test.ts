import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  JD_BLOCKER_ENUM_LABELS,
  canSubmitJdOutbound,
  jdOutboundBlockerText,
  jdOutboundConfirmationDetail,
  jdOutboundConfirmationTitle,
  jdOutboundNotice,
  jdOutboundPresentation,
  jdOutboundRuntimeGate,
} from '../src/pages/fulfillment/shipmentJdOutbound.ts';
import {
  shipmentJdOutboundIdempotencyKey,
  shipmentJdOutboundSubmitRequest,
} from '../src/api/shipmentJdOutbound.ts';

test('shipment JD outbound presentation distinguishes in-flight, success and retry safety', () => {
  assert.deepEqual(jdOutboundPresentation({
    erp_delivery_no: '202608140001',
    sync_status: 'SUBMITTING',
    retry_count: 1,
    retryable: false,
    client_mode: 'REAL',
  }), {
    statusLabel: '提交中',
    statusTone: 'processing',
    modeLabel: '真实京东',
    actionLabel: '正在提交…',
    canSubmit: false,
  });

  assert.equal(jdOutboundPresentation({
    erp_delivery_no: '202608140001',
    sync_status: 'SUBMITTED',
    retry_count: 1,
    retryable: false,
    client_mode: 'MOCK',
  }).statusLabel, '已提交');
  assert.equal(jdOutboundPresentation({
    erp_delivery_no: '202608140001',
    sync_status: 'SYNC_FAILED',
    retry_count: 1,
    retryable: true,
    client_mode: 'MOCK',
  }).actionLabel, '重试提交');
  assert.equal(jdOutboundPresentation({
    erp_delivery_no: '202608140001',
    sync_status: 'SYNC_FAILED',
    retry_count: 1,
    retryable: false,
    client_mode: 'REAL',
  }).actionLabel, '需先对账');
  assert.equal(jdOutboundPresentation(undefined).actionLabel, '提交京东出库单');
});

test('shipment JD outbound submission uses the guarded Shipment endpoint and replay key', () => {
  assert.deepEqual(
    shipmentJdOutboundSubmitRequest('55', { 'Idempotency-Key': 'jd-submit-55' }),
    {
      path: '/api/v1/shipments/55/jd-so-order',
      options: {
        method: 'POST',
        body: {},
        headers: { 'Idempotency-Key': 'jd-submit-55' },
      },
    },
  );
  assert.equal(
    shipmentJdOutboundIdempotencyKey('55'),
    shipmentJdOutboundIdempotencyKey('55'),
    'the same Shipment operation must keep the same replay key across response-loss retries',
  );
  assert.notEqual(
    shipmentJdOutboundIdempotencyKey('55'),
    shipmentJdOutboundIdempotencyKey('56'),
  );
});

test('shipment JD outbound runtime gate fails closed until the current runtime is known', () => {
  assert.deepEqual(jdOutboundRuntimeGate(undefined), {
    ready: false,
    mode: undefined,
    confirmation: '运行环境尚未确认，不能提交',
  });
  assert.equal(jdOutboundRuntimeGate({ client_mode: 'REAL', live_ready: false }).ready, false);
  assert.equal(jdOutboundRuntimeGate({ client_mode: 'REAL', live_ready: true }).ready, true);
  assert.equal(jdOutboundRuntimeGate({ client_mode: 'MOCK', live_ready: false }).ready, true);
});

test('confirmation detail surfaces the erp delivery no and exact SKU cargo lines from the preview request', () => {
  const detail = jdOutboundConfirmationDetail({
    erp_delivery_no: 'ERP-UX-20260814-0001',
    request: {
      erpDeliveryNo: 'ERP-UX-20260814-0001',
      cargoInfos: [
        { orderLine: '1', goodsNo: 'JD-SKU-000001', goodsName: '鲜鸡蛋 30 枚装', planQuantity: 3 },
        { orderLine: '2', goodsNo: 'JD-SKU-000002', goodsName: '有机牛奶 1L', planQuantity: 6 },
      ],
    },
  }, 'ERP-STALE-0000');
  assert.equal(detail.erpDeliveryNo, 'ERP-UX-20260814-0001');
  assert.deepEqual(detail.cargos, [
    { orderLine: '1', goodsNo: 'JD-SKU-000001', goodsName: '鲜鸡蛋 30 枚装', planQuantity: 3 },
    { orderLine: '2', goodsNo: 'JD-SKU-000002', goodsName: '有机牛奶 1L', planQuantity: 6 },
  ]);
});

test('confirmation detail falls back to the persisted outbound no and tolerates malformed cargo rows', () => {
  const detail = jdOutboundConfirmationDetail({
    erp_delivery_no: '',
    request: {
      cargoInfos: [
        { orderLine: '1', goodsName: '无 SKU 映射商品', planQuantity: 2 },
        'not-an-object',
        { orderLine: '2', goodsNo: 'JD-SKU-000003', goodsName: '有机牛奶 1L', planQuantity: '6' },
      ],
    },
  }, 'ERP-PERSISTED-0001');
  assert.equal(detail.erpDeliveryNo, 'ERP-PERSISTED-0001');
  assert.deepEqual(detail.cargos, [
    { orderLine: '1', goodsNo: '', goodsName: '无 SKU 映射商品', planQuantity: 2 },
    { orderLine: '2', goodsNo: 'JD-SKU-000003', goodsName: '有机牛奶 1L', planQuantity: 6 },
  ]);
  assert.deepEqual(jdOutboundConfirmationDetail(null).cargos, []);
  assert.deepEqual(jdOutboundConfirmationDetail({ erp_delivery_no: 'ERP-UX-1', request: {} }).cargos, []);
});

test('REAL confirmation title keeps the real warning and appends the merchant outbound no', () => {
  assert.equal(
    jdOutboundConfirmationTitle('REAL', '确认向真实京东提交这张出库单？', 'ERP-UX-20260814-0001'),
    '确认向真实京东提交这张出库单？（商户出库号 ERP-UX-20260814-0001）',
  );
  assert.equal(
    jdOutboundConfirmationTitle('REAL', '确认向真实京东提交这张出库单？'),
    '确认向真实京东提交这张出库单？',
    'REAL keeps its warning even while the outbound no is still loading',
  );
  assert.equal(
    jdOutboundConfirmationTitle('MOCK', '确认在模拟环境提交？', 'ERP-UX-20260814-0001'),
    '确认在模拟环境提交？',
  );
  assert.equal(
    jdOutboundConfirmationTitle(undefined, '运行环境尚未确认，不能提交', 'ERP-UX-20260814-0001'),
    '运行环境尚未确认，不能提交',
  );
});

test('submission gate rejects retained async data while loading, failed, or from another Shipment', () => {
  const ready = {
    selectedShipmentId: '55',
    detailShipmentId: '55',
    previewShipmentId: '55',
    isJdShipment: true,
    presentationAllowsSubmit: true,
    detailLoading: false,
    detailError: false,
    previewSubmittable: true,
    previewLoading: false,
    previewError: false,
    runtimeReady: true,
    runtimeLoading: false,
    runtimeError: false,
    submitting: false,
  };
  assert.equal(canSubmitJdOutbound(ready), true);
  assert.equal(canSubmitJdOutbound({ ...ready, previewShipmentId: '54' }), false);
  assert.equal(canSubmitJdOutbound({ ...ready, detailShipmentId: '54' }), false);
  assert.equal(canSubmitJdOutbound({ ...ready, detailError: true }), false);
  assert.equal(canSubmitJdOutbound({ ...ready, previewLoading: true }), false);
  assert.equal(canSubmitJdOutbound({ ...ready, runtimeLoading: true }), false);
  assert.equal(canSubmitJdOutbound({ ...ready, runtimeError: true }), false);
});

test('OpenAPI models the submit response separately from the Shipment diagnostic state', () => {
  const openApi = readFileSync(
    fileURLToPath(new URL('../../docs/openapi.yaml', import.meta.url)),
    'utf8',
  );
  const operationStart = openApi.indexOf('  /api/v1/shipments/{shipment_id}/jd-so-order:\n');
  const operationEnd = openApi.indexOf('  /api/v1/shipments/{shipment_id}/jd-so-order-preview:\n');
  assert.notEqual(operationStart, -1);
  assert.notEqual(operationEnd, -1);
  const operation = openApi.slice(operationStart, operationEnd);
  assert.match(operation, /'201':[\s\S]*?#\/components\/schemas\/ShipmentJdOutboundSubmitResult/);

  const schemaStart = openApi.indexOf('    ShipmentJdOutboundSubmitResult:\n');
  assert.notEqual(schemaStart, -1, 'submit result schema must be explicit');
  const schemaEnd = openApi.indexOf('\n    ShipmentJdTrackingBackfillResult:', schemaStart);
  assert.notEqual(schemaEnd, -1, 'the next schema boundary must be explicit');
  const schema = openApi.slice(schemaStart, schemaEnd);
  assert.match(
    schema,
    /required: \[shipment_id, erp_delivery_no, outbound_order_no, sync_status, retry_count, plan_quantity, goods_count\]/,
  );
  assert.doesNotMatch(schema, /retryable|client_mode/);
});

test('Compose exposes explicit fail-closed JD outbound write configuration', () => {
  const compose = readFileSync(
    fileURLToPath(new URL('../../docker-compose.yml', import.meta.url)),
    'utf8',
  );
  const exampleEnv = readFileSync(
    fileURLToPath(new URL('../../.env.example', import.meta.url)),
    'utf8',
  );
  assert.match(compose, /JD_LOP_WRITE_MODE: \$\{JD_LOP_WRITE_MODE:-OFF\}/);
  assert.match(
    compose,
    /JD_OUTBOUND_AUTHORIZED_OPERATORS: \$\{JD_OUTBOUND_AUTHORIZED_OPERATORS:-\}/,
  );
  assert.match(exampleEnv, /^JD_LOP_WRITE_MODE=OFF$/m);
  assert.match(exampleEnv, /^JD_OUTBOUND_AUTHORIZED_OPERATORS=$/m);
  assert.match(compose, /\$\{APP_BIND_ADDRESS:-127\.0\.0\.1\}:\$\{APP_PORT:-8088\}:80/);
  assert.match(exampleEnv, /^APP_BIND_ADDRESS=127\.0\.0\.1$/m);
});

test('deployment and acceptance never fall back to predictable PostgreSQL credentials', () => {
  const compose = readFileSync(
    fileURLToPath(new URL('../../docker-compose.yml', import.meta.url)),
    'utf8',
  );
  const application = readFileSync(
    fileURLToPath(new URL('../../backend/src/main/resources/application.yml', import.meta.url)),
    'utf8',
  );
  const provisioner = readFileSync(
    fileURLToPath(new URL('../../docker/metabase-init/provision.sh', import.meta.url)),
    'utf8',
  );
  const postgresInit = readFileSync(
    fileURLToPath(new URL('../../docker/postgres/init/00-create-metabase-database.sql', import.meta.url)),
    'utf8',
  );
  const exampleEnv = readFileSync(
    fileURLToPath(new URL('../../.env.example', import.meta.url)),
    'utf8',
  );
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );

  assert.doesNotMatch(compose, /POSTGRES_(?:USER|PASSWORD):\s*\$\{POSTGRES_(?:USER|PASSWORD):-/);
  assert.match(compose, /POSTGRES_USER:\s*\$\{POSTGRES_USER:\?/);
  assert.match(compose, /POSTGRES_PASSWORD:\s*\$\{POSTGRES_PASSWORD:\?/);
  assert.match(application, /username:\s*\$\{DB_USERNAME\}/);
  assert.match(application, /password:\s*\$\{DB_PASSWORD\}/);
  assert.doesNotMatch(provisioner, /POSTGRES_(?:USER|PASSWORD):-fulfillment/);
  assert.doesNotMatch(postgresInit, /OWNER fulfillment/);
  assert.match(postgresInit, /current_user/);
  assert.match(exampleEnv, /^POSTGRES_USER=$/m);
  assert.match(exampleEnv, /^POSTGRES_PASSWORD=$/m);
  assert.match(acceptance, /acceptance_credentials\.py" "\$credentials_file"/);
  assert.match(acceptance, /acceptance_compose\.py" "\$credentials_file"/);
  assert.match(acceptance, /unset METABASE_ADMIN_EMAIL METABASE_ADMIN_PASSWORD APP_ADMIN_USER APP_ADMIN_PASSWORD POSTGRES_USER POSTGRES_PASSWORD/);
  assert.doesNotMatch(acceptance, /POSTGRES_(?:USER|PASSWORD)="\$\(sed /);
});

test('deployment credential tools never place reusable secrets in process arguments', () => {
  const gatewayEntrypoint = readFileSync(
    fileURLToPath(new URL('../../docker/nginx/entrypoint.sh', import.meta.url)),
    'utf8',
  );
  const provisioner = readFileSync(
    fileURLToPath(new URL('../../docker/metabase-init/provision.sh', import.meta.url)),
    'utf8',
  );
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );

  assert.doesNotMatch(gatewayEntrypoint, /htpasswd[^\n]*"\$APP_ADMIN_PASSWORD"/);
  assert.match(gatewayEntrypoint, /htpasswd[^\n]*\s-i(?:\s|\\|$)/);
  assert.ok(
    gatewayEntrypoint.indexOf('unset APP_ADMIN_PASSWORD') < gatewayEntrypoint.indexOf('htpasswd '),
  );
  assert.match(gatewayEntrypoint, /APP_ADMIN_PASSWORD_MIN_LENGTH:-16/);
  assert.match(gatewayEntrypoint, /APP_ADMIN_PASSWORD_MIN_LENGTH must be an integer between 6 and 128/);

  assert.match(provisioner, /secret_dir="\$\(mktemp -d/);
  assert.match(provisioner, /chmod 700 "\$secret_dir"/);
  assert.ok(
    provisioner.indexOf('unset METABASE_ADMIN_PASSWORD POSTGRES_PASSWORD')
      < provisioner.indexOf('secret_dir="$(mktemp -d'),
  );
  assert.match(provisioner, /--data-binary "@\$payload_file"/);
  assert.match(provisioner, /--header "@\$session_header_file"/);
  assert.doesNotMatch(provisioner, /(?:-d|--data) "\$payload"/);
  assert.doesNotMatch(provisioner, /-H "X-Metabase-Session: \$session_id"/);
  assert.doesNotMatch(provisioner, /--arg (?:password|db_password) "\$(?:admin_password|POSTGRES_PASSWORD)"/);

  assert.doesNotMatch(acceptance, /--config "\$acceptance_curl_config"/);
  // 06: 边缘认证默认开启后，验收脚本从私有凭据文件派生 Basic 头；硬编码令牌与
  // curl --user 明文密码两种旧模式都被禁止。
  assert.match(acceptance, /"Authorization"\] = "Basic " \+ gateway_basic_auth/);
  assert.doesNotMatch(acceptance, /Authorization: Basic [A-Za-z0-9+/=]{16,}/);
  assert.doesNotMatch(acceptance, /--user "\$APP_ADMIN_USER:\$APP_ADMIN_PASSWORD"/);
});

test('the credential-gated gateway overwrites browser identity at every business and demo boundary', () => {
  const nginx = readFileSync(
    fileURLToPath(new URL('../../docker/nginx/default.conf', import.meta.url)),
    'utf8',
  );
  const gatewayEntrypoint = readFileSync(
    fileURLToPath(new URL('../../docker/nginx/entrypoint.sh', import.meta.url)),
    'utf8',
  );

  // 06: 边缘 Basic Auth 由环境变量 GATEWAY_BASIC_AUTH_ENABLED（默认开）驱动，
  // entrypoint 渲染 edge-auth.inc；default.conf 不再写死 auth_basic off。
  assert.match(nginx, /server \{[\s\S]*?include \/etc\/nginx\/edge-auth\.inc;/);
  assert.match(gatewayEntrypoint, /GATEWAY_BASIC_AUTH_ENABLED/);
  assert.match(gatewayEntrypoint, /edge-auth\.inc/);
  assert.doesNotMatch(nginx, /auth_basic "Zimu Fulfillment ERP";/);
  assert.match(
    nginx,
    /location \/api\/ \{[\s\S]*?include \/etc\/nginx\/backend-auth\.inc;/,
  );
  for (const location of ['location = /demo/v1 {', 'location /demo/v1/ {']) {
    const start = nginx.indexOf(location);
    assert.notEqual(start, -1);
    const end = nginx.indexOf('\n    }', start);
    const block = nginx.slice(start, end);
    assert.match(block, /include \/etc\/nginx\/backend-auth\.inc;/);
    assert.doesNotMatch(block, /local-operator|proxy_set_header Authorization "";/);
  }
  assert.doesNotMatch(nginx, /proxy_set_header X-Operator \$http_x_operator;/);
  assert.doesNotMatch(nginx, /proxy_set_header Authorization \$http_authorization;/);
  assert.match(gatewayEntrypoint, /proxy_set_header X-Operator "%s";/);
  assert.match(gatewayEntrypoint, /proxy_set_header Authorization "Basic %s";/);
  assert.match(gatewayEntrypoint, /chmod 600 \/etc\/nginx\/backend-auth\.inc/);
  assert.match(nginx, /location \/internal\/ \{\s*return 404;\s*\}/);
  for (const location of [
    'location /wecom/callbacks/',
    'location /customer/v1/order-assistant/',
    'location /metabase/',
    'location / {',
  ]) {
    const start = nginx.indexOf(location);
    assert.notEqual(start, -1);
    const end = nginx.indexOf('\n    }', start);
    assert.match(nginx.slice(start, end), /proxy_set_header Authorization "";/);
  }
});

// ---------- 已提交后的提示与阻断原因说人话（2026-08-31 生产实证） ----------

const SUBMITTED_OUTBOUND = {
  erp_delivery_no: '202608310004',
  jd_delivery_no: 'ESL00000025545270765',
  sync_status: 'SUBMITTED',
  retry_count: 1,
  retryable: false,
  client_mode: 'REAL',
} as const;

/** 后端 ShipmentJdOutboundPreparer 对已提交批次实际返回的三条 blocker（消息逐字同源）。 */
const RESUBMIT_PRECHECK_BLOCKERS = [
  { message: '发货批次状态必须是 CREATED 才能提交京东出库单（当前 SHIPPED）' },
  { message: '订单行必须处于 READY_TO_EXPORT 或 WAITING_PROVIDER 阶段（当前 TRACKING_RECEIVED）' },
  { message: '该发货批次已提交京东出库单，禁止重复提交' },
];

test('a submitted outbound shows a success note instead of the resubmit precheck warning', () => {
  // 商户出库号 202608310004：已提交成功 + 已回传运单的批次，把「不可提交」预检渲染成
  // 黄色警告 + 裸枚举，用户误判流程出错、不敢回传。已提交就是成功终态，必须说人话。
  const notice = jdOutboundNotice({
    outbound: SUBMITTED_OUTBOUND,
    preview: { submittable: false, blockers: RESUBMIT_PRECHECK_BLOCKERS },
    canSyncToSource: true,
  });
  assert.ok(notice.kind === 'SUBMITTED_OK', '已提交批次不得再渲染「当前不可提交」');
  assert.match(notice.message, /已提交/);
  assert.match(notice.message, /无需重复提交/);
  assert.match(notice.description, /回传给客户平台/);

  // 已提交但运单还没回来：下一步是等京东回传运单，而不是回传入口
  const waiting = jdOutboundNotice({
    outbound: SUBMITTED_OUTBOUND,
    preview: { submittable: false, blockers: RESUBMIT_PRECHECK_BLOCKERS },
    canSyncToSource: false,
  });
  assert.ok(waiting.kind === 'SUBMITTED_OK');
  assert.match(waiting.description, /运单/);
});

test('genuine pre-submit blockers read in Chinese with no raw enum tokens', () => {
  const notice = jdOutboundNotice({
    outbound: undefined,
    preview: {
      submittable: false,
      blockers: [
        { message: '发货批次状态必须是 CREATED 才能提交京东出库单（当前 SHIPPED）' },
        { message: '订单行必须处于 READY_TO_EXPORT 或 WAITING_PROVIDER 阶段（当前 TRACKING_RECEIVED）' },
      ],
    },
    canSyncToSource: false,
  });
  assert.ok(notice.kind === 'BLOCKED');
  assert.equal(notice.message, '当前不可提交');
  assert.deepEqual(notice.reasons, [
    '发货批次状态必须是「已创建」才能提交京东出库单（当前「已发货」）',
    '订单行必须处于「待生成发货表」或「等待履约方」阶段（当前「已取得运单」）',
  ]);
});

test('unknown identifiers in blocker messages stay verbatim instead of being guessed', () => {
  // sourceSyncWording 同款哲学：翻不出的原样显示，宁可被人看见去问，也不编一句可能错的话。
  assert.equal(
    jdOutboundBlockerText({ message: '仅京东云仓（JD_WAREHOUSE）发货批次可提交京东出库单' }),
    '仅京东云仓（JD_WAREHOUSE）发货批次可提交京东出库单',
  );
  assert.equal(
    jdOutboundBlockerText({ message: '履约方配置缺少京东标识 warehouseNo，请先补齐后再建单' }),
    '履约方配置缺少京东标识 warehouseNo，请先补齐后再建单',
  );
});

test('the notice stays silent while submitting, reconciling, or when the preview allows submit', () => {
  const base = { ...SUBMITTED_OUTBOUND };
  const blocked = { submittable: false, blockers: RESUBMIT_PRECHECK_BLOCKERS };
  assert.equal(jdOutboundNotice({
    outbound: { ...base, sync_status: 'SUBMITTING' },
    preview: blocked,
    canSyncToSource: false,
  }).kind, 'NONE');
  // 需对账（提交结果未知）：结果未知就闭嘴，由 last_error_message 的告警负责解释
  assert.equal(jdOutboundNotice({
    outbound: { ...base, sync_status: 'SYNC_FAILED', retryable: false },
    preview: blocked,
    canSyncToSource: false,
  }).kind, 'NONE');
  // 可重试失败 + 预检不过：要给出翻译后的阻断原因
  assert.equal(jdOutboundNotice({
    outbound: { ...base, sync_status: 'SYNC_FAILED', retryable: true },
    preview: blocked,
    canSyncToSource: false,
  }).kind, 'BLOCKED');
  // 预检可提交或预检尚未返回：无话可说
  assert.equal(jdOutboundNotice({
    outbound: undefined,
    preview: { submittable: true, blockers: [] },
    canSyncToSource: false,
  }).kind, 'NONE');
  assert.equal(jdOutboundNotice({ outbound: undefined, preview: null, canSyncToSource: false }).kind, 'NONE');
});

test('duplicate blocker reasons collapse to one line', () => {
  // 后端对每条订单行各发一条 STAGE_INVALID，多行同阶段时消息逐字相同；重复刷屏无信息量
  const notice = jdOutboundNotice({
    outbound: undefined,
    preview: {
      submittable: false,
      blockers: [
        { message: '订单行必须处于 READY_TO_EXPORT 或 WAITING_PROVIDER 阶段（当前 TRACKING_RECEIVED）' },
        { message: '订单行必须处于 READY_TO_EXPORT 或 WAITING_PROVIDER 阶段（当前 TRACKING_RECEIVED）' },
      ],
    },
    canSyncToSource: false,
  });
  assert.ok(notice.kind === 'BLOCKED');
  assert.equal(notice.reasons.length, 1);
});

test('本地枚举文案与 constants/labels.ts 单表逐字一致（防漂移对账）', () => {
  // JD_BLOCKER_ENUM_LABELS 因 node:test 加载不了 labels.ts（值依赖 @/ 别名模块）而本地建表；
  // 类型只锁键覆盖，这里按源文本锁文案：labels.ts 改词而这里没跟上时立即红。
  const labelsSource = readFileSync(
    fileURLToPath(new URL('../src/constants/labels.ts', import.meta.url)),
    'utf8',
  );
  for (const [token, label] of Object.entries(JD_BLOCKER_ENUM_LABELS)) {
    assert.ok(
      labelsSource.includes(`${token}: '${label}'`),
      `${token} 的文案「${label}」与 constants/labels.ts 不一致`,
    );
  }
});
