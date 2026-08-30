import { useState } from 'react';
import { AutoComplete, Button, Card, Form, Input, Popconfirm, Space, Typography, message } from 'antd';
import { shipmentsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { manualTrackingTone, type CarrierOption } from '@/api/manualTracking';
import { useAsync } from '@/hooks/useAsync';

const { Text } = Typography;

interface Props {
  shipmentId: string;
  /** 录入成功后让外层刷新详情与列表。 */
  onEntered: () => void;
}

/**
 * 人工录入运单。
 *
 * <p>第三方履约方常常只在群里发一个运单号，而在此之前系统唯一的入口是「下载导出文件、
 * 填进去、再传回来」。这张卡把那一圈省掉。
 *
 * <p>快递公司<b>可以留空</b>：服务端按运单号前缀推断（JDVA→京东、SF→顺丰…）。
 * 推不出来它会报错要求明确指定，<b>不会默认一个</b>——承运商会进回填文件、进来源平台回传，
 * 猜错的后果是客户拿着错的快递公司去查一个查不到的单号。
 */
export default function ManualTrackingPanel({ shipmentId, onEntered }: Props) {
  const [carrier, setCarrier] = useState('');
  const [trackingNumber, setTrackingNumber] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const carriers = useAsync<CarrierOption[]>(() => shipmentsApi.carriers(), []);

  const submit = async () => {
    if (!trackingNumber.trim()) return;
    setSubmitting(true);
    try {
      const outcome = await shipmentsApi.enterManualTracking(shipmentId, carrier, trackingNumber);
      // 三种结果语气不同：成了 / 你已经录过了 / 和已有的打架。绝不把后两种当成功报。
      message[manualTrackingTone(outcome.status)](outcome.message);
      if (outcome.status !== 'CONFLICT') {
        setTrackingNumber('');
        setCarrier('');
      }
      onEntered();
    } catch (err) {
      message.error(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const options = (carriers.data ?? []).map((item) => ({ value: item.name, label: item.name }));

  return (
    <Card size="small" title="录入运单">
      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        <Text type="secondary">
          第三方只给了单号、没回填文件时用这里。填完这批就算发出，会推发货卡。
        </Text>
        <Form layout="vertical" size="small" style={{ marginBottom: 0 }}>
          <Form.Item label="运单号" required style={{ marginBottom: 10 }}>
            <Input
              value={trackingNumber}
              onChange={(event) => setTrackingNumber(event.target.value)}
              placeholder="如 SF5152783768751"
              allowClear
            />
          </Form.Item>
          <Form.Item
            label="快递公司"
            extra="留空就按运单号前缀自动认；认不出来会让你补，不会替你猜"
            style={{ marginBottom: 12 }}
          >
            <AutoComplete
              value={carrier}
              onChange={setCarrier}
              options={options}
              placeholder="留空自动识别"
              allowClear
              filterOption={(input, option) =>
                String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
        </Form>
        <Popconfirm
          title="确认录入"
          description={(
            <div style={{ maxWidth: 320 }}>
              录进去这批就算<b>已发货</b>：会推发货卡，并可能触发把运单号回传给客户平台。
              录错要走人工对账才能改。
            </div>
          )}
          okText="确认录入"
          cancelText="再看看"
          onConfirm={submit}
          disabled={!trackingNumber.trim() || submitting}
        >
          <Button type="primary" loading={submitting} disabled={!trackingNumber.trim()}>
            录入运单
          </Button>
        </Popconfirm>
      </Space>
    </Card>
  );
}
