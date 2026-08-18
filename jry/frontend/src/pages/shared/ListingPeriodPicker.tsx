/**
 * 上市周期选择器：上架日期 ~ 下架日期，可只填起始。
 * 表单值形态：{ from?: 'YYYY-MM-DD'; to?: 'YYYY-MM-DD' }；显式清空传空对象 {}。
 */

import { DatePicker } from 'antd';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';

export interface ListingPeriodValue {
  from?: string;
  to?: string;
}

interface ListingPeriodPickerProps {
  value?: ListingPeriodValue;
  onChange?: (value: ListingPeriodValue | undefined) => void;
}

export function ListingPeriodPicker({ value, onChange }: ListingPeriodPickerProps) {
  const from = value?.from ? dayjsOrNull(value.from) : null;
  const to = value?.to ? dayjsOrNull(value.to) : null;
  return (
    <DatePicker.RangePicker
      allowEmpty={[true, true]}
      placeholder={['上架日期（可空）', '下架日期（可空）']}
      value={[from, to]}
      onChange={(dates) => {
        if (!dates) {
          onChange?.({});
          return;
        }
        const [start, end] = dates;
        onChange?.({
          ...(start ? { from: start.format('YYYY-MM-DD') } : {}),
          ...(end ? { to: end.format('YYYY-MM-DD') } : {}),
        });
      }}
    />
  );
}

function dayjsOrNull(value: string): Dayjs | null {
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed : null;
}
