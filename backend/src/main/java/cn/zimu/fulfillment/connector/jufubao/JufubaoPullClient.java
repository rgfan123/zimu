package cn.zimu.fulfillment.connector.jufubao;

import java.util.List;
import java.util.Map;

/** 聚福宝待发货订单读取端口；生产 HTTP 适配器与发货适配器共用同一登录会话。 */
public interface JufubaoPullClient {

    record LoginResult(boolean ok, String businessCode, String message) {
        public static LoginResult failed(String businessCode, String message) {
            return new LoginResult(false, businessCode, message);
        }
    }

    LoginResult login();

    List<Map<String, Object>> pullOrders(long startEpoch, long endEpoch);

    class PullTransportException extends RuntimeException {
        public PullTransportException(String message) {
            super(message, null, false, false);
        }

        public PullTransportException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }
}
