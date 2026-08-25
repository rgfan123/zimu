import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Select, Space } from 'antd';

import { providersApi, shipmentsApi } from '../../api/endpoints';
import type { FulfillmentProvider, ShipmentJdOutboundPreview } from '../../api/types';
import { errorMessage } from '@/api/client';
import { groupBlockers, type BlockerItem, type BlockerGroupView } from './blockerGrouping';

/**
 * 京东建单阻塞项的**就地处置**抽屉（发货台一页闭环 2/n）。
 *
 * <p>此前的动线是「发货台看不到原因 → 跳发货单页看到一行长文本 → 跳系统管理改配置 →
 * 自己走回来 → 自己确认改对没有」。本抽屉把这一串压缩成一次操作：
 * 补齐 → 原地重跑预检 → 阻塞当场减少 → 清零后就地建单。
 *
 * <p>为什么这个文件可以用 AntD 展示组件：ADR 0011 的门禁按**文件**守
 * （`antdBoundary.test.ts` 的 MIGRATED 清单），约束的是页面骨架文件。
 * 抽屉是独立的交互面，`Form`/`Select`/`Input`/`Button`/`Drawer` 本就是 ADR §2
 * 明确保留继续使用的交互控件，且继承 saasTheme token，与手写区块视觉一致。
 *
 * <p>表单字段**由阻塞项驱动**，不是写死的键表——因此天然覆盖到
 * `FulfillmentProvidersPage.JD_CONFIG_KEYS` 漏掉的 `customerCode`
 * （后端 `FulfillmentProviderJdConfig.KNOWN_KEYS` 有 11 键，那个页面只列了 9 个，
 * 其中 customerCode 恰好是真实阻塞之一，在原页面上根本改不了）。
 */

/** 键名 → 中文标签。与 FulfillmentProvidersPage 同源同形，额外补上它漏掉的两个键。 */
const KEY_LABELS: Record<string, string> = {
  sourceNo: '来源编码 sourceNo',
  warehouseNo: '仓库编码 warehouseNo',
  pin: '京东 pin',
  erpShopNo: 'ERP 店铺编码 erpShopNo',
  salesPlatformSource: '销售平台来源 salesPlatformSource',
  ownerNo: '货主编码 ownerNo',
  shopNo: '店铺编码 shopNo',
  carrierNo: '承运商编码 carrierNo',
  townRequired: '乡镇必填 townRequired',
  outboundMode: '建单路由 outboundMode',
  customerCode: '青龙业主号 customerCode',
};

/** 后端 `FulfillmentProviderJdConfig` 里唯一的布尔键；其余键一律非空字符串。 */
const BOOLEAN_KEYS = new Set(['townRequired']);
/** 敏感键：输入用密码框，且永不回显既有值（后端投影本就只给 present）。 */
const SECRET_KEYS = new Set(['pin']);

export interface JdBlockerFixDrawerProps {
  open: boolean;
  /** 复核事项带出的发货单 id；缺失时抽屉只读展示，不提供补齐入口。 */
  shipmentId: string | null;
  blockers: BlockerItem[];
  onClose: () => void;
  /** 阻塞清零时回调，供发货台刷新计数。 */
  onResolved?: () => void;
}

