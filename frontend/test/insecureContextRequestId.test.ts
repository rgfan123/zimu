/**
 * 明文 HTTP 下的请求追踪号(2026-08-27 生产事故回归)。
 *
 * `crypto.randomUUID()` 带 [SecureContext],只在 HTTPS 与 localhost 存在。经局域网 IP 或
 * 公网 IP:端口以明文 HTTP 访问时它是 undefined,直接调用会在 fetch 发出**之前**抛 TypeError
 * ——表现为「所有后端请求都网络失败」,而服务端一条访问日志都没有。生产实证:Chrome 取到
 * 全部静态资源(200)后零 API 请求。
 *
 * 本用例把非安全上下文钉死:crypto 只有 getRandomValues(甚至完全没有)时,请求头照样生成。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { newRequestId } from '../src/api/client.ts';

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function withCrypto<T>(replacement: unknown, run: () => T): T {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  Object.defineProperty(globalThis, 'crypto', { value: replacement, configurable: true });
  try {
    return run();
  } finally {
    if (original) Object.defineProperty(globalThis, 'crypto', original);
    else delete (globalThis as { crypto?: unknown }).crypto;
  }
}

test('安全上下文:有 randomUUID 时直接用它', () => {
  const id = withCrypto({ randomUUID: () => '11111111-2222-4333-8444-555555555555' }, newRequestId);
  assert.equal(id, '11111111-2222-4333-8444-555555555555');
});

test('明文 HTTP:没有 randomUUID 时用 getRandomValues 合成 v4 UUID,不抛异常', () => {
  const id = withCrypto(
    {
      getRandomValues: (array: Uint8Array) => {
        array.fill(0xab);
        return array;
      },
    },
    newRequestId,
  );
  assert.match(id, UUID_SHAPE, '必须仍是 UUID 形状,便于服务端按同一格式追踪');
  assert.equal(id[14], '4', 'version 位必须是 4');
  assert.ok(['8', '9', 'a', 'b'].includes(id[19]), 'variant 位必须合规');
});

test('极端环境:完全没有 crypto 也要产出非空追踪号', () => {
  const id = withCrypto(undefined, newRequestId);
  assert.ok(id.length > 0);
  assert.doesNotThrow(() => newRequestId());
});

test('追踪号不重复', () => {
  const ids = new Set(
    Array.from({ length: 200 }, () =>
      withCrypto(
        {
          getRandomValues: (array: Uint8Array) => {
            for (let i = 0; i < array.length; i += 1) array[i] = Math.floor(Math.random() * 256);
            return array;
          },
        },
        newRequestId,
      ),
    ),
  );
  assert.equal(ids.size, 200);
});
