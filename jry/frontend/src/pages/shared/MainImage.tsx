/**
 * 商品主图：单图上传控件（AntD Form 值 = file_ref 字符串）与列表缩略图。
 * 上传走 /api/v1/product-images（内容寻址存储），值落 products.main_image_ref。
 */

import { useState } from 'react';
import { Button, Image, Space, Typography, Upload } from 'antd';
import { DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { productImagesApi, productImageUrl } from '@/api/endpoints';

interface MainImageUploadProps {
  value?: string | null;
  onChange?: (ref: string | null) => void;
}

/** 表单控件：上传/预览/替换/清除；value 为 null 表示已清除。 */
export function MainImageUpload({ value, onChange }: MainImageUploadProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <Space direction="vertical" size={8}>
      {value ? (
        <Image
          src={productImageUrl(value)}
          alt="主图预览"
          width={96}
          height={96}
          style={{ objectFit: 'cover', borderRadius: 6 }}
        />
      ) : (
        <Typography.Text type="secondary">未上传主图</Typography.Text>
      )}
      {error ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{error}</Typography.Text> : null}
      <Space size={8}>
        <Upload
          accept="image/png,image/jpeg,image/webp"
          showUploadList={false}
          customRequest={async ({ file, onSuccess, onError }) => {
            try {
              setError(null);
              setUploading(true);
              const result = await productImagesApi.upload(file as File);
              onChange?.(result.file_ref);
              onSuccess?.(result);
            } catch (e) {
              const message = errorMessage(e);
              setError(message);
              onError?.(e as Error);
            } finally {
              setUploading(false);
            }
          }}
        >
          <Button size="small" icon={<UploadOutlined />} loading={uploading}>
            {value ? '替换' : '上传'}
          </Button>
        </Upload>
        {value ? (
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onChange?.(null)}>
            清除
          </Button>
        ) : null}
      </Space>
    </Space>
  );
}

/** 列表缩略图：无图显示占位符，点击可看大图。 */
export function MainImageThumb({ ref }: { ref?: string | null }) {
  if (!ref) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Image
      src={productImageUrl(ref)}
      alt="主图"
      width={48}
      height={48}
      style={{ objectFit: 'cover', borderRadius: 4 }}
      preview={{ mask: '查看' }}
    />
  );
}
