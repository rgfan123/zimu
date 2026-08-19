import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { shipmentJdOutboundIdempotencyKey } from '../src/api/shipmentJdOutbound.ts';
import { trustedWriteHeaders } from '../src/api/writeHeaders.ts';

test('local Vite routes browser traffic through the loopback gateway without exposing internal APIs', () => {
  const vite = readFileSync(fileURLToPath(new URL('../vite.config.ts', import.meta.url)), 'utf8');

  assert.match(vite, /http:\/\/127\.0\.0\.1:8088/);
  assert.doesNotMatch(vite, /http:\/\/localhost:8080/);
  assert.doesNotMatch(vite, /local-dev-operator/);
  assert.doesNotMatch(vite, /['"]\/internal(?:\/|['"])/);
  assert.doesNotMatch(vite, /X-Operator['"]?\s*:/);
});

test('canonical browser acceptance authenticates at the credential-gated public seam and never exposes internal APIs', () => {
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );

  assert.doesNotMatch(acceptance, /["']\/internal\//);
  // 06: 边缘认证默认开启后，验收脚本从私有凭据文件派生 Basic 头；源码里不得出现
  // 硬编码的凭据/令牌，也不得把密码放进 curl 的 argv（--config / --user 两种旧模式都被禁止）。
  assert.match(acceptance, /"Authorization"\] = "Basic " \+ gateway_basic_auth/);
  assert.doesNotMatch(acceptance, /--config "\$acceptance_curl_config"/);
  assert.doesNotMatch(acceptance, /Authorization: Basic [A-Za-z0-9+/=]{16,}/);
  assert.doesNotMatch(acceptance, /error\.code == 401/);
  assert.match(acceptance, /request_headers = \{"Accept": "application\/json", \*\*\(headers or \{\}\)\}/);
});

test('production authentication policy has no runtime off switch and test substitution stays test-only', () => {
  const application = readFileSync(
    fileURLToPath(new URL('../../backend/src/main/resources/application.yml', import.meta.url)),
    'utf8',
  );
  const testApplication = readFileSync(
    fileURLToPath(new URL('../../backend/src/test/resources/application.properties', import.meta.url)),
    'utf8',
  );
  const productionPolicy = readFileSync(
    fileURLToPath(new URL(
      '../../backend/src/main/java/cn/zimu/fulfillment/common/web/ProductionRequestAuthenticationPolicy.java',
      import.meta.url,
    )),
    'utf8',
  );
  const testPolicy = readFileSync(
    fileURLToPath(new URL(
      '../../backend/src/test/java/cn/zimu/fulfillment/common/web/TestRequestAuthenticationConfiguration.java',
      import.meta.url,
    )),
    'utf8',
  );

  assert.doesNotMatch(application, /require-authenticated-business-writes|APP_REQUIRE_AUTHENTICATED_BUSINESS_WRITES/);
  assert.match(testApplication, /^spring\.profiles\.active=test-fixtures$/m);
  assert.match(productionPolicy, /@Profile\("!test-fixtures"\)/);
  assert.match(testPolicy, /@Profile\("test-fixtures"\)/);
});

test('acceptance always binds its isolated stack to loopback regardless of ambient deployment config', () => {
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );

  assert.match(acceptance, /^base_url="http:\/\/127\.0\.0\.1:\$app_port"$/m);
  assert.match(acceptance, /^APP_BIND_ADDRESS="127\.0\.0\.1"$/m);
  assert.match(acceptance, /^export APP_BIND_ADDRESS$/m);
  assert.doesNotMatch(acceptance, /APP_BIND_ADDRESS="\$\{APP_BIND_ADDRESS/);
});

test('acceptance state is created exclusively inside its private runtime directory', () => {
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );

  assert.match(acceptance, /^chmod 700 "\$acceptance_secret_dir"$/m);
  assert.match(acceptance, /^state_file="\$acceptance_secret_dir\/state\.json"$/m);
  assert.match(acceptance, /os\.O_WRONLY \| os\.O_CREAT \| os\.O_EXCL/);
  assert.match(acceptance, /flags \|= os\.O_NOFOLLOW/);
  assert.match(acceptance, /os\.fchmod\(descriptor, 0o600\)/);
  assert.doesNotMatch(acceptance, /ACCEPTANCE_STATE_FILE:-/);
  assert.doesNotMatch(acceptance, /Path\(os\.environ\["ACCEPTANCE_STATE_FILE"\]\)\.write_text/);
});

test('explicit acceptance credentials pass the same strength gate as generated bundles', () => {
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );
  const explicitBranch = acceptance.slice(
    acceptance.indexOf('if [ -n "${METABASE_ADMIN_EMAIL:-}" ]'),
    acceptance.indexOf('\nelse\n'),
  );

  assert.match(
    explicitBranch,
    /python3 "\$repo_root\/scripts\/acceptance_credentials\.py" --validate-environment/,
  );
});

test('internal service identity is independently configurable without a predictable default', () => {
  const compose = readFileSync(
    fileURLToPath(new URL('../../docker-compose.yml', import.meta.url)),
    'utf8',
  );
  const exampleEnv = readFileSync(
    fileURLToPath(new URL('../../.env.example', import.meta.url)),
    'utf8',
  );

  assert.match(compose, /APP_INTERNAL_SERVICE_NAME: \$\{APP_INTERNAL_SERVICE_NAME:-\}/);
  assert.match(compose, /APP_INTERNAL_SERVICE_TOKEN: \$\{APP_INTERNAL_SERVICE_TOKEN:-\}/);
  assert.match(exampleEnv, /^APP_INTERNAL_SERVICE_NAME=$/m);
  assert.match(exampleEnv, /^APP_INTERNAL_SERVICE_TOKEN=$/m);
  assert.doesNotMatch(compose, /APP_INTERNAL_SERVICE_(?:NAME|TOKEN):\s*(?:wecom|internal|changeme)/i);
});

test('acceptance secrets only enter the Compose child and are file-backed for other consumers', () => {
  const acceptance = readFileSync(
    fileURLToPath(new URL('../../scripts/acceptance.sh', import.meta.url)),
    'utf8',
  );
  const secretNames = [
    'METABASE_ADMIN_EMAIL',
    'METABASE_ADMIN_PASSWORD',
    'APP_ADMIN_USER',
    'APP_ADMIN_PASSWORD',
    'POSTGRES_USER',
    'POSTGRES_PASSWORD',
  ];

  assert.match(
    acceptance,
    /python3 "\$repo_root\/scripts\/acceptance_compose\.py" "\$credentials_file" "\$project_name" "\$repo_root\/docker-compose\.yml" "\$@"/,
  );
  assert.match(
    acceptance,
    /acceptance_credentials\.py" --write-environment "\$ephemeral_credentials_file"/,
  );
  assert.match(acceptance, /^ACCEPTANCE_CREDENTIAL_FILE="\$credentials_file" \\$/m);
  assert.doesNotMatch(acceptance, /export (?:METABASE_ADMIN|APP_ADMIN|POSTGRES)/);
  const unsetPosition = acceptance.indexOf('unset METABASE_ADMIN_EMAIL METABASE_ADMIN_PASSWORD');
  const firstConsumerPosition = acceptance.indexOf('ACCEPTANCE_CREDENTIAL_FILE="$credentials_file"');
  const cleanupTrapPosition = acceptance.indexOf('trap cleanup_acceptance_secrets EXIT HUP INT TERM');
  const explicitBundlePosition = acceptance.indexOf('--write-environment "$ephemeral_credentials_file"');
  assert.ok(unsetPosition > 0 && unsetPosition < firstConsumerPosition);
  assert.ok(cleanupTrapPosition > 0 && cleanupTrapPosition < explicitBundlePosition);
  for (const name of secretNames) {
    assert.doesNotMatch(acceptance, new RegExp(`^${name}="\\$${name}" \\\\$`, 'm'));
  }
});

test('the existing PostgreSQL volume has an explicit least-privilege migration gate', () => {
  const migrationGate = readFileSync(
    fileURLToPath(new URL('../../docs/postgres-role-migration.md', import.meta.url)),
    'utf8',
  );

  for (const role of [
    'database owner',
    'application runtime',
    'Metabase metadata',
    'analytics read-only',
  ]) {
    assert.match(migrationGate, new RegExp(`\\b${role}\\b`, 'i'));
  }
  assert.match(migrationGate, /Current status: blocked on external migration evidence/);
  assert.match(migrationGate, /postgres-data/);
  assert.match(migrationGate, /backup.*restore drill/is);
  assert.match(migrationGate, /negative privilege checks/is);
  assert.match(migrationGate, /must not be implemented as a Flyway application migration/i);
  assert.match(migrationGate, /Changing environment variables does not migrate existing roles or grants/i);
});

test('release documentation does not overstate the production identity or database boundary', () => {
  const readme = readFileSync(
    fileURLToPath(new URL('../../README.md', import.meta.url)),
    'utf8',
  );
  const ticket = readFileSync(
    fileURLToPath(new URL(
      '../../.scratch/mvp-productization/issues/09-identity-and-admin-credentials.md',
      import.meta.url,
    )),
    'utf8',
  );

  assert.match(readme, /docs\/postgres-role-migration\.md/);
  assert.match(readme, /still shares one PostgreSQL login/i);
  assert.match(readme, /single shared Basic credential/i);
  assert.match(readme, /does not provide per-user attribution or remote access control/i);
  assert.match(ticket, /independent Bearer token/i);
  assert.match(ticket, /request_id.*trace_id/is);
  assert.match(ticket, /test-only.*test-fixtures/is);
  assert.doesNotMatch(ticket, /test profile.*(?:disable|close|turn off)/i);
  assert.match(ticket, /passwordless local/i);
  assert.match(ticket, /role split.*not migrated/is);
});

test('browser write headers contain an idempotency key but never claim an operator identity', () => {
  const headers = trustedWriteHeaders();
  assert.match(headers['Idempotency-Key'], /^[0-9a-f-]{36}$/i);
  assert.equal(headers['X-Operator'], undefined);
});

test('browser write header extras cannot inject trusted identity, credentials, or replay keys', () => {
  const headers = trustedWriteHeaders({ extra: {
    'Content-Type': 'application/json',
    Authorization: 'Bearer attacker',
    authorization: 'Basic attacker',
    'X-Operator': 'attacker',
    'x-operator': 'also-attacker',
    'Idempotency-Key': 'attacker-controlled',
    'idempotency-key': 'also-attacker-controlled',
  } });

  assert.equal(headers['Content-Type'], 'application/json');
  assert.equal(
    Object.keys(headers).some((name) => ['authorization', 'x-operator'].includes(name.toLowerCase())),
    false,
  );
  const idempotencyEntries = Object.entries(headers)
    .filter(([name]) => name.toLowerCase() === 'idempotency-key');
  assert.equal(idempotencyEntries.length, 1);
  assert.match(idempotencyEntries[0][1], /^[0-9a-f-]{36}$/i);
  assert.notEqual(idempotencyEntries[0][1], 'attacker-controlled');
  assert.notEqual(idempotencyEntries[0][1], 'also-attacker-controlled');
});

test('a trusted stable replay key survives while reserved extra headers remain blocked', () => {
  const trustedReplayKey = shipmentJdOutboundIdempotencyKey('55');
  const headers = trustedWriteHeaders({
    idempotencyKey: trustedReplayKey,
    extra: {
      'IDEMPOTENCY-KEY': 'attacker-controlled',
      Authorization: 'Bearer attacker',
      'X-Operator': 'attacker',
    },
  });

  assert.equal(
    Object.keys(headers).some((name) => ['authorization', 'x-operator'].includes(name.toLowerCase())),
    false,
  );
  const idempotencyEntries = Object.entries(headers)
    .filter(([name]) => name.toLowerCase() === 'idempotency-key');
  assert.equal(idempotencyEntries.length, 1);
  assert.equal(idempotencyEntries[0][1], 'shipment-jd-so-order-55');
});

test('an explicit replay key rejects empty, unsafe, or oversized header values', () => {
  for (const value of ['', 'short', 'valid-key\nforged-header: yes', 'x'.repeat(256)]) {
    assert.throws(
      () => trustedWriteHeaders({ idempotencyKey: value }),
      /explicit idempotency key must be 8 to 255 visible characters/,
    );
  }
});
