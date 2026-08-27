import { useCallback, useState } from 'react';
import { Alert, Button, List, Space, Tag, message } from 'antd';

import { shipmentsApi } from '../../api/endpoints';
import { errorMessage } from '@/api/client';
import { SubstituteSkuAction } from './SubstituteSkuAction';
import type { StockBlockerItem } from './stockBlockerCases';

export interface StockBlockerPanelProps {
  /** 复核事项带出的发货单 id；缺失时只读展示，不提供重新核对/换货入口。 */
  shipmentId: string | null;
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
export function StockBlockerPanel({ shipmentId, blockers, onResolved }: StockBlockerPanelProps) {
  const [rechecking, setRechecking] = useState(false);
  const [recheckError, setRecheckError] = useState<unknown>(null);

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
              blocker.orderLineIds.map((orderLineId) => (
                <SubstituteSkuAction
                  key={orderLineId}
                  orderLineId={orderLineId}
                  shipmentId={shipmentId}
                  onSubstituted={recheck}
                />
              ))
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
