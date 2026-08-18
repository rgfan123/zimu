/**
 * 状态标签：按枚举值渲染中文 Tag。未知值回退原码。
 * 状态走 antd 语义预设（随 saasTheme token 降饱和）；来源渠道是普通分类，
 * 用中性底 + 品牌色阶圆点点缀（色点 + 文字双通道，不只靠颜色区分）。
 */

import { Tag, theme } from 'antd';
import {
  CHANNEL_ACCENT,
} from '@/pages/shared/semanticStatus';
import {
  CHANNEL_LABELS,
  ORDER_STATUS_COLORS,
  ORDER_STATUS_LABELS,
  PROCESSING_HEALTH_COLORS,
  PROCESSING_HEALTH_LABELS,
  PROCESSING_STAGE_COLORS,
  PROCESSING_STAGE_LABELS,
  SHIPMENT_STATUS_COLORS,
  SHIPMENT_STATUS_LABELS,
} from '@/constants/labels';
import type { OrderStatus, ProcessingHealth, ProcessingStage, ShipmentStatus, SourceChannel } from '@/api/types';

type Kind = 'channel' | 'orderStatus' | 'stage' | 'health' | 'shipmentStatus';

const metaOf: Record<Kind, { labels: Record<string, string>; colors: Record<string, string | undefined> }> = {
  channel: { labels: CHANNEL_LABELS, colors: {} },
  orderStatus: { labels: ORDER_STATUS_LABELS, colors: ORDER_STATUS_COLORS },
  stage: { labels: PROCESSING_STAGE_LABELS, colors: PROCESSING_STAGE_COLORS },
  health: { labels: PROCESSING_HEALTH_LABELS, colors: PROCESSING_HEALTH_COLORS },
  shipmentStatus: { labels: SHIPMENT_STATUS_LABELS, colors: SHIPMENT_STATUS_COLORS },
};

export interface StatusTagProps {
  kind: Kind;
  value: SourceChannel | OrderStatus | ProcessingStage | ProcessingHealth | ShipmentStatus | string;
  /** 自定义样式覆盖 */
  style?: React.CSSProperties;
}

export default function StatusTag({ kind, value, style }: StatusTagProps) {
  const { token } = theme.useToken();
  const meta = metaOf[kind];
  const label = meta.labels[value] ?? value;

  if (kind === 'channel') {
    const accent = CHANNEL_ACCENT[value as SourceChannel] ?? token.colorTextQuaternary;
    return (
      <Tag
        bordered={false}
        style={{ marginInlineEnd: 0, background: token.colorFillTertiary, ...style }}
      >
        <span
          aria-hidden="true"
          style={{ display: 'inline-block', width: 6, height: 6, borderRadius: '50%', background: accent, marginInlineEnd: 5, verticalAlign: 'middle' }}
        />
        {label}
      </Tag>
    );
  }

  return (
    <Tag color={meta.colors[value]} style={{ marginInlineEnd: 0, ...style }}>
      {label}
    </Tag>
  );
}
