/**
 * Issue #64：运营提醒抽屉（从 ManualReviewPage 拆出）。
 * 只展示提醒事实与「确认已知晓」动作——ACK 不推进订单或履约状态，
 * 只记录处理人、时间、备注与审计日志。动作状态全部收在本组件。
 */

import { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Input, Space, Typography, message } from 'antd';
import dayjs from 'dayjs';
import type { OperationalAlert } from '@/api/types';
import { operationalAlertsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { ALERT_STATUS_LABELS } from './queuePresentation';

export interface AlertDrawerProps {
  selected: OperationalAlert | null;
  onClose: () => void;
  /** ACK 成功后刷新提醒列表。 */
  onQueueReload: () => void;
}

export default function AlertDrawer({ selected, onClose, onQueueReload }: AlertDrawerProps) {
  const [messageApi, messageContext] = message.useMessage();
  const [alertNote, setAlertNote] = useState('');
  const [alertSubmitError, setAlertSubmitError] = useState<string>();
  const [alertSubmitting, setAlertSubmitting] = useState(false);

  useEffect(() => {
    setAlertNote('');
    setAlertSubmitError(undefined);
  }, [selected?.id]);

  async function acknowledgeAlert() {
    if (!selected || !alertNote.trim()) {
      setAlertSubmitError('请填写确认备注');
      return;
    }
    setAlertSubmitting(true);
    setAlertSubmitError(undefined);
    try {
      await operationalAlertsApi.acknowledge(selected.id, {
        expected_version: selected.version,
        note: alertNote.trim(),
      });
      messageApi.success('运营提醒已确认；业务状态未被推进');
      onClose();
      onQueueReload();
    } catch (error) {
      setAlertSubmitError(errorMessage(error));
    } finally {
      setAlertSubmitting(false);
    }
  }

  return (
    <Drawer title={selected ? `运营提醒 ${selected.alert_no}` : '运营提醒'} open={Boolean(selected)} onClose={onClose} width={560}>
      {messageContext}
      {selected ? (
        <Space direction="vertical" size={18} style={{ width: '100%' }}>
          <Descriptions size="small" column={1} items={[
            { key: 'message', label: '提醒内容', children: selected.message },
            { key: 'severity', label: '等级', children: selected.severity === 'RED' ? '红色' : '黄色' },
            { key: 'order', label: '关联订单', children: selected.order_id ? `#${selected.order_id}` : '—' },
            { key: 'status', label: '状态', children: ALERT_STATUS_LABELS[selected.status] },
            { key: 'version', label: '当前版本', children: selected.version },
          ]} />
          <Alert type="info" showIcon message="确认提醒不会推进订单或履约状态" description="此动作只记录处理人、时间、备注和审计日志。" />
          {selected.status === 'OPEN' ? (
            <>
              <Input.TextArea value={alertNote} onChange={(event) => setAlertNote(event.target.value)} rows={4} maxLength={1000} showCount placeholder="填写已知晓后的跟进安排" />
              {alertSubmitError ? <Alert type="error" showIcon message="确认未完成" description={alertSubmitError} /> : null}
              <Button type="primary" loading={alertSubmitting} onClick={acknowledgeAlert}>确认已知晓</Button>
            </>
          ) : (
            <Typography.Text type="secondary">{selected.acknowledged_by ? `${selected.acknowledged_by} 已于 ${dayjs(selected.acknowledged_at).format('YYYY-MM-DD HH:mm')} 确认` : '该提醒已关闭'}</Typography.Text>
          )}
        </Space>
      ) : null}
    </Drawer>
  );
}
