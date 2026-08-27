import assert from 'node:assert/strict';
import test from 'node:test';
import { NetworkError, errorMessage, isPrivateNetworkHost } from '../src/api/client.ts';

/**
 * zimu-issue-115：「网络连接失败，请检查网络后重试」不带任何可定位信息，导致每次
 * 根因不同（残留 dev server / 离开局域网后标签页还指着内网 IP / nginx keepalive
 * 竞态）都要人肉排查。本文件覆盖：
 * - isPrivateNetworkHost 纯函数（10.x / 192.168.x / 172.16-31.x / localhost / 127.x）。
 * - errorMessage 对 NetworkError 拼出的可自证提示（origin + 路径 + 内网提示）。
 */

test('isPrivateNetworkHost 识别私网与本机地址', () => {
  assert.equal(isPrivateNetworkHost('192.168.1.22'), true);
  assert.equal(isPrivateNetworkHost('10.0.5.9'), true);
  assert.equal(isPrivateNetworkHost('172.16.0.1'), true);
  assert.equal(isPrivateNetworkHost('172.31.255.255'), true);
  assert.equal(isPrivateNetworkHost('127.0.0.1'), true);
  assert.equal(isPrivateNetworkHost('localhost'), true);
  assert.equal(isPrivateNetworkHost('LOCALHOST'), true, '大小写不敏感');
  assert.equal(isPrivateNetworkHost('::1'), true);
});

test('isPrivateNetworkHost 不误判公网地址或相邻网段', () => {
  assert.equal(isPrivateNetworkHost('example.com'), false);
  assert.equal(isPrivateNetworkHost('8.8.8.8'), false);
  assert.equal(isPrivateNetworkHost('172.15.255.255'), false, '172.16 之前一个地址不属于私网段');
  assert.equal(isPrivateNetworkHost('172.32.0.1'), false, '172.31 之后一个地址不属于私网段');
  assert.equal(isPrivateNetworkHost('1921.68.1.1'), false, '不是合法 IPv4 不应误判');
});

test('errorMessage 对公网 NetworkError 展示实际请求 origin+路径，不附加内网提示', () => {
  const err = new NetworkError('http://203.0.113.10:8088', '/api/v1/orders', new TypeError('Failed to fetch'));
  const message = errorMessage(err);
  assert.match(message, /无法连接 http:\/\/203\.0\.113\.10:8088\/api\/v1\/orders/);
  assert.match(message, /改用外网地址访问/);
  assert.doesNotMatch(message, /内网地址/);
});

test('errorMessage 对内网 origin 的 NetworkError 额外提示离开该网络将无法访问', () => {
  const err = new NetworkError('http://192.168.1.22', '/api/v1/orders');
  const message = errorMessage(err);
  assert.match(message, /无法连接 http:\/\/192\.168\.1\.22\/api\/v1\/orders/);
  assert.match(message, /当前使用的是内网地址，离开该网络将无法访问/);
});

test('errorMessage 对 localhost origin 的 NetworkError 也判定为内网', () => {
  const err = new NetworkError('http://localhost:5173', '/api/v1/orders');
  assert.match(errorMessage(err), /当前使用的是内网地址，离开该网络将无法访问/);
});

test('errorMessage 对已取消的请求仍返回“操作已取消”，不受 NetworkError 改动影响', () => {
  const abort = Object.assign(new Error('aborted'), { name: 'AbortError' });
  assert.equal(errorMessage(abort), '操作已取消');
});

test('errorMessage 对无法识别的异常兜底展示当前页面 origin（模拟无 DOM 上下文的调用方）', () => {
  const restoreWindow = 'window' in globalThis ? (globalThis as { window?: unknown }).window : undefined;
  const hadWindow = 'window' in globalThis;
  (globalThis as { window: { location: { origin: string; pathname: string } } }).window = {
    location: { origin: 'http://10.1.2.3:8088', pathname: '/orders' },
  };
  try {
    const message = errorMessage(new Error('boom'));
    assert.match(message, /无法连接 http:\/\/10\.1\.2\.3:8088\/orders/);
    assert.match(message, /当前使用的是内网地址，离开该网络将无法访问/);
  } finally {
    if (hadWindow) (globalThis as { window: unknown }).window = restoreWindow;
    else delete (globalThis as { window?: unknown }).window;
  }
});
