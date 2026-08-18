import { createElement, type ReactNode } from 'react';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  PauseCircleOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Spin, Tag, Typography, theme } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import {
  adminCategoryColor,
  adminCategoryTextColor,
  adminFailurePresentation,
  adminStatusPresentation,
  adminStatusTextColor,
  type AdminStatusIcon,
} from './adminVisual';

const STATUS_ICONS: Record<AdminStatusIcon, typeof CheckCircleOutlined> = {
  check: CheckCircleOutlined,
  clock: ClockCircleOutlined,
  info: InfoCircleOutlined,
  pause: PauseCircleOutlined,
  stop: StopOutlined,
  warning: ExclamationCircleOutlined,
};

export function AdminStatusTag({ status }: { status: string }) {
  const presentation = adminStatusPresentation(status);
  const { token } = theme.useToken();

  return (
    <Tag
      bordered={false}
      icon={createElement(STATUS_ICONS[presentation.icon], { style: { color: presentation.color } })}
      style={{
        color: adminStatusTextColor(status),
        background: token.colorFillTertiary,
        marginInlineEnd: 0,
      }}
    >
      {presentation.label}
    </Tag>
  );
}

export function AdminCategoryTag({ category, children }: { category: string; children: ReactNode }) {
  const { token } = theme.useToken();

  return (
    <Tag
      bordered={false}
      style={{
        color: adminCategoryTextColor(category),
        background: token.colorFillTertiary,
        marginInlineEnd: 0,
      }}
    >
      <span className="admin-tag-dot" style={{ background: adminCategoryColor(category) }} aria-hidden="true" />
      {children}
    </Tag>
  );
}

export function AdminFailureAlert({
  error,
  title,
  onRetry,
}: {
  error: unknown;
  title: string;
  onRetry: () => void;
}) {
  const presentation = adminFailurePresentation(error, title);

  return (
    <Alert
      type={presentation.alertType}
      showIcon
      message={presentation.title}
      description={presentation.description}
      action={
        <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>
          重试
        </Button>
      }
    />
  );
}

export function AdminEmpty({ description }: { description: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} />;
}

export function AdminLoading({ description }: { description: string }) {
  return (
    <div className="admin-surface admin-state-panel" role="status" aria-live="polite">
      <Spin size="small" />
      <Typography.Text type="secondary">{description}</Typography.Text>
    </div>
  );
}
