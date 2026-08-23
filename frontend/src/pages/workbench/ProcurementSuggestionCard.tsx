/**
 * 采购建议卡（Issue #110，原型 v-buy .pc 形态）：
 * 可比候选与被剔除候选并排展示、推荐高亮、剔除理由可见（AI 不藏牌）；
 * 「需人工确认」恒为显式标记；动作只有跳转，卡片自身不提交任何业务写操作（ADR 0010）。
 */

import { Link } from 'react-router-dom';
import type { SuggestionCardView } from './procurementSuggestion';

export default function ProcurementSuggestionCard({ view }: { view: SuggestionCardView }) {
  if (view.errorCode) {
    return (
      <article className="zs-pc">
        <div className="zs-pc-h">
          <div className="nm">
            <b>{view.ticketNo}</b>
            <div className="sk">{view.targetSku}</div>
          </div>
          <span className="zs-tag err">比价未完成</span>
        </div>
        <div className="zs-pc-b">
          <div className="zs-box why">
            <b>为什么没有建议</b>
            Agent 以稳定错误码返回 <span className="zs-mono">{view.errorCode}</span>：
            模型未配置 / 未注册 / 未启用时按 fail-closed 处理，不产生任何猜测结论。
          </div>
        </div>
      </article>
    );
  }

  return (
    <article className="zs-pc">
      <div className="zs-pc-h">
        <div className="nm">
          <b>{view.targetSku}</b>
          <div className="sk">
            {view.ticketNo}
            {view.quantity ? ` · 需求 ${view.quantity}` : ''}
          </div>
        </div>
        {view.requiresHuman ? <span className="zs-tag warn">需人工确认</span> : null}
      </div>

      <div className="zs-pc-b">
        <div className="zs-pr">
          {view.inventory ? (
            <>
              <div className="lb first">库存与需求</div>
              <span className="sp">可用库存</span>
              <span className="am">{view.inventory.available}</span>
              <span className="dl" />
              <span className="sp">缺口</span>
              <span className="am">{view.inventory.shortage}</span>
              <span className="dl" />
            </>
          ) : null}

          <div className={view.inventory ? 'lb' : 'lb first'}>候选报价</div>
          {view.quotes.length === 0 ? (
            <>
              <span className="sp zs-muted">无候选</span>
              <span className="am">—</span>
              <span className="dl" />
            </>
          ) : (
            view.quotes.map((quote) => (
              <div
                key={`${quote.provider}-${quote.excludedReason ?? 'ok'}`}
                style={{ display: 'contents' }}
                className={quote.recommended ? 'best' : quote.excludedReason ? 'excluded' : undefined}
              >
                <span className="sp">
                  {quote.provider}
                  {quote.excludedReason ? (
                    <span className="zs-tag err" style={{ marginLeft: 6 }}>{quote.excludedReason}</span>
                  ) : null}
                  {quote.recommended ? <span className="zs-tag brand" style={{ marginLeft: 6 }}>推荐</span> : null}
                </span>
                <span className="am">{quote.price}</span>
                <span className="dl">{quote.basis === '—' ? '' : quote.basis}</span>
              </div>
            ))
          )}
        </div>

        <div className="zs-box why">
          <b>为什么提出来</b>
          {view.why}
        </div>
        <div className="zs-box sug">
          <b>建议</b>
          {view.suggestion}
        </div>

        <div className="zs-prov">
          {view.provenance.map((item) => (
            <span key={item} className="zs-tag m">{item}</span>
          ))}
          {view.confidencePercent !== null ? (
            <span className="zs-tag m">置信度 {view.confidencePercent}%</span>
          ) : null}
        </div>
      </div>

      <div className="zs-pc-a">
        {/* ADR 0010：建议不创建任何东西——只跳去处理既有缺货工单 */}
        <Link to="/procurement/tickets">去处理该工单</Link>
        <Link to={`/procurement/price-compare?procurement_ticket_id=${encodeURIComponent(view.ticketId)}`}>
          查看完整比价
        </Link>
      </div>
    </article>
  );
}
