import { formatDateTime } from '../format/dateTime.ts';

/** 实际发货时间缺失是可表达的业务事实，不用其他时间填充。 */
export function shipmentTimeLabel(value?: string | null): string {
  return formatDateTime(value, '未提供');
}
