package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.basicinfo.JDBasicInfoService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdGoodsReadOnlyVerifierTest {

    /**
     * queryType 必须是 String "1"（官方枚举：1-查询全部信息；2-查询商品编号）。
     * 传 "2" 时京东只回商品编号，basicInfo 其余字段全为 null，enableFlag 会被误判成缺失而阻断建单。
     */
    @Test
    void sendsStringQueryTypeOneAndRejectsCallsInsideAnActiveTransaction() {
        JDBasicInfoService client = mock(JDBasicInfoService.class);
        when(client.queryGoodsInfo(anyMap())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new JdResult(true, "1000", "ok", "jd-request-1", List.of(Map.of(
                    "basicInfo", Map.of(
                            "goodsNo", "JD-SKU-1",
                            "erpGoodsNo", "ERP-JD-SKU-1",
                            "goodsName", "商品名",
                            "enableFlag", 2))));
        });
        JdGoodsReadOnlyVerifier verifier = new JdGoodsReadOnlyVerifier(client);

        JdGoodsReadOnlyVerifier.Verification result = verifier.verify("JD-SKU-1");

        assertThat(result.querySucceeded()).isTrue();
        assertThat(result.found()).isTrue();
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(client).queryGoodsInfo(request.capture());
        assertThat(request.getValue().get("queryType"))
                .isInstanceOf(String.class)
                .isEqualTo("1");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> verifier.verify("JD-SKU-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a database transaction");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
