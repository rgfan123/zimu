/**
 * PageState —— 全站统一「页面状态」组件（issue #36「全站页面状态组件（加载/错误/空态）」）。
 *
 * 三态（loading / error / empty）用一个判别联合 props 表达，页面不必再各自拼装
 * AdminLoading / AdminFailureAlert / AdminEmpty：
 *
 * - `state="loading"`：Spin + 描述文案（默认「正在加载…」，可自定义 description）
 * - `state="error"`  ：错误 Alert（默认文案「加载失败」，可自定义 message / description）
 *                      附「重试」按钮，点击触发 onRetry 回调
 * - `state="empty"`  ：空态 Empty（默认「暂无数据」，可自定义 description）
 *
 * 三态共用同一面板外壳（admin-surface + admin-state-panel），视觉与既有
 * AdminVisualComponents（AdminLoading / AdminFailureAlert / AdminEmpty）保持一致。
 *
 * 注意：PageState 面向页面级状态插槽（loading → error → 内容 / empty 的整块切换）；
 * 表格 locale.emptyText 之类的裸空态请继续使用 AdminEmpty（无需面板外壳）。
 */

import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Spin, Typography } from 'antd';

import './adminSurface.css';

/** 三态统一文案，页面如需与默认值对齐可从这里引用。 */
export const PAGE_STATE_COPY = {
  loading: '正在加载…',
  error: '加载失败',
  retry: '重试',
  empty: '暂无数据',
} as const;

/** 判别联合：error 分支强制要求 onRetry，保证「可重试」语义在类型层面成立。 */
export type PageStateProps =
  | { state: 'loading'; description?: string }
  | { state: 'error'; message?: string; description?: string; onRetry: () => void }
  | { state: 'empty'; description?: string };

export function PageState(props: PageStateProps) {
  switch (props.state) {
    case 'loading':
      return (
        <div className="admin-surface admin-state-panel" role="status" aria-live="polite">
          <Spin size="small" />
          <Typography.Text type="secondary">{props.description ?? PAGE_STATE_COPY.loading}</Typography.Text>
        </div>
      );
    case 'error':
      return (
        <div className="admin-surface admin-state-panel">
          <Alert
            type="error"
            showIcon
            style={{ width: '100%' }}
            message={props.message ?? PAGE_STATE_COPY.error}
            description={props.description}
            action={
              <Button size="small" icon={<ReloadOutlined />} onClick={props.onRetry}>
                {PAGE_STATE_COPY.retry}
              </Button>
            }
          />
        </div>
      );
    case 'empty':
      return (
        <div className="admin-surface admin-state-panel">
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={props.description ?? PAGE_STATE_COPY.empty} />
        </div>
      );
  }
}
