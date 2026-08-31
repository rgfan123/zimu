/**
 * 上传平台 · 中汇渠道平台（pms_openapi.md）：从商品档案批量上传商品到中汇 PMS。
 *
 * 流程：
 * 1. 登录：获取图片验证码 → 人工输入 AuthCode 完成登录（token 只在服务端内存）；
 * 2. 选择商品档案 SKU（支持 SKU 编码/商品名搜索 + 履约方筛选，最多 200 条）；
 * 3. 可覆盖品牌/资质/税率/库存/产地/物流等字段（覆盖优先于配置默认值）；
 * 4. 批量上传（批次先落库），展示逐商品结果（goodsId/审核状态/warning）与批次号。
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  App as AntApp,
  Alert,
  Button,
  Checkbox,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { skusApi, zhonghuiPmsApi } from '@/api/endpoints';
import { attr } from '@/pages/shared/MasterDataCrud';
import { AdminEmpty, AdminStatusTag } from '@/pages/shared/AdminVisualComponents';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { useProviderOptions } from '@/pages/product/masterOptions';
import { buildPmsBatchUploadOverrides, pmsUploadSummary } from '@/pages/product/zhonghuiPmsUpload';
import '@/pages/shared/adminSurface.css';
import type {
  SkuRecord,
  ZhonghuiPmsBatchUploadItem,
  ZhonghuiPmsBatchUploadResult,
  ZhonghuiPmsCaptcha,
  ZhonghuiPmsOptions,
  ZhonghuiPmsStatus,
} from '@/api/types';

interface OverrideForm {
  brand_id?: number;
  certification_type?: number;
  certification_id?: number;
  third_id?: number;
  limit_area_temp_id?: number;
  goods_tax?: number;
  logistics_carrier?: string[];
  producing_area?: string;
  goods_num?: number;
  sale_unit?: string;
  origincountry?: number;
  goods_price?: number;
  supply_price?: number;
}

const SKU_PAGE_SIZE = 200;

export default function ZhonghuiChannelPage() {
  const { message: messageApi } = AntApp.useApp();
  const providerOptions = useProviderOptions();
  const [searchParams] = useSearchParams();
  // 从商品档案页「上架」跳转时可携带筛选条件（query / provider_id）。
  const [searchInput, setSearchInput] = useState(searchParams.get('query') ?? '');
  const [searchQuery, setSearchQuery] = useState<string | undefined>(
    searchParams.get('query')?.trim() || undefined,
  );
  const [providerId, setProviderId] = useState<string | undefined>(
    searchParams.get('provider_id')?.trim() || undefined,
  );
  const [status, setStatus] = useState<ZhonghuiPmsStatus | null>(null);
  const [captcha, setCaptcha] = useState<ZhonghuiPmsCaptcha | null>(null);
  const [authCode, setAuthCode] = useState('');
  const [loggingIn, setLoggingIn] = useState(false);
  const [skus, setSkus] = useState<SkuRecord[]>([]);
  const [skusLoading, setSkusLoading] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [options, setOptions] = useState<ZhonghuiPmsOptions | null>(null);
  const [form] = Form.useForm<OverrideForm>();
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ZhonghuiPmsBatchUploadResult | null>(null);

  const authenticated = status?.authenticated ?? false;

  const loadStatus = useCallback(async () => {
    try {
      const value = await zhonghuiPmsApi.status();
      setStatus(value);
      if (value.authenticated) {
        setOptions(null);
        void loadOptions();
      }
    } catch (error) {
      messageApi.error(errorMessage(error));
    }
  }, [messageApi]);

  /** 品牌/资质/物流候选需在登录后查询（REAL 模式未登录时 PMS 返回空）。 */
  const loadOptions = useCallback(async () => {
    try {
      setOptions(await zhonghuiPmsApi.options());
    } catch (error) {
      messageApi.error(errorMessage(error));
    }
  }, [messageApi]);

  const loadCaptcha = async () => {
    try {
      setCaptcha(await zhonghuiPmsApi.captcha());
      setAuthCode('');
    } catch (error) {
      messageApi.error(errorMessage(error));
    }
  };

  const loadSkus = useCallback(async () => {
    setSkusLoading(true);
    try {
      const page = await skusApi.list({ page: 0, size: SKU_PAGE_SIZE, query: searchQuery, provider_id: providerId });
      setSkus(page.items);
      // 默认不勾选任何 SKU，避免整页误传；由用户按需勾选或「全选」。
    } catch (error) {
      messageApi.error(errorMessage(error));
    } finally {
      setSkusLoading(false);
    }
  }, [searchQuery, providerId, messageApi]);

  useEffect(() => {
    setResult(null);
    setCaptcha(null);
    setAuthCode('');
    setOptions(null);
    form.resetFields();
    void loadStatus();
    void loadSkus();
  }, [loadStatus, loadSkus, form]);

  const login = async () => {
    if (!captcha) {
      messageApi.warning('请先获取验证码');
      return;
    }
    if (!authCode.trim()) {
      messageApi.warning('请输入图片验证码');
      return;
    }
    setLoggingIn(true);
    try {
      const value = await zhonghuiPmsApi.login(authCode.trim(), captcha.captcha_no);
      if (value.success) {
        messageApi.success('中汇 PMS 登录成功');
        setCaptcha(null);
        setAuthCode('');
        setOptions(null);
        await loadStatus();
        void loadOptions();
      } else {
        messageApi.error(value.message || '登录失败，请核对验证码后重试');
        await loadCaptcha();
      }
    } catch (error) {
      messageApi.error(errorMessage(error));
    } finally {
      setLoggingIn(false);
    }
  };

  const checkedIds = useMemo(
    () => skus.filter((item) => selected.has(item.id)).map((item) => item.id),
    [skus, selected],
  );

  const upload = async () => {
    if (checkedIds.length === 0) {
      messageApi.warning('请至少勾选一个商品档案 SKU');
      return;
    }
    setUploading(true);
    setResult(null);
    try {
      const overrides = buildPmsBatchUploadOverrides(form.getFieldsValue());
      const value = await zhonghuiPmsApi.batchUpload({ sku_ids: checkedIds, overrides });
      setResult(value);
      if (value.failed === 0) {
        messageApi.success(`已上传 ${value.succeeded} 个商品（批次 ${value.batch_no}）`);
      } else {
        messageApi.warning(`上传完成：成功 ${value.succeeded}，失败 ${value.failed}（批次 ${value.batch_no}）`);
      }
    } catch (error) {
      messageApi.error(errorMessage(error));
    } finally {
      setUploading(false);
    }
  };

  const resultColumns: ColumnsType<ZhonghuiPmsBatchUploadItem> = [
    { title: '商品', key: 'goods', render: (_, item) => item.goods_name },
    { title: 'SKU 编码', dataIndex: 'sku_code', width: 150 },
    {
      title: '结果',
      dataIndex: 'success',
      width: 80,
      render: (success: boolean) =>
        success ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>,
    },
    {
      title: 'PMS 商品',
      key: 'goods_id',
      width: 160,
      render: (_, item) =>
        item.success
          ? (item.goods_id ? `#${item.goods_id}` : '—') + (item.pms_status ? ` · ${item.pms_status}` : '')
          : '—',
    },
    {
      title: '说明',
      key: 'message',
      render: (_, item) => {
        const message = item.success ? item.business_code : `${item.business_code}：${item.message}`;
        return item.warning ? (
          <Space size={4} wrap>
            <span>{message}</span>
            <Tag color="warning">{item.warning}</Tag>
          </Space>
        ) : (
          message
        );
      },
    },
  ];

  return (
    <div className="admin-surface" style={{ margin: 16, padding: 16 }}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        中汇渠道平台 · 商品上架
      </Typography.Title>

      {status ? (
        <Descriptions size="small" column={3} style={{ marginBottom: 8 }}>
          <Descriptions.Item label="连接模式">
            <AdminStatusTag status={status.client_mode === 'REAL' ? 'ACTIVE' : 'INACTIVE'} />
            <Typography.Text style={{ marginLeft: 4 }}>{status.client_mode}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="凭据">
            {status.credentials_configured ? '已配置' : '未配置'}
          </Descriptions.Item>
          <Descriptions.Item label="登录态">
            {authenticated ? '已登录' : '未登录'}
          </Descriptions.Item>
        </Descriptions>
      ) : null}

      {status && status.client_mode === 'REAL' && !status.credentials_configured ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 8 }}
          message="REAL 模式未配置中汇 PMS 凭据（ZHONGHUI_PMS_BASE_URL / USERNAME / PASSWORD）"
        />
      ) : null}

      {!authenticated ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 8 }}
          message="上传前需要先登录中汇 PMS：获取图片验证码，人工识别后输入验证码完成登录。"
        />
      ) : null}

      {/* 登录区 */}
      {!authenticated ? (
        <Space wrap style={{ marginBottom: 4 }}>
          <Button icon={<ReloadOutlined />} onClick={() => void loadCaptcha()} disabled={loggingIn}>
            获取验证码
          </Button>
          {captcha ? (
            <img
              src={`data:image/png;base64,${captcha.img}`}
              alt="验证码"
              style={{ height: 36, border: '1px solid #d9d9d9', borderRadius: 4 }}
            />
          ) : null}
          <Input
            style={{ width: 140 }}
            placeholder="图片验证码"
            value={authCode}
            onChange={(e) => setAuthCode(e.target.value)}
            onPressEnter={() => void login()}
            disabled={loggingIn}
          />
          <Button type="primary" onClick={() => void login()} loading={loggingIn}>
            登录
          </Button>
        </Space>
      ) : (
        <Space style={{ marginBottom: 4 }}>
          <Typography.Text type="success">已登录中汇 PMS（会话保存在服务端内存）</Typography.Text>
          <Button
            size="small"
            onClick={() => {
              void zhonghuiPmsApi.logout().catch(() => {
                // 登出失败不阻塞重新登录流程
              });
              if (status) {
                setStatus({ ...status, authenticated: false });
              }
              void loadCaptcha();
            }}
          >
            重新登录
          </Button>
        </Space>
      )}

      <Divider style={{ margin: '12px 0' }} />

      {/* SKU 筛选与选择 */}
      <Space wrap style={{ width: '100%', marginBottom: 8 }}>
        <Input.Search
          style={{ width: 260 }}
          placeholder="搜索 SKU 编码 / 商品名称 / 历史别名"
          allowClear
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onSearch={(value) => setSearchQuery(value.trim() || undefined)}
        />
        {searchQuery ? (
          <Button size="small" onClick={() => { setSearchInput(''); setSearchQuery(undefined); }}>
            清除搜索
          </Button>
        ) : null}
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>履约方</Typography.Text>
        <Select
          style={{ width: 200 }}
          placeholder="全部履约方"
          allowClear
          value={providerId}
          onChange={setProviderId}
          options={providerOptions}
        />
        <Typography.Text strong>
          选择商品档案（共 {skus.length} 条，最多 {SKU_PAGE_SIZE} 条）
        </Typography.Text>
        <Button
          size="small"
          onClick={() =>
            setSelected(checkedIds.length === skus.length ? new Set() : new Set(skus.map((item) => item.id)))
          }
        >
          {checkedIds.length === skus.length ? '全不选' : '全选'}
        </Button>
        {skus.length === SKU_PAGE_SIZE ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            超出 {SKU_PAGE_SIZE} 条，请用搜索/履约方筛选缩小范围
          </Typography.Text>
        ) : null}
      </Space>

      <Table<SkuRecord>
        size="small"
        rowKey="id"
        loading={skusLoading}
        dataSource={skus}
        pagination={false}
        locale={{ emptyText: <AdminEmpty description="没有匹配的商品档案" /> }}
        scroll={{ y: 260 }}
        columns={[
          {
            title: '',
            key: 'check',
            width: 40,
            render: (_, record) => (
              <Checkbox
                checked={selected.has(record.id)}
                onChange={(e) => {
                  setSelected((prev) => {
                    const next = new Set(prev);
                    if (e.target.checked) next.add(record.id);
                    else next.delete(record.id);
                    return next;
                  });
                }}
              />
            ),
          },
          {
            title: '商品 / SKU',
            key: 'identity',
            render: (_, record) => <ProductIdentity name={record.name} code={record.code} />,
          },
          {
            title: '规格',
            key: 'spec',
            width: 120,
            render: (_, record) => String(attr(record, 'specification') ?? '—'),
          },
          {
            title: '单位',
            key: 'unit',
            width: 70,
            render: (_, record) => String(attr(record, 'unit') ?? '—'),
          },
          {
            title: '售价',
            key: 'price',
            width: 90,
            align: 'right',
            render: (_, record) => String(attr(record, 'retail_price') ?? '—'),
          },
        ]}
      />

      {/* 覆盖字段（优先于配置默认值） */}
      <Form form={form} layout="inline" style={{ rowGap: 8, marginTop: 12 }}>
        <Form.Item name="brand_id" label="品牌">
          <Select
            style={{ width: 130 }}
            allowClear
            placeholder="默认"
            options={(options?.brands ?? []).map((brand) => ({
              value: brand.brand_id,
              label: brand.brand_name,
            }))}
          />
        </Form.Item>
        <Form.Item name="certification_type" label="资质类型">
          <InputNumber style={{ width: 90 }} min={0} placeholder="默认" />
        </Form.Item>
        <Form.Item name="certification_id" label="资质">
          <Select
            style={{ width: 150 }}
            allowClear
            placeholder="默认"
            options={(options?.certifications ?? []).map((cert) => ({
              value: cert.certification_id,
              label: cert.certification_name,
            }))}
          />
        </Form.Item>
        <Form.Item name="third_id" label="第三方分类">
          <InputNumber style={{ width: 110 }} min={0} placeholder="默认" />
        </Form.Item>
        <Form.Item name="limit_area_temp_id" label="限售模板">
          <InputNumber style={{ width: 110 }} min={0} placeholder="默认" />
        </Form.Item>
        <Form.Item name="goods_tax" label="税率%">
          <InputNumber style={{ width: 80 }} min={0} max={100} placeholder="默认" />
        </Form.Item>
        <Form.Item name="logistics_carrier" label="物流">
          <Select
            style={{ width: 170 }}
            mode="multiple"
            allowClear
            placeholder="默认（可多选）"
            options={(options?.logistics ?? []).map((logistics) => ({
              value: logistics.logist_id,
              label: logistics.logist_name,
            }))}
          />
        </Form.Item>
        <Form.Item name="goods_num" label="库存">
          <InputNumber style={{ width: 80 }} min={0} placeholder="默认" />
        </Form.Item>
        <Form.Item name="sale_unit" label="单位">
          <Input style={{ width: 90 }} placeholder="默认" />
        </Form.Item>
        <Form.Item name="producing_area" label="产地">
          <Input style={{ width: 110 }} placeholder="默认" />
        </Form.Item>
        <Form.Item name="origincountry" label="原产国">
          <InputNumber style={{ width: 90 }} min={0} placeholder="默认" />
        </Form.Item>
        <Form.Item name="goods_price" label="售价(元)">
          <InputNumber style={{ width: 100 }} min={0} precision={2} placeholder="默认" />
        </Form.Item>
        <Form.Item name="supply_price" label="供货价(元)">
          <InputNumber style={{ width: 100 }} min={0} precision={2} placeholder="默认" />
        </Form.Item>
      </Form>

      <Space style={{ marginTop: 12 }}>
        <Button
          type="primary"
          icon={<UploadOutlined />}
          loading={uploading}
          disabled={!authenticated || checkedIds.length === 0}
          onClick={() => void upload()}
        >
          上传 {checkedIds.length > 0 ? `（${checkedIds.length} 个）` : ''}
        </Button>
        {result ? (
          <Typography.Text>
            {pmsUploadSummary(result.total, result.succeeded, result.failed)}
            <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
              批次 {result.batch_no}
            </Typography.Text>
          </Typography.Text>
        ) : null}
      </Space>

      {result ? (
        <Table<ZhonghuiPmsBatchUploadItem>
          size="small"
          rowKey="sku_id"
          style={{ marginTop: 12 }}
          dataSource={result.items}
          pagination={false}
          columns={resultColumns}
          scroll={{ y: 220 }}
        />
      ) : null}
    </div>
  );
}
