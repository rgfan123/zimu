/**
 * 工作台：KPI 数据卡（订单数 / 已发订单 / 待人工介入 + 待介入原因卡）、
 * 近 7 日订单与发货趋势（ECharts 平滑面积图）、待人工介入明细。
 * 取数：GET /api/v1/dashboard/summary（契约 §4.1）。
 */

import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, Card, Col, Empty, Row, Space, Table, Typography } from 'antd';
import { AlertOutlined, CarOutlined, InboxOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { EChartsOption } from 'echarts';
import dayjs from 'dayjs';
import { dashboardApi, reviewCasesApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type { ReviewCase } from '@/api/types';
import { reasonLabel } from '@/constants/labels';
import { ATTENTION_COLORS } from '@/pages/shared/semanticStatus';
import { saasChartPalette, saasVisualTokens } from '@/theme/saasTheme';
import { useAsync } from '@/hooks/useAsync';
import Chart from '@/components/Chart';
import KpiCard from '@/components/KpiCard';

function trendOption(dates: string[], orders: number[], shipped: number[]): EChartsOption {
  const [orderColor, shippedColor] = saasChartPalette.categorical;
  return {
    color: [orderColor, shippedColor],
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => `${v} 单`,
    },
    legend: { data: ['订单数', '已发订单'], top: 0, right: 0, icon: 'circle' },
    grid: { left: 8, right: 12, top: 32, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLine: { lineStyle: { color: saasVisualTokens.neutral[300] } },
      axisTick: { show: false },
      axisLabel: { color: saasVisualTokens.neutral[500] },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: saasVisualTokens.neutral[100] } },
      axisLabel: { color: saasVisualTokens.neutral[500] },
    },
    series: [
      {
        name: '订单数',
        type: 'line',
        data: orders,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: `${orderColor}30` },
              { offset: 1, color: `${orderColor}02` },
            ],
          },
        },
      },
      {
        name: '已发订单',
        type: 'line',
        data: shipped,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: `${shippedColor}30` },
              { offset: 1, color: `${shippedColor}02` },
            ],
          },
        },
      },
    ],
  };
}

/**
 * 阻断/异常类原因 → 严重（红）；其余待关注（琥珀）。与工作台 attention 的 RED/YELLOW 语义对齐。
 *
 * 注意：这是 reason → severity 的前端派生表（后端 ReviewCase 契约未携带 severity）。
 * 新增严重原因码时需与本表同步；attention 聚合卡仍消费后端自带 severity，两者口径需保持一致。
 */
const CRITICAL_REASONS = new Set([
  'OUT_OF_STOCK',
  'PROCUREMENT_FAILED',
  'FULFILLMENT_EXCEPTION',
  'JD_SUBMIT_FAILED',
  'SYNC_FAILED',
  'TRACKING_OVERDUE',
  'RETURN_OVERDUE',
]);

function issueSeverity(reasonCode: string): { color: string; label: string } {
  const severe = CRITICAL_REASONS.has(reasonCode);
  return { color: severe ? ATTENTION_COLORS.severe : ATTENTION_COLORS.waiting, label: severe ? '严重' : '关注' };
}

/** 停留时长：自复核事项创建至今（小时/天）。 */
function ageText(createdAt: string): string {
  const hours = Math.max(0, Math.floor((Date.now() - Date.parse(createdAt)) / 3_600_000));
  if (hours < 1) return '<1 小时';
  if (hours < 24) return `${hours} 小时`;
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  return rest > 0 ? `${days} 天 ${rest} 时` : `${days} 天`;
}

/** 取全部 OPEN 复核事项（跨页取全，避免明细被分页截断）。 */
async function fetchOpenReviewCases(): Promise<ReviewCase[]> {
  const first = await reviewCasesApi.list({ page: 0, size: 200, status: 'OPEN' });
  if (first.total_pages <= 1) return first.items;
  const rest = await Promise.all(
    Array.from({ length: first.total_pages - 1 }, (_, page) =>
      reviewCasesApi.list({ page: page + 1, size: 200, status: 'OPEN' })),
  );
  return [first, ...rest].flatMap((page) => page.items);
}

