package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** 京东件数换算批量导入输入（jd-real-sdk-switch 03）；行级校验在 Service 层完成。 */
public record ProviderSkuJdFactorImport(List<ProviderSkuJdFactorRow> rows) {

    public record ProviderSkuJdFactorRow(
            String providerSkuCode,
            @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer jdPiecesPerUnit) {}
}
