import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Popconfirm, Select, Space, message } from 'antd';

import { jdWarehouseApi, providersApi, shipmentsApi } from '../../api/endpoints';
import type {
  FulfillmentProvider, JdClientStatus, JdReceiverAddressCandidate, Shipment,
  ShipmentJdOutboundPreview,
} from '../../api/types';
import { jdReceiverAddressDefaults } from '../fulfillment/jdReceiverAddress';
import {
  canSubmitJdOutbound, jdOutboundConfirmationDetail, jdOutboundConfirmationTitle,
  jdOutboundPresentation, jdOutboundRuntimeGate,
} from '../fulfillment/shipmentJdOutbound';
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
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [runtime, setRuntime] = useState<JdClientStatus | null>(null);
  const [preview, setPreview] = useState<ShipmentJdOutboundPreview | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [addressCandidate, setAddressCandidate] = useState<JdReceiverAddressCandidate | null>(null);
  const [addressForm] = Form.useForm();
  const [savingAddress, setSavingAddress] = useState(false);
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
  // 地址类阻塞的 source 指向 shipments 表（如 shipments.jd_receiver_province）
  const hasAddressBlocker = useMemo(
    () => groups.some((group) => group.table === 'shipments'),
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
        const loaded = await shipmentsApi.detail(shipmentId);
        if (!loaded.provider_id) throw new Error('该发货批次未绑定履约方，无法定位配置');
        const found = (await providersApi.list()).find((p) => p.id === loaded.provider_id);
        if (!found) throw new Error(`未找到履约方 ${loaded.provider_id}`);
        // 运行时状态是高危外部写的前置门禁：读不到就一律禁止建单（不是默认放行）
        const status = await jdWarehouseApi.status().catch(() => null);
        const candidates = await shipmentsApi
          .jdReceiverAddressCandidates({ only_missing: false })
          .catch(() => [] as JdReceiverAddressCandidate[]);
        const mine = candidates.find((row) => row.shipment_id === shipmentId) ?? null;
        if (!cancelled) {
          setShipment(loaded);
          setProvider(found);
          setRuntime(status);
          setAddressCandidate(mine);
          if (mine) addressForm.setFieldsValue(jdReceiverAddressDefaults(mine));
        }
      } catch (error) {
        if (!cancelled) setLoadError(error);
      }
    })();
    return () => { cancelled = true; };
  }, [open, shipmentId]);

  const rerunPreview = useCallback(async () => {
    if (!shipmentId) return;
    const [next, refreshed] = await Promise.all([
      shipmentsApi.previewJdOutbound(shipmentId),
      shipmentsApi.detail(shipmentId),
    ]);
    setPreview(next);
    setShipment(refreshed);
    if (next.blockers.length === 0) onResolved?.();
  }, [shipmentId, onResolved]);

  const saveAddress = useCallback(async () => {
    if (!addressCandidate) return;
    setSavingAddress(true);
    setSaveError(null);
    try {
      const values = await addressForm.validateFields();
      await shipmentsApi.confirmJdReceiverAddresses({
        items: [{
          shipment_id: addressCandidate.shipment_id,
          expected_version: addressCandidate.expected_version,
          province: values.province,
          city: values.city,
          county: values.county,
          town: values.town ?? null,
          detail_address: values.detail_address,
        }],
      });
      await rerunPreview();
    } catch (error) {
      setSaveError(error);
    } finally {
      setSavingAddress(false);
    }
  }, [addressCandidate, addressForm, rerunPreview]);

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

  // 建单门禁完整复用发货单页的纯函数，避免两处各判一套后悄悄漂移。
  const presentation = jdOutboundPresentation(shipment?.jd_outbound);
  const runtimeGate = jdOutboundRuntimeGate(runtime);
  const confirmation = jdOutboundConfirmationDetail(preview, shipment?.jd_outbound?.erp_delivery_no);
  const confirmTitle = jdOutboundConfirmationTitle(
    runtimeGate.mode, runtimeGate.confirmation, confirmation.erpDeliveryNo,
  );
  const canSubmit = canSubmitJdOutbound({
    selectedShipmentId: shipmentId ?? undefined,
    detailShipmentId: shipment?.id,
    previewShipmentId: preview?.shipment_id,
    isJdShipment: provider?.provider_type === 'JD_WAREHOUSE',
    presentationAllowsSubmit: presentation.canSubmit,
    detailLoading: false,
    detailError: Boolean(loadError),
    previewSubmittable: preview?.submittable === true,
    previewLoading: false,
    previewError: false,
    runtimeReady: runtimeGate.ready,
    runtimeLoading: false,
    runtimeError: runtime === null,
    submitting,
  });

  const submit = useCallback(async () => {
    if (!shipmentId || !canSubmit) return;
    setSubmitting(true);
    try {
      const result = await shipmentsApi.submitJdOutbound(shipmentId);
      message.success(`京东出库单 ${result.erp_delivery_no} 已提交`);
      await rerunPreview();
      onResolved?.();
    } catch (error) {
      setSaveError(error);
    } finally {
      setSubmitting(false);
    }
  }, [shipmentId, canSubmit, rerunPreview, onResolved]);

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
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert
            type="success"
            showIcon
            message="阻塞已全部解除"
            description={
              confirmation.erpDeliveryNo
                ? `商户出库号 ${confirmation.erpDeliveryNo}，可直接提交建单。`
                : '可直接提交建单。'
            }
          />
          {!runtimeGate.ready ? (
            <Alert
              type="error"
              showIcon
              message={runtimeGate.mode === 'REAL' ? '真实京东连接尚未就绪' : '京东运行环境尚未确认'}
              description={runtimeGate.mode === 'REAL'
                ? '凭据或租户配置未通过 readiness，系统已禁止建单。'
                : '运行状态读取失败或尚未完成，系统已默认禁止建单。'}
            />
          ) : null}
          {saveError ? (
            <Alert type="error" showIcon message="建单失败" description={errorMessage(saveError)} />
          ) : null}
          <Popconfirm
            title={confirmTitle}
            description={(
              <Space direction="vertical" size={6} style={{ maxWidth: 420 }}>
                <span>系统会再次执行 SKU 映射、数量换算和实时库存门禁。</span>
                {confirmation.erpDeliveryNo ? (
                  <span>商户出库号：<span style={{ fontVariantNumeric: 'tabular-nums' }}>{confirmation.erpDeliveryNo}</span></span>
                ) : null}
                {confirmation.cargos.length > 0 ? (
                  <div style={{ background: '#f7f8fa', borderRadius: 8, padding: '8px 10px' }}>
                    <div style={{ fontWeight: 600, marginBottom: 4 }}>本次将提交以下 SKU×数量：</div>
                    <ul style={{ margin: 0, paddingInlineStart: 16 }}>
                      {confirmation.cargos.map((cargo) => (
                        <li key={`${cargo.orderLine}-${cargo.goodsNo}`}>
                          {cargo.goodsName}
                          {cargo.goodsNo ? `（SKU ${cargo.goodsNo}）` : ''}
                          {' '}× {Number.isFinite(cargo.planQuantity) ? cargo.planQuantity.toLocaleString('zh-CN') : '—'} 件
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
              </Space>
            )}
            okText="确认提交"
            cancelText="取消"
            onConfirm={submit}
            disabled={!canSubmit}
          >
            <Button type="primary" loading={submitting} disabled={!canSubmit}>
              {presentation.actionLabel}
            </Button>
          </Popconfirm>
        </Space>
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

          {hasAddressBlocker && addressCandidate ? (
            <Form form={addressForm} layout="vertical" style={{ marginTop: 16 }} disabled={savingAddress}>
              <p style={{ margin: '0 0 4px', fontWeight: 600 }}>收货人结构化地址</p>
              <p style={{ margin: '0 0 12px', color: 'rgba(0,0,0,.55)' }}>
                原始地址：{addressCandidate.receiver_address_snapshot}
                <br />
                系统不从自由文本猜测行政区划，四级必须人工确认。
              </p>
              {[
                ['province', '省'], ['city', '市'], ['county', '区/县'],
                ['town', '乡镇（京东未要求时可留空）'], ['detail_address', '详细地址'],
              ].map(([name, label]) => (
                <Form.Item
                  key={name}
                  name={name}
                  label={label}
                  rules={name === 'town' ? [] : [{ required: true, message: `请填写${label}` }]}
                >
                  <Input autoComplete="off" />
                </Form.Item>
              ))}
              <Space>
                <Button type="primary" loading={savingAddress} onClick={saveAddress}>
                  确认地址并重跑预检
                </Button>
              </Space>
            </Form>
          ) : null}

          {configKeys.length === 0 && !hasAddressBlocker ? (
            <p style={{ marginTop: 16, color: 'rgba(0,0,0,.55)' }}>
              本批阻塞不在履约方配置或收货地址层，需到对应位置处理后重跑预检。
            </p>
          ) : null}
        </>
      )}
    </Drawer>
  );
}
