import type { CSSProperties } from 'react';
import { saasVisualTokens } from '../../theme/saasTheme';
import { createAnalyticsVisualSystem } from './analyticsVisual';

export const analyticsVisualSystem = createAnalyticsVisualSystem(saasVisualTokens);

export const analyticsCssVariables = {
  '--analytics-canvas': saasVisualTokens.surface.canvas,
  '--analytics-surface': saasVisualTokens.surface.raised,
  '--analytics-sunken': saasVisualTokens.surface.sunken,
  '--analytics-border': saasVisualTokens.neutral[300],
  '--analytics-border-soft': saasVisualTokens.neutral[100],
  '--analytics-text': saasVisualTokens.text.primary,
  '--analytics-text-secondary': saasVisualTokens.text.secondary,
  '--analytics-text-tertiary': saasVisualTokens.text.tertiary,
  '--analytics-brand': saasVisualTokens.brand.primary,
  '--analytics-brand-hover': saasVisualTokens.brand.hover,
  '--analytics-brand-active': saasVisualTokens.brand.active,
  '--analytics-brand-focus': saasVisualTokens.brand.focus,
  '--analytics-brand-subtle': saasVisualTokens.brand.subtle,
  '--analytics-success': saasVisualTokens.semantic.success,
  '--analytics-warning': saasVisualTokens.semantic.warning,
  '--analytics-error': saasVisualTokens.semantic.error,
  '--analytics-motion-fast': '0.12s',
  '--analytics-motion-mid': '0.16s',
} as CSSProperties;
