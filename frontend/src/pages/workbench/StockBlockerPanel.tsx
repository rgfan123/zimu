import { useCallback, useState } from 'react';
import { Alert, Button, Descriptions, List, Space, Spin, Table, Tag, message } from 'antd';

import { ordersApi, shipmentsApi } from '../../api/endpoints';
import { errorMessage } from '@/api/client';
import { useAsync } from '@/hooks/useAsync';
import { CHANNEL_LABELS, ORDER_STATUS_LABELS } from '@/constants/labels';
import { SubstituteSkuAction } from './SubstituteSkuAction';
import { presentOrderContext, type OrderContextLine } from './orderContext';
import type { StockBlockerItem } from './stockBlockerCases';

export interface StockBlockerPanelProps {
  /** 复核事项带出的发货单 id；缺失时只读展示，不提供重新核对/换货入口。 */
  shipmentId: string | null;
  /** 复核事项带出的订单 id；缺失时不渲染订单事实区（不编造，只少显示）。 */
  orderId?: string | null;
  blockers: StockBlockerItem[];
  /** 阻塞清零或换货后回调，供发货台刷新计数/重新拉取事项列表。 */
  onResolved?: () => void;
}

/**
 * 京东库存/映射阻断的「就地处置」面板：逐条渲染商品名/编码/阻断原因/缺的字段，
 * 每条按 order_line_ids 提供「换货」入口，底部提供「重新核对」直接复用既有
 * RERUN_JD_STOCK_CHECK 维护动作（POST /api/v1/shipments/{id}/jd-stock-check）。
 *
 * <p>与 JdBlockerFixDrawer 里 JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED 那套「配置表单」
 * 完全不同的数据形状（item 级商品身份，没有 path/source/correction_target），
 * 所以拆成独立面板，由 JdBlockerFixDrawer 按 blocker 形状分流渲染。
 */
