/**
 * 商品档案 · 上架平台选择弹窗：点「上架」后先选择目标渠道平台，
 * 选定后进入对应平台的上传/上架页面（目前仅中汇渠道平台，后续渠道在此追加）。
 */

import { Modal, Typography } from 'antd';
import { CloudUploadOutlined, RightOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

interface PlatformUploadModalProps {
  open: boolean;
  onClose: () => void;
  /** 商品档案页当前筛选（搜索词/履约方），跳转后中汇渠道页沿用，减少重选。 */
  query?: string;
  providerId?: string;
}

interface PlatformOption {
  path: string;
  name: string;
  description: string;
}

/** 可选上架平台（渠道维度）；二级选择后跳转对应平台页。 */
const PLATFORM_OPTIONS: PlatformOption[] = [
  {
    path: '/upload-platform/zhonghui',
    name: '中汇渠道平台',
    description: '批量上传商品到中汇好泰 PMS（pms.zhonghuihaotai.com）',
  },
];

export default function PlatformUploadModal({ open, onClose, query, providerId }: PlatformUploadModalProps) {
  const navigate = useNavigate();

  const choose = (platform: PlatformOption) => {
    onClose();
    const params = new URLSearchParams();
    if (query?.trim()) params.set('query', query.trim());
    if (providerId) params.set('provider_id', providerId);
    const suffix = params.toString();
    navigate(platform.path + (suffix ? `?${suffix}` : ''));
  };

  return (
    <Modal title="上架平台" open={open} onCancel={onClose} footer={null} width={520} destroyOnHidden>
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        请选择要把商品上架到的渠道平台：
      </Typography.Paragraph>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {PLATFORM_OPTIONS.map((platform) => (
          <div
            key={platform.path}
            role="button"
            tabIndex={0}
            onClick={() => choose(platform)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') choose(platform);
            }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              padding: '12px 14px',
              border: '1px solid #d9d9d9',
              borderRadius: 8,
              cursor: 'pointer',
              transition: 'border-color 0.2s, box-shadow 0.2s',
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.borderColor = '#1677ff';
              e.currentTarget.style.boxShadow = '0 0 0 2px rgba(22, 119, 255, 0.1)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.borderColor = '#d9d9d9';
              e.currentTarget.style.boxShadow = 'none';
            }}
          >
            <CloudUploadOutlined style={{ fontSize: 22, color: '#1677ff' }} />
            <div style={{ flex: 1 }}>
              <Typography.Text strong>{platform.name}</Typography.Text>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {platform.description}
                </Typography.Text>
              </div>
            </div>
            <RightOutlined style={{ color: '#8c8c8c' }} />
          </div>
        ))}
      </div>
    </Modal>
  );
}
