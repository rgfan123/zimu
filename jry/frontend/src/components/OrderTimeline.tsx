/**
 * 订单事件时间线 —— 演示亮点组件（PRD §18）。
 * 自定义纵向时间线（非 AntD Timeline），按 sequence_no 排列：
 *   - 每个事件：事件类型图标圆点（按 tone 着色）+ 中文标题 + 时间/操作人 + 关联实体 + payload 明细
 *   - 最后一条事件高亮为「当前」状态
 * payload 为自由结构（openapi additionalProperties），按已知键中文化、未知键原样展示。
 */

import { Empty, Skeleton, Tag, Typography } from 'antd';
import {
  BarcodeOutlined,
  CarOutlined,
  CheckCircleOutlined,
  CheckSquareOutlined,
  CloudSyncOutlined,
  CloseCircleOutlined,
  DatabaseOutlined,
  DropboxOutlined,
  EditOutlined,
  FileTextOutlined,
  InboxOutlined,
  LinkOutlined,
  SendOutlined,
  ShoppingOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import dayjs from 'dayjs';
import { ORDER_EVENT_FALLBACK, ORDER_EVENT_META, type EventTone } from '@/constants/labels';
import type { OrderEvent } from '@/api/types';
import { displayOperator, safeEventPayloadRows } from '@/presentation/publicReady';

const EVENT_ICONS: Record<string, ReactNode> = {
  ORDER_RECEIVED: <InboxOutlined />,
  ORDER_UPDATED: <EditOutlined />,
  SKU_MAPPED: <LinkOutlined />,
  JD_STOCK_CHECKED: <DatabaseOutlined />,
  JD_OUTBOUND_SUBMITTED: <SendOutlined />,
  JD_OUTBOUND_ACCEPTED: <CheckCircleOutlined />,
  JD_SHIPPED: <CarOutlined />,
  SHIPMENT_CREATED: <DropboxOutlined />,
  TRACKING_RECEIVED: <BarcodeOutlined />,
  PROCUREMENT_REQUESTED: <ShoppingOutlined />,
  PROCUREMENT_COMPLETED: <CheckSquareOutlined />,
  SOURCE_SYNCED: <CloudSyncOutlined />,
  FULFILLMENT_EXCEPTION: <WarningOutlined />,
  SYNC_FAILED: <CloseCircleOutlined />,
};

export interface OrderTimelineProps {
  events: OrderEvent[];
  loading?: boolean;
  /** 展示高度上限（超出内部滚动），默认 520 */
  maxHeight?: number;
}

export default function OrderTimeline({ events, loading, maxHeight = 520 }: OrderTimelineProps) {
  if (loading) {
    return (
      <div style={{ padding: '16px 8px' }}>
        <Skeleton active paragraph={{ rows: 6 }} />
      </div>
    );
  }

  if (!events.length) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无事件记录" style={{ padding: '32px 0' }} />;
  }

  return (
    <div className="tl" style={{ maxHeight, overflowY: 'auto', paddingRight: 4 }}>
      {events.map((event, index) => {
        const meta = ORDER_EVENT_META[event.event_type_code] ?? ORDER_EVENT_FALLBACK;
        const isLast = index === events.length - 1;
        const tone: EventTone = meta.tone;
        const payloadRows = safeEventPayloadRows(event.payload);
        return (
          <div className="tl-item" key={event.id}>
            <div className="tl-rail">
              <span className={`tl-dot tl-dot--${tone}`}>{EVENT_ICONS[event.event_type_code] ?? <FileTextOutlined />}</span>
              {!isLast ? <span className="tl-line" /> : null}
            </div>
            <div className="tl-body">
              <div className="tl-head">
                <span className={`tl-title tl-title--${tone}`}>
                  {meta.label}
                  {isLast ? <Tag color={tone === 'red' ? 'error' : 'processing'} style={{ marginInlineStart: 8, borderRadius: 6 }}>当前</Tag> : null}
                </span>
                <span className="tl-time">
                  {dayjs(event.created_at).format('YYYY-MM-DD HH:mm:ss')} · {displayOperator(event.operator)}
                </span>
              </div>
              {payloadRows.length ? (
                <div className="tl-payload">
                  {payloadRows.map((row) => (
                    <span key={row.label} className="tl-kv">
                      <span className="tl-kv-label">{row.label}</span>
                      <span className="tl-kv-value">{row.value}</span>
                    </span>
                  ))}
                </div>
              ) : null}
            </div>
          </div>
        );
      })}
      <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 34, display: 'inline-block' }}>
        共 {events.length} 条事件 · 按发生顺序排列
      </Typography.Text>
    </div>
  );
}
