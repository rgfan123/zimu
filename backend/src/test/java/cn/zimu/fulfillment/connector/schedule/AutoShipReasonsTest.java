package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 阻断原因归类。这些断言存在的理由是一句生产反馈：运营收到「映射门禁未通过」
 * 却以为是缺货，跑去催补货——需求里点名不许再笼统报「失败」。
 */
class AutoShipReasonsTest {

    @Test
    void onlyStockInsufficientIsRealStockShortage() {
        assertThat(AutoShipReasons.categorize("JD_STOCK_INSUFFICIENT"))
                .isEqualTo(AutoShipReasons.Category.STOCK_INSUFFICIENT);
    }

    @Test
    void mappingGateIsNeverReportedAsStockShortage() {
        // ShipmentJdStockCheckService 在映射门禁未过时直接短路返回，
        // 根本没有向京东发出库存查询——「有没有货」这个问题从来没被问过。
        assertThat(AutoShipReasons.categorize("JD_SKU_MAPPING_GATE_BLOCKED"))
                .isEqualTo(AutoShipReasons.Category.SKU_MAPPING)
                .isNotEqualTo(AutoShipReasons.Category.STOCK_INSUFFICIENT);
    }

    @Test
    void missingWarehouseRowIsNotZeroStock() {
        // 京东响应里缺目标仓那一行，不能被解释成 0 库存——报成缺货会让人去补根本不缺的货。
        assertThat(AutoShipReasons.categorize("JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED"))
                .isEqualTo(AutoShipReasons.Category.JD_ANSWER_UNUSABLE);
        assertThat(AutoShipReasons.categorize("JD_STOCK_QUERY_FAILED"))
                .isEqualTo(AutoShipReasons.Category.JD_ANSWER_UNUSABLE);
        assertThat(AutoShipReasons.categorize("JD_STOCK_RESPONSE_AMBIGUOUS"))
                .isEqualTo(AutoShipReasons.Category.JD_ANSWER_UNUSABLE);
        assertThat(AutoShipReasons.categorize("JD_STOCK_RESPONSE_INVALID"))
                .isEqualTo(AutoShipReasons.Category.JD_ANSWER_UNUSABLE);
    }

    @Test
    void unknownCodeIsNotSquashedIntoAnyKnownCategory() {
        assertThat(AutoShipReasons.categorize("SOMETHING_NEW")).isEqualTo(AutoShipReasons.Category.OTHER);
        assertThat(AutoShipReasons.categorize(null)).isEqualTo(AutoShipReasons.Category.OTHER);
        assertThat(AutoShipReasons.categorize("  ")).isEqualTo(AutoShipReasons.Category.OTHER);
    }

    @Test
    void mappingGateReportsTheInnerReasonNotTheOuterCode() {
        // 外层码对全部 14 种原因都是同一个字符串，只播报它等于什么都没说。
        assertThat(AutoShipReasons.specificCode("JD_SKU_MAPPING_GATE_BLOCKED", "UNIT_CONVERSION_MISSING"))
                .isEqualTo("UNIT_CONVERSION_MISSING");
        assertThat(AutoShipReasons.specificCode("JD_SKU_MAPPING_GATE_BLOCKED", "NON_INTEGRAL_QUANTITY"))
                .isEqualTo("NON_INTEGRAL_QUANTITY");
    }

    @Test
    void mappingGateFallsBackToOuterCodeWhenInnerReasonIsMissing() {
        // 退回本身也是信息：门禁判了阻断却没给出逐项明细。
        assertThat(AutoShipReasons.specificCode("JD_SKU_MAPPING_GATE_BLOCKED", null))
                .isEqualTo("JD_SKU_MAPPING_GATE_BLOCKED");
        assertThat(AutoShipReasons.specificCode("JD_SKU_MAPPING_GATE_BLOCKED", ""))
                .isEqualTo("JD_SKU_MAPPING_GATE_BLOCKED");
    }

    @Test
    void nonMappingCodesKeepTheirOwnCode() {
        assertThat(AutoShipReasons.specificCode("JD_STOCK_INSUFFICIENT", "IGNORED"))
                .isEqualTo("JD_STOCK_INSUFFICIENT");
    }

    @Test
    void knownFalsePositiveReasonsAreFlagged() {
        // 生产实测：报「未配置映射」而映射好好配着。不加提示的话，
        // 运营会照字面意思去重配一遍已经存在的映射。
        assertThat(AutoShipReasons.falsePositiveProne("MAPPING_MISSING")).isTrue();
        assertThat(AutoShipReasons.falsePositiveProne("INTERNAL_SKU_MISSING")).isTrue();
        assertThat(AutoShipReasons.falsePositiveProne("UNIT_CONVERSION_MISSING")).isFalse();
    }

    @Test
    void summaryKeepsStockAndMappingApartAndOrdersStockFirst() {
        Map<AutoShipReasons.Category, List<String>> summary = AutoShipReasons.summarize(List.of(
                Map.of("code", "JD_SKU_MAPPING_GATE_BLOCKED", "mapping_issue_code", "MAPPING_MISSING"),
                Map.of("code", "JD_STOCK_INSUFFICIENT"),
                Map.of("code", "JD_SKU_MAPPING_GATE_BLOCKED", "mapping_issue_code", "GOODS_DISABLED")));

        assertThat(summary.keySet())
                .containsExactly(
                        AutoShipReasons.Category.STOCK_INSUFFICIENT, AutoShipReasons.Category.SKU_MAPPING);
        assertThat(summary.get(AutoShipReasons.Category.STOCK_INSUFFICIENT))
                .containsExactly("JD_STOCK_INSUFFICIENT");
        assertThat(summary.get(AutoShipReasons.Category.SKU_MAPPING))
                .containsExactly("MAPPING_MISSING", "GOODS_DISABLED");
    }

    @Test
    void describeIsReadableAndFlagsSuspectReasons() {
        String text = AutoShipReasons.describe(AutoShipReasons.summarize(List.of(
                Map.of("code", "JD_STOCK_INSUFFICIENT"),
                Map.of("code", "JD_SKU_MAPPING_GATE_BLOCKED", "mapping_issue_code", "MAPPING_MISSING"))));

        assertThat(text)
                .isEqualTo("缺货: JD_STOCK_INSUFFICIENT; 映射校验: MAPPING_MISSING(疑似误报)");
    }

    @Test
    void describeIsEmptyWhenThereIsNothingToSay() {
        assertThat(AutoShipReasons.describe(AutoShipReasons.summarize(List.of()))).isEmpty();
    }

    @Test
    void duplicateBlockersCollapseSoTheCardDoesNotRepeatItself() {
        Map<AutoShipReasons.Category, List<String>> summary = AutoShipReasons.summarize(List.of(
                Map.of("code", "JD_STOCK_INSUFFICIENT"),
                Map.of("code", "JD_STOCK_INSUFFICIENT")));

        assertThat(summary.get(AutoShipReasons.Category.STOCK_INSUFFICIENT))
                .containsExactly("JD_STOCK_INSUFFICIENT");
    }
}
