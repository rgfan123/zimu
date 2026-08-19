package cn.zimu.fulfillment.recon;

import cn.zimu.fulfillment.common.web.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出库信息内外事实并排查询（Ticket 01）：GET /api/v1/outbound-recon。
 *
 * <p>查询条件走 query string（type + value），刷新/分享链接可复现同一视图；
 * 查询本身走既有审计通道，生产环境按默认认证策略要求已认证操作人。
 */
@RestController
@RequestMapping("/api/v1/outbound-recon")
public class OutboundReconController {

    private final OutboundReconService service;

    public OutboundReconController(OutboundReconService service) {
        this.service = service;
    }

    /**
     * @param query_type  OUTBOUND_ORDER_NO（系统出库单号）/ JD_DELIVERY_NO（京东单号）/ ORDER_NO（订单号）
     * @param query_value 对应单号
     */
    @GetMapping
    public OutboundReconView query(
            @RequestParam(name = "query_type") String queryType,
            @RequestParam(name = "query_value") String queryValue) {
        return service.query(queryType, queryValue, RequestContext.current());
    }
}