export function StockBlockerPanel({ shipmentId, orderId, blockers, onResolved }: StockBlockerPanelProps) {
  const [rechecking, setRechecking] = useState(false);
  const [recheckError, setRecheckError] = useState<unknown>(null);

  // 订单事实区：处置动作都在改真实订单，动手前先把「谁的哪一单、买了什么」摆出来。
  // 拉取失败只让事实区退成一条提示，不影响下面的阻断明细与处置动作。
  const order = useAsync(
    async () => (orderId ? await ordersApi.detail(orderId) : null),
    [orderId ?? ''],
  );
  const context = order.data ? presentOrderContext(order.data, blockers) : null;

  const recheck = useCallback(async () => {
    if (!shipmentId) return;
    setRechecking(true);
    setRecheckError(null);
    try {
      const result = await shipmentsApi.checkJdStock(shipmentId);
      if (result.stock_status === 'PASSED') {
        message.success('京东库存重新核对已通过，阻断已解除');
      } else {
        message.warning('已重新核对，仍有阻断，请看下方最新明细');
      }
      onResolved?.();
    } catch (error) {
      setRecheckError(error);
    } finally {
      setRechecking(false);
    }
  }, [shipmentId, onResolved]);

  return (
    <div>
      {orderId ? (
        <div style={{ marginBottom: 16 }}>
          {order.loading ? (
            <Spin size="small" />
          ) : order.error ? (
            // 事实区拉不到就诚实说明并给出可跳转的订单入口，不猜、不留空白。
            <Alert
              type="info"
              showIcon
              message="订单信息暂时读不到"
              description={
                <>
                  {errorMessage(order.error)}
                  <br />
                  <a href={`/orders/${orderId}`} target="_blank" rel="noreferrer">在订单页查看</a>
                </>
              }
            />
          ) : context ? (
            <>
              <Descriptions
                size="small"
                column={1}
                bordered
                items={[
                  {
                    key: 'source',
                    label: '订单来源',
                    children: (
                      <>
                        {context.sourceChannel
                          ? CHANNEL_LABELS[context.sourceChannel as keyof typeof CHANNEL_LABELS]
                            ?? context.sourceChannel
                          : '—'}
                        {context.sourceRef ? (
                          <span style={{ color: 'rgba(0,0,0,.55)' }}>　来源单号 {context.sourceRef}</span>
                        ) : null}
                      </>
                    ),
                  },
                  {
                    key: 'order',
                    label: '订单信息',
                    children: (
                      <>
                        <a href={`/orders/${orderId}`} target="_blank" rel="noreferrer">
                          {context.orderNo ?? `订单 ${orderId}`}
                        </a>
                        <span style={{ color: 'rgba(0,0,0,.55)' }}>
                          {'　'}
                          {context.orderStatus
                            ? ORDER_STATUS_LABELS[context.orderStatus as keyof typeof ORDER_STATUS_LABELS]
                              ?? context.orderStatus
                            : '—'}
                          {/* 平台下单时刻缺失时如实标注这是我方入库时间，不冒充成下单时间。 */}
                          {'　'}{context.orderedAtIsFallback ? '入库' : '下单'} {context.orderedAt}
                        </span>
                      </>
                    ),
                  },
                  {
                    key: 'receiver',
                    label: '收货人',
                    children: context.receiverName || context.receiverPhone ? (
                      <>
                        {context.receiverName ?? '（无姓名）'}
                        {context.receiverPhone ? `　${context.receiverPhone}` : ''}
                        {context.receiverAddress ? (
                          <div style={{ color: 'rgba(0,0,0,.65)' }}>{context.receiverAddress}</div>
                        ) : null}
                      </>
                    ) : (
                      '—'
                    ),
                  },
                ]}
              />
              <Table<OrderContextLine>
                style={{ marginTop: 12 }}
                size="small"
                rowKey="id"
                pagination={false}
                dataSource={context.lines}
                // 出问题的那几行直接标出来，省得人工拿商品名去比对。
                rowClassName={(line) => (line.blocked ? 'zs-row-blocked' : '')}
                columns={[
                  {
                    title: '商品（平台原名）',
                    dataIndex: 'productName',
                    render: (_: unknown, line: OrderContextLine) => (
                      <>
                        {line.productName}
                        {line.blocked ? <Tag color="warning" style={{ marginLeft: 6 }}>本次阻断</Tag> : null}
                        {line.skuCode ? (
                          <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12 }}>{line.skuCode}</div>
                        ) : null}
                      </>
                    ),
                  },
                  { title: '规格', dataIndex: 'specification', width: 110, render: (v: string | null) => v ?? '—' },
                  {
                    title: '数量',
                    dataIndex: 'quantity',
                    width: 90,
                    align: 'right' as const,
                    render: (_: unknown, line: OrderContextLine) => `${line.quantity}${line.unit ?? ''}`,
                  },
                ]}
              />
            </>
          ) : null}
        </div>
      ) : null}
      <Alert
        type="warning"
        showIcon
        message={`当前 ${blockers.length} 项库存/映射阻断，逐条处置`}
        description="缺字段的可去主数据页补配置后点“重新核对”；缺货或映射迟迟配不齐的可直接换货。"
      />
      {recheckError ? (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 12 }}
          message="重新核对失败"
          description={errorMessage(recheckError)}
        />
      ) : null}
      <List
        style={{ marginTop: 16 }}
        itemLayout="vertical"
        dataSource={blockers}
        renderItem={(blocker, index) => (
          <List.Item key={`${blocker.code}-${blocker.skuId ?? index}-${index}`}>
            <div style={{ fontWeight: 600 }}>
              {blocker.productName ?? '（无商品名）'}
              {blocker.goodsNo ? (
                <span style={{ color: 'rgba(0,0,0,.45)', fontWeight: 400 }}>
                  {' '}（京东编码 {blocker.goodsNo}）
                </span>
              ) : null}
              {blocker.skuCode ? <Tag style={{ marginLeft: 8 }}>{blocker.skuCode}</Tag> : null}
            </div>
            <div style={{ margin: '4px 0' }}>{blocker.message}</div>
            {blocker.missingField ? (
              <div style={{ color: 'rgba(0,0,0,.55)' }}>缺字段：{blocker.missingField}</div>
            ) : null}
            {blocker.orderLineIds.length > 0 && shipmentId ? (
              blocker.orderLineIds.map((orderLineId) => {
                // 数量只用于确认框里说清「整行全换」换的是多少；拿不到就不显示，不猜。
                const line = context?.lines.find((item) => item.id === orderLineId);
                return (
                  <SubstituteSkuAction
                    key={orderLineId}
                    orderLineId={orderLineId}
                    shipmentId={shipmentId}
                    quantityLabel={line ? `${line.quantity}${line.unit ?? ''}` : null}
                    onSubstituted={recheck}
                  />
                );
              })
            ) : (
              <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12 }}>
                该阻断未定位到具体订单行，暂不能直接换货，请到发货单页人工核对。
              </div>
            )}
          </List.Item>
        )}
      />
      <Space style={{ marginTop: 16 }}>
        <Button onClick={recheck} loading={rechecking} disabled={!shipmentId}>
          重新核对京东库存
        </Button>
      </Space>
    </div>
  );
}
