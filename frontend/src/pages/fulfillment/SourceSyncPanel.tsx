import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Popconfirm, Space, Tag, Typography, message } from 'antd';
import { shipmentsApi } from '@/api/endpoints';
import type { SourceSyncCheck } from '@/api/sourceSync';
import { errorMessage } from '@/api/client';
import type { SourceChannel } from '@/api/types';
import { CHANNEL_LABELS } from '@/constants/labels';

const { Text } = Typography;

const ADDRESS_STATUS_LABELS: Record<string, string> = {
  CLEAR: '与我方一致',
  CONFIRMATION_REQUIRED: '平台侧已变更，需人工确认',
  UNKNOWN: '未知',
};

interface Props {
  shipmentId: string;
  /** 回传成功后让外层刷新发货单详情与列表。 */
  onSynced: () => void;
}

/**
 * 来源回传入口：把我方运单号写回客户的来源平台。
 *
 * <p><b>为什么是两步而不是一个按钮</b>：check 只读，会真去平台读一次当前事实（收货地址有没有
 * 被改、这单还在不在待发货、物流公司能不能映射）；execute 携带那次 check 的 check_hash，
 * 服务端据此确认「人看到的就是要写的」。中间平台事实变了哈希就对不上，服务端拒绝，
 * 不会拿一份过期的结论去写客户的系统。
 *
 * <p>检查<b>不</b>在抽屉打开时自动跑：它是一次真实的平台读取，不该因为随手点开一张发货单
 * 就发出去。
 */
export default function SourceSyncPanel({ shipmentId, onSynced }: Props) {
  const [check, setCheck] = useState<SourceSyncCheck | null>(null);
  const [checking, setChecking] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const runCheck = async () => {
    setChecking(true);
    try {
      const result = await shipmentsApi.checkSourceSync(shipmentId);
      setCheck(result);
    } catch (err) {
      setCheck(null);
      message.error(errorMessage(err));
    } finally {
      setChecking(false);
    }
  };

  const execute = async () => {
    if (!check?.ready) return;
    setSubmitting(true);
    try {
      const outcome = await shipmentsApi.executeSourceSync(shipmentId, check.check_hash);
      if (outcome.status === 'SYNCED') {
        message.success(`已回传${channelLabel(check)}：${outcome.message}`);
      } else {
        // RECONCILIATION_REQUIRED 等非成功终态必须原样说出来，不能当成功报。
        message.warning(`回传未确认成功（${outcome.status}）：${outcome.message}`);
      }
      await runCheck();
      onSynced();
    } catch (err) {
      message.error(errorMessage(err));
      await runCheck();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card
      size="small"
      title="来源平台回传"
      extra={(
        <Button size="small" loading={checking} onClick={runCheck}>
          {check ? '重新检查' : '检查回传条件'}
        </Button>
      )}
    >
      {!check ? (
        <Text type="secondary">
          点「检查回传条件」会去来源平台读一次当前事实，确认无误后才能回传。
        </Text>
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions
            size="small"
            column={1}
            items={[
              {
                key: 'c',
                label: '来源平台',
                children: `${channelLabel(check)} · 子单 ${check.internal.source_line_ref ?? '—'}`,
              },
              {
                key: 't',
                label: '将写入',
                children: check.internal.tracking_number
                  ? `${check.internal.carrier_output_value ?? check.internal.carrier_name ?? '—'} · ${check.internal.tracking_number}`
                  : '—',
              },
              {
                key: 'p',
                label: '平台当前状态',
                children: check.platform.available
                  ? `${check.platform.platform_state ?? '—'} · 可发 ${check.platform.sendable_quantity ?? '—'} · 地址${ADDRESS_STATUS_LABELS[check.platform.address_status] ?? check.platform.address_status}`
                  : <Text type="danger">{check.platform.message}</Text>,
              },
            ]}
          />

          {check.blockers.length > 0 ? (
            <Alert
              type="warning"
              showIcon
              message={`${check.blockers.length} 项阻断，回传按钮不可用`}
              description={(
                <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                  {check.blockers.map((blocker) => (
                    <li key={blocker.code}>
                      {blocker.message}
                      <Tag style={{ marginInlineStart: 6 }}>{blocker.code}</Tag>
                    </li>
                  ))}
                </ul>
              )}
            />
          ) : null}

          <Popconfirm
            title="确认回传来源平台"
            description={(
              <div style={{ maxWidth: 340 }}>
                这是一次<b>不可逆</b>的外部写：会把
                {' '}<b>{check.internal.carrier_output_value ?? '—'} {check.internal.tracking_number ?? '—'}</b>
                {' '}写进 {channelLabel(check)} 的子单 {check.internal.source_line_ref ?? '—'}。
                写完系统会回查平台确认，回查不符或读不到都会转人工对账。
              </div>
            )}
            okText="确认回传"
            cancelText="取消"
            onConfirm={execute}
            disabled={!check.ready || submitting}
          >
            <Button type="primary" loading={submitting} disabled={!check.ready}>
              回传运单号到{channelLabel(check)}
            </Button>
          </Popconfirm>
        </Space>
      )}
    </Card>
  );
}

/** 渠道值来自服务端，可能是词表里还没有的历史技术值——认不出就原样显示，不猜。 */
function channelLabel(check: SourceSyncCheck): string {
  const channel = check.internal.source_channel;
  return CHANNEL_LABELS[channel as SourceChannel] ?? channel;
}
