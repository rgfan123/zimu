import type { ThemeConfig } from 'antd';

/**
 * 子牧 SaaS 视觉基线。
 *
 * 普通强调只能从 brand / neutral / chart 中取色；success、warning、error、info
 * 仅用于对应业务语义。后续页面与 ECharts 配置都应从本文件取色，避免继续扩散页面级调色板。
 */
export const saasVisualTokens = {
  brand: {
    primary: '#3f6fd1',
    hover: '#3564c4',
    active: '#2f5fbe',
    focus: '#6682c9',
    subtle: '#edf3ff',
  },
  data: {
    blue: '#3f6fd1',
    cyan: '#2f8793',
    violet: '#7567b1',
    blueSoft: '#7f9bdd',
    cyanSoft: '#69a8ae',
    violetSoft: '#9a91c7',
  },
  neutral: {
    50: '#f7f8fa',
    100: '#eef1f4',
    300: '#d7dce4',
    500: '#7d8796',
    700: '#495261',
    900: '#202633',
  },
  surface: {
    canvas: '#f7f9fc',
    raised: '#ffffff',
    sunken: '#f1f4f8',
  },
  text: {
    primary: '#202633',
    secondary: '#5f6878',
    tertiary: '#667080',
    inverse: '#ffffff',
  },
  semantic: {
    info: '#526b9f',
    success: '#3f7d65',
    warning: '#9a6a32',
    error: '#a44f57',
  },
} as const;

/**
 * 图表只使用品牌同色阶与中性色；状态图层必须显式选择 status 中的语义色。
 */
export const saasChartPalette = {
  categorical: [
    saasVisualTokens.data.blue,
    saasVisualTokens.data.cyan,
    saasVisualTokens.data.violet,
    saasVisualTokens.data.blueSoft,
    saasVisualTokens.data.cyanSoft,
    saasVisualTokens.data.violetSoft,
  ],
  sequential: [
    saasVisualTokens.brand.subtle,
    saasVisualTokens.data.blueSoft,
    saasVisualTokens.brand.primary,
    saasVisualTokens.brand.active,
    '#244895',
  ],
  status: saasVisualTokens.semantic,
} as const;

export const saasTheme: ThemeConfig = {
  cssVar: { key: 'zimu-saas' },
  token: {
    colorPrimary: saasVisualTokens.brand.primary,
    colorPrimaryHover: saasVisualTokens.brand.hover,
    colorPrimaryActive: saasVisualTokens.brand.active,
    colorPrimaryBg: saasVisualTokens.brand.subtle,
    colorPrimaryBgHover: '#dfe9ff',
    colorPrimaryBorder: saasVisualTokens.brand.focus,
    colorPrimaryBorderHover: saasVisualTokens.brand.hover,
    colorPrimaryText: saasVisualTokens.brand.primary,
    colorPrimaryTextHover: saasVisualTokens.brand.hover,
    colorPrimaryTextActive: saasVisualTokens.brand.active,
    colorInfo: saasVisualTokens.semantic.info,
    colorInfoBg: '#edf3ff',
    colorInfoBorder: '#bfd0f3',
    colorSuccess: saasVisualTokens.semantic.success,
    colorSuccessBg: '#edf5f1',
    colorSuccessBorder: '#c5ddd2',
    colorWarning: saasVisualTokens.semantic.warning,
    colorWarningBg: '#faf3e8',
    colorWarningBorder: '#e7d2b2',
    colorError: saasVisualTokens.semantic.error,
    colorErrorBg: '#fbefef',
    colorErrorBorder: '#e9c7ca',
    colorLink: saasVisualTokens.brand.primary,
    colorLinkHover: saasVisualTokens.brand.hover,
    colorLinkActive: saasVisualTokens.brand.active,
    colorText: saasVisualTokens.text.primary,
    colorTextSecondary: saasVisualTokens.text.secondary,
    colorTextTertiary: saasVisualTokens.text.tertiary,
    colorTextHeading: '#171c26',
    colorTextDisabled: '#9da5b1',
    colorBgLayout: saasVisualTokens.surface.canvas,
    colorBgContainer: saasVisualTokens.surface.raised,
    colorBgElevated: saasVisualTokens.surface.raised,
    colorBgSpotlight: saasVisualTokens.neutral[900],
    colorFill: '#e9eef6',
    colorFillSecondary: saasVisualTokens.neutral[100],
    colorFillTertiary: saasVisualTokens.neutral[50],
    colorFillQuaternary: '#fbfcfe',
    colorBorder: saasVisualTokens.neutral[300],
    colorBorderSecondary: '#e2e7ef',
    controlOutline: saasVisualTokens.brand.focus,
    controlOutlineWidth: 2,
    borderRadius: 8,
    borderRadiusLG: 10,
    controlHeight: 34,
    boxShadow: '0 1px 2px rgba(32, 38, 51, 0.06)',
    boxShadowSecondary: '0 8px 24px rgba(32, 38, 51, 0.08)',
    motionDurationFast: '0.12s',
    motionDurationMid: '0.16s',
    motionDurationSlow: '0.18s',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Button: {
      primaryShadow: 'none',
      defaultShadow: 'none',
      dangerShadow: 'none',
    },
    Card: {
      headerBg: saasVisualTokens.surface.raised,
    },
    Layout: {
      bodyBg: saasVisualTokens.surface.canvas,
      headerBg: saasVisualTokens.surface.raised,
      siderBg: saasVisualTokens.surface.raised,
    },
    Menu: {
      itemBg: saasVisualTokens.surface.raised,
      itemColor: saasVisualTokens.text.secondary,
      itemHoverBg: saasVisualTokens.neutral[50],
      itemHoverColor: saasVisualTokens.text.primary,
      itemSelectedBg: saasVisualTokens.brand.subtle,
      itemSelectedColor: saasVisualTokens.brand.active,
      subMenuItemBg: saasVisualTokens.surface.raised,
      itemBorderRadius: 6,
      // Issue #104 外壳密度对齐原型：条目 32px / 13.5px，分组标题 10.5px 弱化。
      itemHeight: 32,
      itemMarginBlock: 2,
      fontSize: 13.5,
      groupTitleFontSize: 11,
      groupTitleColor: saasVisualTokens.text.tertiary,
    },
    Table: {
      headerBg: saasVisualTokens.neutral[50],
      headerColor: saasVisualTokens.text.secondary,
      borderColor: '#e2e7ef',
      rowHoverBg: '#f5f8fd',
    },
    Tabs: {
      itemColor: saasVisualTokens.text.secondary,
      itemHoverColor: saasVisualTokens.brand.hover,
      itemSelectedColor: saasVisualTokens.brand.active,
      inkBarColor: saasVisualTokens.brand.primary,
    },
  },
};