export function JdBlockerFixDrawer({
  open, shipmentId, blockers, onClose, onResolved,
}: JdBlockerFixDrawerProps) {
  const [form] = Form.useForm();
  const [provider, setProvider] = useState<FulfillmentProvider | null>(null);
  const [preview, setPreview] = useState<ShipmentJdOutboundPreview | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  /** 当前展示的阻塞：重跑预检后以预检结果为准，否则用事项里落库的那份。 */
  const current: BlockerItem[] = useMemo(() => {
    if (!preview) return blockers;
    return groupBlockers(
      preview.blockers.map((b) => ({
        code: b.code, path: b.path, source: b.source,
        correctionTarget: b.correction_target, message: b.message,
      })),
    ).flatMap((group) => group.items);
  }, [preview, blockers]);

  const groups: BlockerGroupView[] = useMemo(() => groupBlockers(current), [current]);
  const configKeys = useMemo(
    () => groups.filter((g) => g.table === 'fulfillment_providers').flatMap((g) => g.keys),
    [groups],
  );
  const cleared = current.length === 0;

  // 打开时解析发货单 → 履约方；阻塞项自身不带履约方身份，只能这样拿。
  useEffect(() => {
    if (!open || !shipmentId) return;
    let cancelled = false;
    setLoadError(null);
    (async () => {
      try {
        const shipment = await shipmentsApi.detail(shipmentId);
        if (!shipment.provider_id) throw new Error('该发货批次未绑定履约方，无法定位配置');
        const found = (await providersApi.list()).find((p) => p.id === shipment.provider_id);
        if (!found) throw new Error(`未找到履约方 ${shipment.provider_id}`);
        if (!cancelled) setProvider(found);
      } catch (error) {
        if (!cancelled) setLoadError(error);
      }
    })();
    return () => { cancelled = true; };
  }, [open, shipmentId]);

  const rerunPreview = useCallback(async () => {
    if (!shipmentId) return;
    const next = await shipmentsApi.previewJdOutbound(shipmentId);
    setPreview(next);
    if (next.blockers.length === 0) onResolved?.();
  }, [shipmentId, onResolved]);

  const save = useCallback(async () => {
    if (!provider) return;
    setBusy(true);
    setSaveError(null);
    try {
      const values = await form.validateFields();
      const config: Record<string, string | boolean> = {};
      for (const [key, value] of Object.entries(values)) {
        if (value === undefined || value === null || value === '') continue;
        config[key] = BOOLEAN_KEYS.has(key) ? value === true || value === 'true' : String(value);
      }
      if (Object.keys(config).length === 0) throw new Error('没有要保存的项');
      // 乐观锁用刚读到的 version；冲突时后端报 VERSION_CONFLICT，不静默覆盖别人的改动。
      const updated = await providersApi.update(provider.id, {
        expected_version: provider.version,
        config,
      });
      setProvider(updated);
      form.resetFields();
      // 保存成功即原地重跑预检——这是「不用自己走回来确认」的关键一步
      await rerunPreview();
    } catch (error) {
      setSaveError(error);
    } finally {
      setBusy(false);
    }
  }, [provider, form, rerunPreview]);

  return (
    <Drawer
      title="就地处置京东建单阻塞"
      width={560}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {loadError ? (
        <Alert type="error" showIcon message="无法定位履约方配置" description={errorMessage(loadError)} />
      ) : null}

      {cleared ? (
        <Alert
          type="success"
          showIcon
          message="阻塞已全部解除"
          description={
            preview?.erp_delivery_no
              ? `出库单号 ${preview.erp_delivery_no}，可在发货单页提交建单。`
              : '可在发货单页提交建单。'
          }
        />
      ) : (
        <>
          <Alert
            type="warning"
            showIcon
            message={`当前 ${current.length} 项阻塞，按修复位置分组`}
            description={
              <ul style={{ margin: '6px 0 0', paddingInlineStart: 18 }}>
                {groups.map((group) => (
                  <li key={group.targetKey}>
                    <strong>{group.label}</strong>（{group.items.length} 项）
                    {group.keys.length > 0 ? `：${group.keys.join('、')}` : null}
                    {group.table === null ? '　— 该组来源无法定位，请人工核对' : null}
                  </li>
                ))}
              </ul>
            }
          />

          {configKeys.length > 0 && provider ? (
            <Form form={form} layout="vertical" style={{ marginTop: 16 }} disabled={busy}>
              <p style={{ margin: '0 0 12px', color: 'rgba(0,0,0,.55)' }}>
                只列出缺失项（履约方 {provider.provider_name}）。保存后自动重跑预检。
              </p>
              {configKeys.map((key) => (
                <Form.Item
                  key={key}
                  name={key}
                  label={KEY_LABELS[key] ?? key}
                  rules={[{ required: true, message: `请填写 ${KEY_LABELS[key] ?? key}` }]}
                >
                  {BOOLEAN_KEYS.has(key) ? (
                    <Select
                      options={[
                        { value: true, label: '是（京东要求乡镇）' },
                        { value: false, label: '否' },
                      ]}
                      placeholder="系统不猜测京东要求，请显式选择"
                    />
                  ) : SECRET_KEYS.has(key) ? (
                    <Input.Password autoComplete="off" placeholder="敏感值，保存后不回显" />
                  ) : (
                    <Input autoComplete="off" />
                  )}
                </Form.Item>
              ))}
              {saveError ? (
                <Alert
                  type="error"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="保存失败"
                  description={errorMessage(saveError)}
                />
              ) : null}
              <Space>
                <Button type="primary" loading={busy} onClick={save}>
                  保存并重跑预检
                </Button>
                <Button onClick={onClose} disabled={busy}>稍后处理</Button>
              </Space>
            </Form>
          ) : null}

          {configKeys.length === 0 ? (
            <p style={{ marginTop: 16, color: 'rgba(0,0,0,.55)' }}>
              本批阻塞不在履约方配置层，需到对应位置处理后重跑预检。
            </p>
          ) : null}
        </>
      )}
    </Drawer>
  );
}
