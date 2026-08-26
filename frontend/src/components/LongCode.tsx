/**
 * 长单号展示组件（UIUX-06 #140）：订单号 / 发货单号 / 履约单号 / 复核单号等
 * 「前缀 + 32 位十六进制」长码的统一展示——单行省略不折行，悬停显示完整单号，
 * 复制入口一键复制全码；可选跳转目标（如订单详情）。
 */

import { Button, Tooltip, Typography, message } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

interface LongCodeProps {
  /** 完整单号 */
  value: string;
  /** 点击跳转目标（如 /orders/{id}）；不传则仅展示 */
  to?: string;
  /** 单行省略宽度上限（px 或 CSS 长度），默认 170 */
  width?: number | string;
}

export function LongCode({ value, to, width = 170 }: LongCodeProps) {
  const navigate = useNavigate();

  const handleCopy = async (event: React.MouseEvent) => {
    event.stopPropagation();
    try {
      await navigator.clipboard.writeText(value);
      message.success('已复制完整单号');
    } catch {
      message.warning('复制失败，请手动选择复制');
    }
  };

  return (
    <Tooltip title={value} mouseEnterDelay={0.25}>
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          maxWidth: width,
          verticalAlign: 'middle',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {to ? (
          <Typography.Link
            onClick={() => navigate(to)}
            ellipsis
            style={{ display: 'inline-block', minWidth: 0, flex: 1 }}
          >
            {value}
          </Typography.Link>
        ) : (
          <Typography.Text
            ellipsis
            style={{ display: 'inline-block', minWidth: 0, flex: 1 }}
          >
            {value}
          </Typography.Text>
        )}
        <Button
          type="text"
          size="small"
          icon={<CopyOutlined />}
          aria-label="复制完整单号"
          onClick={handleCopy}
        />
      </span>
    </Tooltip>
  );
}

export default LongCode;
