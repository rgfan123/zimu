package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.connector.jd.JdResult;

/** 京东 addSoOrder 专用适配器端口；不接受原始请求，只接受受控编排生成的 capability。 */
public interface JdSalesOutboundWriter {

    JdResult create(PreparedJdSalesOutbound outbound);
}
