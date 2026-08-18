/**
 * 「模拟下单」演示页 —— AI 提取草稿与固定场景最终都只进入 /demo/v1：
 *   1. GET  /demo/v1/scenarios        列出固定演示场景
 *   2. POST /demo/v1/scenarios        创建 DemoRun（Mock Adapter 同步跑完 Timeline）
 *   3. GET  /demo/v1/runs/{run_id}    查询运行状态（RUNNING 时轮询至终态）
 * Timeline 随 DemoRun 由 /demo/v1 专用接口提供；不跨域读取 /api/v1。
 * 严禁复用 POST /internal/v1/orders；演示数据仅存在于 DEMO 数据域，
 * 不进入业务订单列表 / 分析 / Metabase。
 */

import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, Empty, Row, Skeleton, Space, Tabs, Tag, Typography } from 'antd';
import { PlayCircleOutlined, RocketOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { demoApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type { DemoRun, DemoScenario } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import StatusTag from '@/components/StatusTag';
import OrderTimeline from '@/components/OrderTimeline';
import AiOrderAssistantPanel from './AiOrderAssistantPanel';

interface RunRecord {
  runNo: string;
  scenarioName: string;
  status: DemoRun['status'];
  finishedAt?: string;
}

export default function DemoOrderPage() {
  const scenariosQuery = useAsync(() => demoApi.scenarios(), []);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [run, setRun] = useState<DemoRun | null>(null);
  const [runLoading, setRunLoading] = useState(false);
  const [runError, setRunError] = useState<Error | null>(null);
  const [history, setHistory] = useState<RunRecord[]>([]);

  // RUNNING 时轮询运行详情，直到终态
  useEffect(() => {
    if (!run || run.status !== 'RUNNING') return;
    const timer = setInterval(() => {
      demoApi
        .runDetail(run.id)
        .then((latest) => setRun(latest))
        .catch(() => {
          /* 轮询失败继续重试，不做处理 */
        });
    }, 2000);
    return () => clearInterval(timer);
  }, [run]);

  const selectedScenario: DemoScenario | undefined = useMemo(
    () => scenariosQuery.data?.find((s) => s.scenario_code === selectedCode),
    [scenariosQuery.data, selectedCode],
  );

  const handleRun = async () => {
    if (!selectedCode) return;
    setRunLoading(true);
    setRunError(null);
    try {
      const created = await demoApi.run(selectedCode);
      setRun(created);
      setHistory((h) => [
        {
          runNo: created.run_no,
          scenarioName: selectedScenario?.scenario_name ?? created.scenario_code,
          status: created.status,
          finishedAt: created.finished_at,
        },
        ...h,
      ]);
    } catch (e) {
      setRunError(e as Error);
    } finally {
      setRunLoading(false);
    }
  };

  const handleAiRun = (created: DemoRun) => {
    setRunError(null);
    setRun(created);
    setHistory((current) => [
      {
        runNo: created.run_no,
        scenarioName: 'AI 提取订单',
        status: created.status,
        finishedAt: created.finished_at,
      },
      ...current,
    ]);
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="演示环境说明"
        description="本页用于验证从接单到来源回传的完整履约流程。演示订单与正式业务数据严格隔离，不会进入业务订单、经营分析或管理报表。"
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={10}>
          <Tabs
            defaultActiveKey="ai"
            items={[
              {
                key: 'ai',
                label: 'AI 提取订单',
                children: (
                  <Card size="small" style={{ borderRadius: 10 }}>
                    <AiOrderAssistantPanel onRunCreated={handleAiRun} />
                  </Card>
                ),
              },
              {
                key: 'fixed',
                label: '固定演示场景',
                children: (
                  <Card size="small" title="选择演示场景" style={{ borderRadius: 10 }}>
                    {scenariosQuery.loading ? (
                      <Skeleton active paragraph={{ rows: 3 }} />
                    ) : scenariosQuery.error ? (
                      <Alert type="error" showIcon message="场景列表加载失败" description={errorMessage(scenariosQuery.error)} />
                    ) : !scenariosQuery.data?.length ? (
                      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可用演示场景" />
                    ) : (
                      <Space direction="vertical" size={10} style={{ width: '100%' }}>
                        {scenariosQuery.data.map((scenario) => (
                          <div
                            key={scenario.scenario_code}
                            onClick={() => setSelectedCode(scenario.scenario_code)}
                            style={{
                              border: `1px solid ${selectedCode === scenario.scenario_code ? '#2563eb' : '#e4e8ee'}`,
                              borderRadius: 8,
                              padding: '10px 14px',
                              cursor: 'pointer',
                              background: selectedCode === scenario.scenario_code ? '#eff6ff' : '#fff',
                              transition: 'all .15s',
                            }}
                          >
                            <Space align="center">
                              <RocketOutlined style={{ color: selectedCode === scenario.scenario_code ? '#2563eb' : '#7a8699' }} />
                              <div>
                                <div style={{ fontWeight: 600, color: '#1c2230' }}>{scenario.scenario_name}</div>
                                <div style={{ fontSize: 12, color: '#7a8699', marginTop: 2 }}>{scenario.description}</div>
                              </div>
                            </Space>
                          </div>
                        ))}
                        <Button
                          type="primary"
                          block
                          icon={<PlayCircleOutlined />}
                          loading={runLoading}
                          disabled={!selectedCode}
                          onClick={handleRun}
                        >
                          {runLoading ? '模拟运行中…' : '开始模拟下单'}
                        </Button>
                      </Space>
                    )}
                  </Card>
                ),
              },
            ]}
          />

          <Card
            size="small"
            title="本次会话运行记录"
            style={{ marginTop: 16, borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
          >
            {history.length ? (
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                {history.map((h, i) => (
                  <div key={i} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, fontSize: 13 }}>
                    <Typography.Text code>{h.runNo}</Typography.Text>
                    <Typography.Text ellipsis style={{ flex: 1 }}>
                      {h.scenarioName}
                    </Typography.Text>
                    <Tag color={h.status === 'SUCCEEDED' ? 'success' : h.status === 'FAILED' ? 'error' : 'processing'} style={{ borderRadius: 6 }}>
                      {h.status === 'SUCCEEDED' ? '成功' : h.status === 'FAILED' ? '失败' : '运行中'}
                    </Tag>
                  </div>
                ))}
              </Space>
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                暂无运行记录
              </Typography.Text>
            )}
          </Card>
        </Col>

        <Col xs={24} xl={14}>
          <Card
            size="small"
            title="演示运行结果"
            style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
          >
            {runError ? <Alert type="error" showIcon message="模拟下单失败" description={errorMessage(runError)} /> : null}
            {!run ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="通过 AI 提取或固定场景创建演示订单" style={{ padding: '40px 0' }} />
            ) : (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Descriptions
                  size="small"
                  column={{ xs: 1, sm: 2, xl: 4 }}
                  items={[
                    { key: 'run_no', label: '运行编号', children: <Typography.Text code>{run.run_no}</Typography.Text> },
                    {
                      key: 'status',
                      label: '运行状态',
                      children: (
                        <Tag color={run.status === 'SUCCEEDED' ? 'success' : run.status === 'FAILED' ? 'error' : 'processing'} style={{ borderRadius: 6 }}>
                          {run.status === 'SUCCEEDED' ? '已完成' : run.status === 'FAILED' ? '失败' : '运行中'}
                        </Tag>
                      ),
                    },
                    { key: 'scope', label: '数据域', children: <Tag color="purple" style={{ borderRadius: 6 }}>DEMO</Tag> },
                    {
                      key: 'scenario',
                      label: '场景',
                      children: run.scenario_code === 'AI_EXTRACTED_ORDER'
                        ? 'AI 提取订单'
                        : selectedScenario?.scenario_name ?? run.scenario_code,
                    },
                    { key: 'started', label: '开始时间', children: dayjs(run.started_at).format('YYYY-MM-DD HH:mm:ss') },
                    {
                      key: 'finished',
                      label: '结束时间',
                      children: run.finished_at ? dayjs(run.finished_at).format('YYYY-MM-DD HH:mm:ss') : '—',
                    },
                    { key: 'order_no', label: '演示订单号', children: run.order?.order_no ?? '—' },
                    { key: 'channel', label: '来源渠道', children: run.order ? <StatusTag kind="channel" value={run.order.source_channel} /> : '—' },
                    {
                      key: 'order_status',
                      label: '订单状态',
                      children: run.order ? <StatusTag kind="orderStatus" value={run.order.order_status} /> : '—',
                    },
                    {
                      key: 'progress',
                      label: '行进度',
                      children: run.order ? `${run.order.completed_count}/${run.order.total_count}` : '—',
                    },
                  ]}
                />

                {run.status === 'FAILED' ? (
                  <Alert type="error" showIcon message="演示运行失败" description={run.error?.message ?? '未知错误'} />
                ) : null}

                <Card
                  size="small"
                  type="inner"
                  title="演示履约时间线"
                >
                  <OrderTimeline events={run.timeline ?? []} maxHeight={420} />
                </Card>
              </Space>
            )}
          </Card>
        </Col>
      </Row>
    </Space>
  );
}
