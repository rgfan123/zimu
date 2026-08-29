import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Popconfirm, Space, Typography, message } from 'antd';
import { shipmentsApi } from '@/api/endpoints';
import type { SourceSyncCheck } from '@/api/sourceSync';
import { errorMessage } from '@/api/client';
import type { SourceChannel } from '@/api/types';
import { CHANNEL_LABELS } from '@/constants/labels';
import { presentSourceSync } from './sourceSyncWording';

const { Text } = Typography;

const ADDRESS_STATUS_LABELS: Record<string, string> = {
  CLEAR: '和我们这边一致',
  CONFIRMATION_REQUIRED: '平台上被改过，要人工确认',
  UNKNOWN: '读不到',
};

interface Props {
  shipmentId: string;
  /** 回传成功后让外层刷新发货单详情与列表。 */
  onSynced: () => void;
}

/**
 * 来源回传入口：把我方运单号写回客户的来源平台。
 *
 * <p><b>为什么是两步而不是一个按钮</b>：检查只读，会真去平台读一次当前事实（地址有没有
 * 被改、单子还在不在待发货、物流公司能不能映射）；回传携带那次检查的 check_hash，
 * 服务端据此确认「人看到的就是要写的」；中间平台事实变了哈希就对不上，服务端拒绝，
 * 不会拿一份过期的结论去写客户的系统。
 *
 * <p>检查<b>不</b>在抽屉打开时自动跑：那是一次真实的外部读取，不该因为随手点开一张发货单
 * 就发出去。
 *
 * <p>所有面向人的文案走 {@link presentSourceSync}——服务端的 business_code 是排障标识，
 * 不是给操作员读的。
 */
export default function SourceSyncPanel({ shipmentId, onSynced }: Props) {
  const [check, setCheck] = useState<SourceSyncCheck | null>(null);
  const [checking, setChecking] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const runCheck = async () => {
    setChecking(true);
    try {
      setCheck(await shipmentsApi.checkSourceSync(shipmentId));
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
        message.success(`已回传${channelLabel(check)}，平台侧已确认收到运单号`);
      } else {
        // 非 SYNCED 的终态必须原样说出来，绝不当成功报。
        message.warning(`回传发出去了，但平台没确认成功：${outcome.message}`);
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

  const view = check ? presentSourceSync(check) : null;

  return (
    <Card
      size="small"
      title="回传给客户平台"
      extra={(
        <Button size="small" loading={checking} onClick={runCheck}>
          {check ? '重新检查' : '检查能不能回传'}
        </Button>
      )}
    >
      {!check || !view ? (
        <Text type="secondary">
          我们发完货，客户平台那边还不知道运单号。点「检查能不能回传」会去平台读一次当前情况，
          确认没问题才能发。
        </Text>
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions
            size="small"
            column={1}
            items={[
              {
                key: 'w',
                label: '要告诉平台',
                children: check.internal.tracking_number
                  ? `${check.internal.carrier_output_value ?? check.internal.carrier_name ?? '—'} ${check.internal.tracking_number}`
                  : '—',
              },
              {
                key: 'c',
                label: '写到哪',
                children: `${channelLabel(check)} 的子单 ${check.internal.source_line_ref ?? '—'}`,
              },
              // 平台读不到时这一行没有内容可说，理由已经在下面的清单里，不重复报一遍。
              ...(check.platform.available
                ? [{
                  key: 'p',
                  label: '平台那边',
                  children: `还能发 ${check.platform.sendable_quantity ?? '—'} 份 · 收货地址${ADDRESS_STATUS_LABELS[check.platform.address_status] ?? check.platform.address_status}`,
                }]
                : []),
            ]}
          />

          {view.tone === 'done' ? (
            <Alert type="success" showIcon message={view.headline} />
          ) : null}

          {view.tone === 'blocked' ? (
            <Alert
              type="warning"
              showIcon
              message={view.headline}
              description={(
                <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                  {view.reasons.map((reason) => (
                    <li key={reason.code}>
                      {reason.text}
                      {/* 码留着但压到最轻：排障要得到，操作员不用读。 */}
                      <Text type="secondary" style={{ fontSize: 11, marginInlineStart: 6 }}>
                        {reason.code}
                      </Text>
                    </li>
                  ))}
                </ul>
              )}
            />
          ) : null}

          <Popconfirm
            title="确认回传"
            description={(
              <div style={{ maxWidth: 340 }}>
                这一步会真的写进{channelLabel(check)}，<b>撤不回来</b>。
                告诉平台的是：<b>{check.internal.carrier_output_value ?? '—'} {check.internal.tracking_number ?? '—'}</b>。
                写完系统会去平台回查一次，对不上或读不到都会转人工对账。
              </div>
            )}
            okText="确认，发出去"
            cancelText="再想想"
            onConfirm={execute}
            disabled={!check.ready || submitting}
          >
            <Button type="primary" loading={submitting} disabled={!check.ready}>
              {view.actionLabel}
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
