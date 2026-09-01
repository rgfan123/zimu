import type { SourceSyncCheck } from '@/api/sourceSync';

/**
 * 来源回传的说人话层。
 *
 * <p>服务端的 business_code 是给日志和排障用的稳定标识，不是给人读的。
 * 2026-08-29 生产上界面直接把 {@code SOURCE_SYNC_ALREADY_SYNCED} 和
 * 「内部 Shipment 事实尚未通过门禁」怼在操作员脸上，还把「已经回传过了」这件<b>好事</b>
 * 渲染成红色的「1 项阻断」。这个模块负责把码翻译成一句话，并区分
 * 「这单成了」和「这单出事了」。
 *
 * <p>翻译不到的码<b>原样显示</b>：宁可让人看见一个不认识的码去问，
 * 也不要编一句听起来很顺但可能是错的解释。
 */

/** 回传面板的整体语气：成功 / 还差点事 / 真出问题了。 */
export type SourceSyncTone = 'done' | 'blocked' | 'ready';

export interface SourceSyncPresentation {
  tone: SourceSyncTone;
  /** 一句话说清现在是什么状况。 */
  headline: string;
  /** 每条阻断翻成人话；已回传这种「好消息码」不会出现在这里。 */
  reasons: Array<{ code: string; text: string }>;
  /** 按钮文案；不可点时说明为什么。 */
  actionLabel: string;
}

/** 「已经回传过了」不是阻断，是完成态。 */
const DONE_CODES = new Set(['SOURCE_SYNC_ALREADY_SYNCED']);

const REASONS: Record<string, string> = {
  SOURCE_SYNC_IN_PROGRESS: '这一单正在回传，稍等一下再看',
  SOURCE_SYNC_RECONCILIATION_REQUIRED: '上一次回传的结果没确认成功，要先对账才能再发',
  SHIPMENT_NOT_SHIPPED: '这批货还没发出，没有可以回传的事实',
  FORMAL_TRACKING_REQUIRED: '还没有正式的物流公司和运单号',
  SOURCE_BATCH_NOT_CONFIRMED: '这批来源订单还没确认，先去确认',
  SOURCE_SYNC_CHANNEL_UNSUPPORTED: '这个来源平台还不支持在线回传，要走回填文件人工上传',
  SOURCE_SYNC_ONLINE_TRANSPORT_REQUIRED: '这个来源平台的在线回传还没开启（连接配置里还是文件模式）',
  SOURCE_SYNC_CONNECTOR_DISABLED: '这个来源平台的连接被停用了',
  SOURCE_PLATFORM_CARRIER_UNMAPPED: '来源平台无法唯一识别这家物流公司，需要核对平台承运商字典或专用接口代码',
  SOURCE_SYNC_SINGLE_SOURCE_LINE_REQUIRED: '这批发货对应了不止一个来源子单，目前只支持一单对一单',
  SOURCE_SYNC_MULTI_SHIPMENT_UNSUPPORTED: '同一个来源子单被拆成了多批发货，要人工处理',
  SOURCE_SYNC_LINEAGE_AMBIGUOUS: '发货明细和来源行对不上一一对应，血缘存疑',
  SOURCE_SYNC_RAW_ROW_REUSED: '同一条来源行被算进了多条发货明细，份数会重复',
  SOURCE_SYNC_FULL_SHIPMENT_REQUIRED: '这批有没发完的行，目前只支持整批全发的回传',
  SOURCE_SYNC_FULL_FULFILLMENT_REQUIRED: '这个来源子单还没完全履约',
  SOURCE_SYNC_CUMULATIVE_QUANTITY_INCOMPLETE: '累计实发还没盖满下单数量',
  SOURCE_SYNC_CANCELLED_REMAINING_UNSUPPORTED: '这单有取消剩余量，要人工处理',
  SOURCE_SYNC_SOURCE_QUANTITY_INCOMPLETE: '来源下单份数和准备回传的实发份数对不上',
  SOURCE_SYNC_QUANTITY_NOT_SOURCE_UNIT: '内部数量换算不回来源的整数份数',
  SOURCE_SYNC_ITEMS_REQUIRED: '这批发货没有可回传的明细',
};

/**
 * 平台读不到的统一说法。
 *
 * <p>各渠道的码不同（{@code JUFUBAO_/CAISHIXIAN_/FEIXIANG_PLATFORM_CHECK_UNAVAILABLE}），
 * 但对操作员是同一件事。括号里那句是 2026-08-29 实测出来的最常见成因：
 * 已经在平台后台手动发过货的单，在待发货列表里就查不到了。
 */
function platformUnavailableReason(code: string): string | null {
  return code.endsWith('_PLATFORM_CHECK_UNAVAILABLE')
    ? '在来源平台上查不到这一单（最常见的原因是已经在平台后台手动发过货了）'
    : null;
}

export function presentSourceSync(check: SourceSyncCheck): SourceSyncPresentation {
  const done = check.blockers.some((blocker) => DONE_CODES.has(blocker.code));
  if (done) {
    return {
      tone: 'done',
      headline: '这一单已经回传过了，不用再发',
      reasons: [],
      actionLabel: '已回传',
    };
  }

  const reasons = check.blockers.map((blocker) => ({
    code: blocker.code,
    // 翻不动就把服务端的话原样端上来，绝不编。
    text: REASONS[blocker.code] ?? platformUnavailableReason(blocker.code) ?? blocker.message,
  }));

  if (check.ready) {
    return {
      tone: 'ready',
      headline: '条件都满足了，可以回传',
      reasons: [],
      actionLabel: '回传运单号',
    };
  }

  return {
    tone: 'blocked',
    headline: reasons.length === 1 ? '还差一件事' : `还差 ${reasons.length} 件事`,
    reasons,
    actionLabel: '暂时不能回传',
  };
}
