/**
 * 系统管理 · 运营人员：登记内部运营人员（姓名、企微 userid、所属责任团队）。
 * Issue #89：只做「运营人员 ↔ 企微 userid ↔ 责任团队」映射与责任归属，不做登录/权限；
 * 未绑定 userid 的人员在需要推送时由解析 seam 明确提示，本页展示绑定状态与运营提示。
 */

import { useMemo, useState } from 'react';
import {
  Alert,
  App as AntApp,
  Button,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { operatorsApi } from '@/api/endpoints';
import type { Operator } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import {
  AdminEmpty,
  AdminStatusTag,
} from '@/pages/shared/AdminVisualComponents';
import { adminFailurePresentation, adminPageState } from '@/pages/shared/adminVisual';
import { PageState } from '@/pages/shared/PageState';
import '@/pages/shared/adminSurface.css';

/** 企微 userid 前端校验：与后端契约一致（1..64，首字符数字/字母，可含 _ - @ .）。 */
const WECOM_USERID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_@.\-]{0,63}$/;

const validateWecomUserid = (_rule: unknown, value: string | undefined) => {
  if (typeof value !== 'string' || value.trim().length === 0) return Promise.resolve();
  const trimmed = value.trim();
  if (trimmed.length > 64 || !WECOM_USERID_PATTERN.test(trimmed)) {
    return Promise.reject(new Error('企微 userid 必须为 1..64 个字符，首字符为数字或字母，只能包含数字、字母与 _ - @ .'));
  }
  return Promise.resolve();
};

/** 责任团队归一化（与后端一致）：trim + 大写。 */
const normalizeTeam = (value: string) => value.trim().toUpperCase();

const PAGE_SIZE = 10;

