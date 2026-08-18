import { ApiError, errorMessage } from '../../api/client';
import { saasVisualTokens } from '../../theme/saasTheme';
import {
  createAdminVisualSystem,
  permissionFailurePresentation,
  type AdminPageState,
  type AdminStatusPresentation,
} from './adminVisualCore';
export type { AdminStatusIcon, AdminStatusTone } from './adminVisualCore';

const adminVisualSystem = createAdminVisualSystem(saasVisualTokens);

export function adminStatusPresentation(status: string): AdminStatusPresentation {
  return adminVisualSystem.status(status);
}

export function adminCategoryColor(category: string): string {
  return adminVisualSystem.category(category);
}

export function adminStatusTextColor(status: string): string {
  return adminVisualSystem.tagText(status);
}

export function adminCategoryTextColor(category: string): string {
  return adminVisualSystem.categoryText(category);
}

export function adminPageState(loading: boolean, error: unknown, hasData: boolean): AdminPageState {
  return adminVisualSystem.pageState(loading, error, hasData);
}

export interface AdminFailurePresentation {
  kind: 'permission' | 'error';
  alertType: 'warning' | 'error';
  title: string;
  description: string;
}

export function adminFailurePresentation(error: unknown, fallbackTitle: string): AdminFailurePresentation {
  if (error instanceof ApiError) {
    const permission = permissionFailurePresentation(error.status);
    if (permission) return permission;
  }

  return { kind: 'error', alertType: 'error', title: fallbackTitle, description: errorMessage(error) };
}
