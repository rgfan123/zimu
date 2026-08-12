const SHANGHAI_DATE_TIME = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** 实际发货时间缺失是可表达的业务事实，不用其他时间填充。 */
export function shipmentTimeLabel(value?: string | null): string {
  if (!value) return '未提供';
  const parts = Object.fromEntries(
    SHANGHAI_DATE_TIME.formatToParts(new Date(value)).map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}