export default function OperatorsPage() {
  const { message: messageApi } = AntApp.useApp();
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [teamFilter, setTeamFilter] = useState<string | undefined>();
  const { data, loading, error, reload } = useAsync(
    () => operatorsApi.list({ page, size: PAGE_SIZE, query: query || undefined, responsible_team: teamFilter }),
    [page, query, teamFilter],
  );
  const [editing, setEditing] = useState<Operator | null>(null);
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const rows = useMemo(() => data?.items ?? [], [data]);
  const total = data?.total_elements ?? 0;

  const openCreate = () => {
    setEditing(null);
    setCreating(true);
    form.resetFields();
    form.setFieldsValue({ active: true });
  };

  const openEdit = (record: Operator) => {
    form.setFieldsValue({
      display_name: record.display_name,
      responsible_team: record.responsible_team,
      wecom_userid: record.wecom_userid ?? '',
      active: record.active,
    });
    setEditing(record);
    setCreating(false);
  };

  const closeEditor = () => {
    if (submitting) return;
    setEditing(null);
    setCreating(false);
    form.resetFields();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const displayName = String(values.display_name).trim();
      const team = normalizeTeam(String(values.responsible_team));
      const useridRaw = typeof values.wecom_userid === 'string' ? values.wecom_userid.trim() : '';
      const active = typeof values.active === 'boolean' ? values.active : true;
      if (editing) {
        // 只从实际变化的业务字段构建 PATCH（null/undefined = 后端不改动）；空串 userid = 显式清除绑定
        const patch = {
          display_name: displayName !== editing.display_name ? displayName : undefined,
          responsible_team: team !== editing.responsible_team ? team : undefined,
          wecom_userid: (useridRaw || null) !== (editing.wecom_userid ?? null) ? useridRaw : undefined,
          active: active !== editing.active ? active : undefined,
        };
        if (Object.values(patch).every((value) => value === undefined)) {
          // 无变化：明确反馈「无变更」并关闭弹窗，不调用 PATCH
          messageApi.info('无变更');
          setEditing(null);
          setCreating(false);
          form.resetFields();
          return;
        }
        setSubmitting(true);
        await operatorsApi.update(editing.id, {
          expected_version: editing.version,
          ...patch,
        });
        messageApi.success('运营人员已保存');
      } else {
        setSubmitting(true);
        await operatorsApi.create({
          display_name: displayName,
          responsible_team: team,
          wecom_userid: useridRaw || null,
          active,
        });
        messageApi.success('运营人员已登记');
      }
      setEditing(null);
      setCreating(false);
      form.resetFields();
      reload();
    } catch (submitError) {
      if (submitError instanceof Error) messageApi.error(errorMessage(submitError));
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<Operator> = [
    { title: '姓名', dataIndex: 'display_name', width: 160 },
    {
      title: '责任团队',
      dataIndex: 'responsible_team',
      width: 180,
      render: (value: string) => <Tag color="geekblue" style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</Tag>,
    },
    {
      title: '企微绑定',
      dataIndex: 'wecom_userid',
      width: 220,
      render: (value: string | null, record) =>
        value ? (
          <Tooltip title="已绑定企微 userid；首次使用前需先与企微机器人打招呼（@机器人 发一条消息）才能收到个人推送">
            <Tag color="blue" style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</Tag>
          </Tooltip>
        ) : (
          <Tooltip title={`${record.display_name} 未绑定企微 userid：需要推送时系统会明确提示，不会静默跳过`}>
            <Tag color="warning">未绑定</Tag>
          </Tooltip>
        ),
    },
    {
      title: '状态',
      dataIndex: 'active',
      width: 100,
      render: (value: boolean) => <AdminStatusTag status={value ? 'ACTIVE' : 'INACTIVE'} />,
    },
    { title: '版本', dataIndex: 'version', width: 80, align: 'right' },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, record) => <Typography.Link onClick={() => openEdit(record)}>编辑</Typography.Link>,
    },
  ];

  const viewState = adminPageState(loading, error, rows.length > 0);

  if (viewState === 'loading') {
    return (
      <div className="admin-page">
        <PageState state="loading" description="正在加载运营人员…" />
      </div>
    );
  }

  if (viewState === 'error') {
    const presentation = adminFailurePresentation(error, '运营人员加载失败');
    return (
      <div className="admin-page">
        <PageState state="error" message={presentation.title} description={presentation.description} onRetry={reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="运营人员"
        description="登记内部运营人员并绑定企微 userid，让 responsible_team 可以解析到具体人员。只做映射与责任归属，不做登录与权限。"
        actions={<Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="首次使用前，请先让成员与企微机器人打一次招呼"
          description="企微机器人主动推送通常要求对方先与机器人有过会话；未绑定 userid 的人员在需要推送时会得到明确提示，系统不会静默跳过。请登记后要求每位成员在企微里 @机器人 发一条消息，实测结论以真实企微测试为准。"
        />
        <div className="admin-crud">
          <div className="admin-toolbar">
            <Space wrap>
              <Input.Search
                allowClear
                placeholder="按姓名 / 企微 userid 搜索"
                style={{ width: 240 }}
                onSearch={(value) => {
                  setPage(0);
                  setQuery(value.trim());
                }}
              />
              <Input.Search
                allowClear
                placeholder="责任团队（精确筛选，自动大写）"
                style={{ width: 220 }}
                onSearch={(value) => {
                  setPage(0);
                  // 精确团队值由服务端筛选；不从当前页推导选项（当前页只是分页片段，不是全集）
                  setTeamFilter(value.trim() ? normalizeTeam(value) : undefined);
                }}
              />
            </Space>
            <div className="admin-toolbar__spacer" />
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建
            </Button>
          </div>

          <div className="admin-surface" style={{ marginTop: 16 }}>
            <DataTable<Operator>
              rowKey="id"
              columns={columns}
              dataSource={rows}
              size="middle"
              scroll={{ x: 900 }}
              pagination={{
                current: page + 1,
                pageSize: PAGE_SIZE,
                total,
                showSizeChanger: false,
                showTotal: (value) => `共 ${value} 条`,
                onChange: (nextPage) => setPage(nextPage - 1),
              }}
              emptyText={<AdminEmpty description="暂无运营人员登记" />}
            />
          </div>
        </div>
      </PageShell>

      <Modal
        title={editing ? `编辑运营人员 ${editing.display_name}` : '新建运营人员'}
        open={creating || Boolean(editing)}
        onCancel={closeEditor}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okButtonProps={{ disabled: submitting }}
        cancelButtonProps={{ disabled: submitting }}
        closable={!submitting}
        maskClosable={!submitting}
        keyboard={!submitting}
        width={520}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="display_name"
            label="姓名"
            rules={[
              { required: true, message: '请填写姓名' },
              { max: 64, message: '姓名最长 64 个字符' },
              { whitespace: true, message: '姓名不能只含空白' },
            ]}
          >
            <Input placeholder="请输入姓名" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="responsible_team"
            label="责任团队"
            extra="保存时自动大写（如 ORDER_OPS / CUSTOMER_OPS / SKU_OPS）"
            rules={[
              { required: true, message: '请填写责任团队' },
              { max: 32, message: '责任团队最长 32 个字符' },
              { whitespace: true, message: '责任团队不能只含空白' },
            ]}
          >
            <Input placeholder="如 ORDER_OPS" maxLength={32} />
          </Form.Item>
          <Form.Item
            name="wecom_userid"
            label="企微 userid"
            extra="首次使用前，请先让该成员与企微机器人打一次招呼（@机器人 发一条消息）；留空 = 未绑定，编辑时留空并保存 = 清除绑定"
            rules={[{ validator: validateWecomUserid }]}
          >
            <Input placeholder="请输入企微 userid（留空 = 未绑定）" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="active"
            label="启用"
            valuePropName="checked"
            extra="停用后该人员不再出现在团队解析结果中；不做物理删除"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
