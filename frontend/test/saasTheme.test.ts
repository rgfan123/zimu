import assert from 'node:assert/strict';
import test from 'node:test';
import { saasChartPalette, saasTheme, saasVisualTokens } from '../src/theme/saasTheme.ts';

function relativeLuminance(hex: string): number {
  const channels = hex
    .replace('#', '')
    .match(/.{2}/g)!
    .map((channel) => Number.parseInt(channel, 16) / 255)
    .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4));
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

function contrastRatio(foreground: string, background: string): number {
  const foregroundLuminance = relativeLuminance(foreground);
  const backgroundLuminance = relativeLuminance(background);
  const lighter = Math.max(foregroundLuminance, backgroundLuminance);
  const darker = Math.min(foregroundLuminance, backgroundLuminance);
  return (lighter + 0.05) / (darker + 0.05);
}

test('application theme exposes one clear brand family and distinct semantic roles', () => {
  assert.equal(saasVisualTokens.brand.primary, '#3f6fd1');
  assert.equal(saasTheme.token?.colorPrimary, saasVisualTokens.brand.primary);
  assert.equal(saasTheme.token?.colorBgLayout, saasVisualTokens.surface.canvas);
  assert.equal(saasTheme.token?.colorText, saasVisualTokens.text.primary);

  const semanticColors = Object.values(saasVisualTokens.semantic);
  assert.equal(new Set([saasVisualTokens.brand.primary, ...semanticColors]).size, semanticColors.length + 1);
  assert.ok(contrastRatio(saasVisualTokens.brand.primary, '#ffffff') >= 4.5);
  assert.ok(contrastRatio(saasVisualTokens.text.primary, saasVisualTokens.surface.canvas) >= 7);
  assert.ok(contrastRatio(saasVisualTokens.text.secondary, saasVisualTokens.surface.raised) >= 4.5);
  assert.ok(contrastRatio(saasVisualTokens.text.tertiary, saasVisualTokens.surface.raised) >= 4.5);
  assert.ok(contrastRatio(saasVisualTokens.text.tertiary, saasVisualTokens.surface.canvas) >= 4.5);
});

test('focus border and outline remain distinguishable on raised and canvas surfaces', () => {
  const focusBorder = saasTheme.token?.colorPrimaryBorder;
  const focusOutline = saasTheme.token?.controlOutline;

  assert.equal(typeof focusBorder, 'string');
  assert.equal(typeof focusOutline, 'string');
  assert.match(focusBorder as string, /^#[0-9a-f]{6}$/i);
  assert.match(focusOutline as string, /^#[0-9a-f]{6}$/i);

  for (const focusColor of [focusBorder as string, focusOutline as string]) {
    assert.ok(contrastRatio(focusColor, saasVisualTokens.surface.raised) >= 3);
    assert.ok(contrastRatio(focusColor, saasVisualTokens.surface.canvas) >= 3);
  }
});

test('chart colors use a shared lively data ramp without borrowing semantic status colors', () => {
  const allowed = new Set([...Object.values(saasVisualTokens.data), ...Object.values(saasVisualTokens.neutral)]);
  const semanticColors = new Set(Object.values(saasVisualTokens.semantic));

  assert.equal(saasChartPalette.categorical.length, 6);
  assert.ok(saasChartPalette.categorical.every((color) => allowed.has(color)));
  assert.ok(saasChartPalette.categorical.every((color) => !semanticColors.has(color)));
  assert.equal(new Set(saasChartPalette.categorical.slice(0, 3)).size, 3);
  assert.deepEqual(saasChartPalette.status, saasVisualTokens.semantic);
});

test('application surfaces stay bright enough to read as live UI rather than a grey screenshot', () => {
  assert.ok(relativeLuminance(saasVisualTokens.surface.canvas) >= 0.94);
  assert.ok(relativeLuminance(saasVisualTokens.surface.raised) >= 0.99);
  assert.ok(contrastRatio(saasVisualTokens.brand.primary, saasVisualTokens.surface.raised) >= 4.5);
});

test('theme interaction timings stay within the restrained SaaS motion budget', () => {
  assert.equal(saasTheme.token?.motionDurationFast, '0.12s');
  assert.equal(saasTheme.token?.motionDurationMid, '0.16s');
  assert.equal(saasTheme.token?.motionDurationSlow, '0.18s');
});
