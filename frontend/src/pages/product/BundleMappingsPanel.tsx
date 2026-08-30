/**
 * 主数据 · 来源礼包映射（GET/POST /api/v1/source-bundle-mappings，
 * PATCH /api/v1/source-bundle-mappings/{id}）。
 *
 * 在此之前这张表只能绕过界面直接写库，运营配一条礼包映射得找人改数据库。
 * 这里补上列表 / 新建 / 编辑 / 停用，与「SKU 映射」页签并列。
 */

import { useCallback, useMemo, useState } from 'react';
import { Select, Space, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import MasterDataCrud, { type CrudField } from '@/pages/shared/MasterDataCrud';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { sourceBundleMappingsApi } from '@/api/endpoints';
import { CHANNEL_LABELS } from '@/constants/labels';
import type { MasterDataRecord } from '@/api/types';
import { SOURCE_MAPPING_CHANNELS, type SourceMappingMatrixChannel } from './skuMappingMatrix';
import {
  bundleMappingCreateBody,
  bundleMappingPresentation,
  bundleMappingUpdateBody,
} from './bundleMappings';
import { useBundleDirectory } from './masterOptions';

const channelOptions = SOURCE_MAPPING_CHANNELS.map((channel) => ({
  value: channel,
  label: CHANNEL_LABELS[channel],
}));

export default function BundleMappingsPanel() {
  const [channel, setChannel] = useState<SourceMappingMatrixChannel | undefined>();
  const { options: bundleOptions, labelById } = useBundleDirectory();

  const fetchPage = useCallback(
    (query: { page: number; size: number }) =>
      sourceBundleMappingsApi.list({ ...query, source_channel: channel }),
    [channel],
  );

  const columns = useMemo<ColumnsType<MasterDataRecord>>(() => [
    {
      title: '来源渠道',
      key: 'source_channel',
      width: 110,
      render: (_, record) => {
        const { sourceChannel } = bundleMappingPresentation(record);
        return sourceChannel ? CHANNEL_LABELS[sourceChannel] : '—';
      },
    },
    {
      title: '来源礼包',
      key: 'source_bundle',
      width: 260,
      render: (_, record) => {
        const { sourceBundleRef, sourceBundleName } = bundleMappingPresentation(record);
        return <ProductIdentity name={sourceBundleName} code={sourceBundleRef} />;
      },
    },
    {
      title: '目标礼包',
      key: 'bundle',
      width: 260,
      render: (_, record) => {
        const { bundleId } = bundleMappingPresentation(record);
        if (!bundleId) return '—';
        // 礼包清单还没取回、或映射指向清单外的礼包时，照实显示 id，不假装认得。
        return labelById[bundleId] ?? `礼包 #${bundleId}`;
      },
    },
    { title: '版本', dataIndex: 'version', width: 70, align: 'right' },
  ], [labelById]);

  const createFields: CrudField[] = [
    { name: 'source_channel', label: '来源渠道', required: true, type: 'select', options: channelOptions },
    {
      name: 'source_bundle_ref',
      label: '来源礼包编号',
      required: true,
      placeholder: '渠道侧的礼包编号；大者没有编号，填商品名称',
    },
    { name: 'source_bundle_name', label: '来源礼包名称', placeholder: '留空则沿用目标礼包名称' },
    { name: 'bundle_id', label: '目标礼包', required: true, type: 'select', options: bundleOptions },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  const updateFields: CrudField[] = [
    // 来源渠道与来源礼包编号是这条映射的身份，改了就是另一条映射，只能新建，不给改。
    {
      name: 'source_bundle_name',
      label: '来源礼包名称',
      // 后端投影在没存自定义名称时回落到礼包名，响应里区分不出来，编辑时原样带出。
      loadValue: (record) => record.name,
    },
    { name: 'bundle_id', label: '目标礼包', required: true, type: 'select', options: bundleOptions },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  return (
    <>
      <MasterDataCrud
        filters={(
          <Space wrap>
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>来源渠道</Typography.Text>
            <Select
              aria-label="来源渠道"
              style={{ width: 200 }}
              placeholder="全部渠道"
              allowClear
              value={channel}
              onChange={(value) => setChannel(value as SourceMappingMatrixChannel | undefined)}
              options={channelOptions}
            />
          </Space>
        )}
        extraQuery={{ source_channel: channel }}
        fetchPage={fetchPage}
        create={(values) => sourceBundleMappingsApi.create(bundleMappingCreateBody(values))}
        update={(id, values) => sourceBundleMappingsApi.update(id, bundleMappingUpdateBody(values))}
        columns={columns}
        createFields={createFields}
        updateFields={updateFields}
        tableScrollX={900}
      />
      <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
        一个来源礼包单位恒等于一份目标礼包，暂不支持包装乘数。停用只影响后续订单，
        已经落库的订单快照不会跟着变。
      </Typography.Text>
    </>
  );
}