const issueColumns: ColumnsType<ReviewCase> = [
  {
    title: '原因',
    dataIndex: 'reason_code',
    render: (v: string) => {
      const sev = issueSeverity(v);
      return (
        <span>
          <span
            style={{
              display: 'inline-block',
              width: 8,
              height: 8,
              borderRadius: '50%',
              background: sev.color,
              marginRight: 8,
              verticalAlign: 'middle',
            }}
            title={sev.label}
          />
          {reasonLabel(v)}
        </span>
      );
    },
  },
  { title: '复核单号', dataIndex: 'case_no', ellipsis: true },
  { title: '责任团队', dataIndex: 'responsible_team', ellipsis: true },
  {
    title: '订单',
    dataIndex: 'order_id',
    width: 104,
    render: (v?: string) => (v ? <Link to={`/orders/${v}`}>查看订单</Link> : '—'),
  },
  { title: '停留时长', dataIndex: 'created_at', width: 104, render: (v: string) => ageText(v) },
];

export default function DashboardPage() {
  // KPI 摘要与待介入明细并行加载，但互不拖垮：明细失败不影响 KPI 卡展示
  const { data, loading, error, reload } = useAsync(
    () =>
      Promise.all([
        dashboardApi.summary(dayjs().format('YYYY-MM-DD')).catch((err) => ({ summaryError: err })),
        fetchOpenReviewCases().catch((err) => ({ issuesError: err })),
      ]).then(([summaryResult, issuesResult]) => ({
        summary: summaryResult && 'summaryError' in summaryResult ? undefined : summaryResult,
        issues: issuesResult && 'issuesError' in issuesResult ? [] : issuesResult,
        summaryError: summaryResult && 'summaryError' in summaryResult ? summaryResult.summaryError : undefined,
        issuesError: issuesResult && 'issuesError' in issuesResult ? issuesResult.issuesError : undefined,
      })),
    [],
  );
  const summary = data?.summary;
  const issues = data?.issues ?? [];
  const issuesError = data?.issuesError;

  const option = useMemo(() => {
    if (!summary?.trend?.length) return null;
    return trendOption(
      summary.trend.map((t) => dayjs(t.business_date).format('MM-DD')),
      summary.trend.map((t) => t.order_count),
      summary.trend.map((t) => t.shipped_order_count),
    );
  }, [summary]);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {error ? (
        <Alert
          type="error"
          showIcon
          message="工作台数据加载失败"
          description={errorMessage(error)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
              重试
            </Button>
          }
        />
      ) : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="今日订单数"
            value={summary?.order_count}
            unit="单"
            color={saasVisualTokens.brand.primary}
            icon={<InboxOutlined />}
            spark={summary?.trend.map((t) => t.order_count)}
            loading={loading}
            tooltip="今日业务订单总数（BUSINESS 数据域）"
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="今日已发订单"
            value={summary?.shipped_order_count}
            unit="单"
            color={saasVisualTokens.brand.primary}
            icon={<CarOutlined />}
            spark={summary?.trend.map((t) => t.shipped_order_count)}
            loading={loading}
            tooltip="今日已实际发货的订单数"
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="待人工介入"
            value={summary?.pending_review_count}
            unit="项"
            color={ATTENTION_COLORS.waiting}
            icon={<AlertOutlined />}
            loading={loading}
            tooltip="待人工介入事项（复核/缺货/异常等）"
          />
        </Col>
        {summary?.attention.map((item) => (
          <Col key={item.reason_code} xs={24} sm={12} xl={6}>
            <KpiCard
              title={reasonLabel(item.reason_code)}
              value={item.count}
              unit="单"
              color={item.severity === 'RED' ? ATTENTION_COLORS.severe : ATTENTION_COLORS.waiting}
              icon={<WarningOutlined />}
              loading={loading}
            />
          </Col>
        ))}
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Card size="small" title="近 7 日订单与发货趋势">
            {option ? <Chart option={option} height={320} loading={loading} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无趋势数据" />}
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="待人工介入明细">
            <Table<ReviewCase>
              rowKey="id"
              size="small"
              columns={issueColumns}
              dataSource={issues}
              loading={loading}
              pagination={false}
              scroll={{ x: 620 }}
              locale={{ emptyText: issuesError
                ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="明细加载失败，请刷新重试" />
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待介入事项" /> }}
            />
          </Card>
        </Col>
      </Row>

      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        业务日：{summary?.business_date ?? '—'}（Asia/Shanghai）
      </Typography.Text>
    </Space>
  );
}
