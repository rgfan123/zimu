import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const appLayoutPath = fileURLToPath(new URL('../src/components/layout/AppLayout.tsx', import.meta.url));
const brandAvatarPath = fileURLToPath(new URL('../src/assets/zimu-brand-avatar.png', import.meta.url));

test('application shell renders the supplied square brand avatar instead of the placeholder icon', () => {
  const source = readFileSync(appLayoutPath, 'utf8');

  assert.match(source, /import brandAvatarUrl from '@\/assets\/zimu-brand-avatar\.png';/);
  assert.match(source, /<img[\s\S]*?src=\{brandAvatarUrl\}[\s\S]*?alt=""/);
  assert.doesNotMatch(source, /DeploymentUnitOutlined/);
});

test('brand avatar has a square high-density PNG source for the 32px shell icon', () => {
  const png = readFileSync(brandAvatarPath);

  assert.equal(png.subarray(1, 4).toString('ascii'), 'PNG');
  assert.equal(png.readUInt32BE(16), 256);
  assert.equal(png.readUInt32BE(20), 256);
});
