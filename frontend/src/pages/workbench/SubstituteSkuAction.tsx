import { useCallback, useState } from 'react';
import { Button, Popconfirm, Select, Space } from 'antd';

import { ordersApi, orderLinesApi, shipmentsApi, skusApi } from '../../api/endpoints';
import type { SkuRecord } from '../../api/types';
import { errorMessage } from '@/api/client';

export interface SubstituteSkuActionProps {
  /** 该阻断锁定的订单行——换货只改这一行的 sku_id，不动其它行。 */
  orderLineId: string;
  shipmentId: string;
  /** 换货并重新核对完成后回调（无论核对结果通过与否），供上层刷新列表/阻塞计数。 */
  onSubstituted?: () => void;
}

/**
 * 「换货」：把这条阻断的订单行改指到另一个 SKU，成功后自动重新核对京东库存
 * （POST /api/v1/order-lines/{id}/substitute-sku 之后接一次
 * POST /api/v1/shipments/{id}/jd-stock-check，两步都由本控件内部完成）。
 *
 * <p>候选 SKU 按当前发货批次的履约方过滤，只是不让候选列表里出现明显不对的选项——
 * 真正的同履约方/active/映射校验都在后端（OrderLineSkuSubstitutionService），
 * 这里的过滤只是 UX，不是安全边界。
 */
export function SubstituteSkuAction({ orderLineId, shipmentId, onSubstituted }: SubstituteSkuActionProps) {
  const [options, setOptions] = useState<SkuRecord[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const search = useCallback(async (query: string) => {
    setSearching(true);
    try {
      const shipment = await shipmentsApi.detail(shipmentId);
      const page = await skusApi.list({
        query: query || undefined,
        provider_id: shipment.provider_id ?? undefined,
        size: 20,
      });
      setOptions(page.items);
    } catch {
      setOptions([]);
    } finally {
      setSearching(false);
    }
  }, [shipmentId]);

  const confirm = useCallback(async () => {
    if (!selected) return;
    setSubmitting(true);
    setError(null);
    try {
      const shipment = await shipmentsApi.detail(shipmentId);
      // 乐观锁用刚读到的订单版本；冲突时后端报 ORDER_VERSION_CONFLICT，不静默覆盖别人的改动。
      const order = await ordersApi.detail(shipment.order_id);
      await orderLinesApi.substituteSku(orderLineId, {
        new_sku_id: selected,
        expected_order_version: order.version,
      });
      // 换货只改分配，不代表一定有货——立刻重新核对，让阻断明细反映换货后的真实结果。
      await shipmentsApi.checkJdStock(shipmentId).catch(() => null);
      setSelected(null);
      onSubstituted?.();
    } catch (submitError) {
      setError(submitError);
    } finally {
      setSubmitting(false);
    }
  }, [selected, orderLineId, shipmentId, onSubstituted]);

  return (
    <Space direction="vertical" size={4} style={{ marginTop: 8 }}>
      <Space>
        <Select
          showSearch
          allowClear
          style={{ width: 300 }}
          placeholder="搜索可替代的 SKU（商品名 / 编码）"
          filterOption={false}
          loading={searching}
          value={selected}
          onSearch={search}
          onFocus={() => {
            if (options.length === 0) void search('');
          }}
          onChange={(value: string | null) => setSelected(value ?? null)}
          options={options.map((sku) => ({
            value: sku.id,
            label: `${sku.name}（${sku.code}）`,
          }))}
        />
        <Popconfirm
          title="将此商品替换为所选 SKU 并重新核对京东库存"
          okText="确认换货"
          cancelText="取消"
          disabled={!selected}
          onConfirm={confirm}
        >
          <Button size="small" type="primary" loading={submitting} disabled={!selected}>
            换货
          </Button>
        </Popconfirm>
      </Space>
      {error ? <span style={{ color: '#cf1322', fontSize: 12 }}>{errorMessage(error)}</span> : null}
    </Space>
  );
}
