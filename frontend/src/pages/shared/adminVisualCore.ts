export type AdminStatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'error';
export type AdminStatusIcon = 'check' | 'clock' | 'info' | 'pause' | 'stop' | 'warning';

export interface AdminVisualTokenInput {
  data: {
    blue: string;
    cyan: string;
    violet: string;
    blueSoft: string;
    cyanSoft: string;
    violetSoft: string;
  };
  neutral: { 500: string };
  text: { primary: string; secondary: string };
  semantic: { info: string; success: string; warning: string; error: string };
}

export type AdminPageState = 'loading' | 'error' | 'ready-empty' | 'ready-data';

export interface AdminStatusPresentation {
  label: string;
  tone: AdminStatusTone;
  color: string;
  icon: AdminStatusIcon;
}

export function createAdminVisualSystem(tokens: AdminVisualTokenInput) {
  const statuses: Record<string, AdminStatusPresentation> = {
    PENDING: { label: '待处理', tone: 'warning', color: tokens.semantic.warning, icon: 'clock' },
    SUCCESS: { label: '已补齐', tone: 'success', color: tokens.semantic.success, icon: 'check' },
    PARTIAL: { label: '部分补齐', tone: 'info', color: tokens.semantic.info, icon: 'info' },
    FAILED: { label: '失败', tone: 'error', color: tokens.semantic.error, icon: 'warning' },
    CANCELLED: { label: '已取消', tone: 'neutral', color: tokens.neutral[500], icon: 'stop' },
    ACTIVE: { label: '启用', tone: 'success', color: tokens.semantic.success, icon: 'check' },
    INACTIVE: { label: '停用', tone: 'neutral', color: tokens.neutral[500], icon: 'pause' },
    CONFIGURED: { label: '已配置', tone: 'success', color: tokens.semantic.success, icon: 'check' },
    UNCONFIGURED: { label: '未配置', tone: 'neutral', color: tokens.neutral[500], icon: 'info' },
    MATCHED: { label: '可确认', tone: 'success', color: tokens.semantic.success, icon: 'check' },
    CONFLICT: { label: '存在冲突', tone: 'error', color: tokens.semantic.error, icon: 'warning' },
    NEED_REVIEW: { label: '待复核', tone: 'warning', color: tokens.semantic.warning, icon: 'warning' },
    AUDIT_SUCCESS: { label: '成功', tone: 'success', color: tokens.semantic.success, icon: 'check' },
    AUDIT_INCOMPLETE: { label: '未完成', tone: 'error', color: tokens.semantic.error, icon: 'warning' },
  };

  const categories: Record<string, string> = {
    CAISHIXIAN: tokens.data.blue,
    JUFUBAO: tokens.data.cyan,
    FEIXIANG: tokens.data.violet,
    ZHONGHUI: tokens.data.cyanSoft,
    WANGQI: tokens.data.violetSoft,
    DAZHE: tokens.data.violetSoft,
    WANQI: tokens.data.blueSoft,
    WECOM: tokens.data.blueSoft,
    MANUAL: tokens.data.cyanSoft,
    JD_WAREHOUSE: tokens.data.blue,
    THIRD_PARTY: tokens.data.cyan,
    REAL: tokens.data.violet,
    MOCK: tokens.data.blueSoft,
  };

  return {
    status(status: string): AdminStatusPresentation {
      return statuses[status] ?? {
        label: status,
        tone: 'neutral',
        color: tokens.neutral[500],
        icon: 'info',
      };
    },
    category(category: string): string {
      return categories[category] ?? tokens.data.violetSoft;
    },
    tagText(_status: string): string {
      return tokens.text.primary;
    },
    categoryText(_category: string): string {
      return tokens.text.secondary;
    },
    categoryAccent(category: string): string {
      return categories[category] ?? tokens.data.violetSoft;
    },
    pageState(loading: boolean, error: unknown, hasData: boolean): AdminPageState {
      if (loading) return 'loading';
      if (error) return 'error';
      return hasData ? 'ready-data' : 'ready-empty';
    },
  } as const;
}

export function permissionFailurePresentation(status: number) {
  if (status !== 401 && status !== 403) return null;
  return {
    kind: 'permission' as const,
    alertType: 'warning' as const,
    title: '暂无查看权限',
    description: '当前账号未获授权，请联系管理员确认权限。',
  };
}
