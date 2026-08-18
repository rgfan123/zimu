package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdStockUnitConverterTest {

    @Test
    void requiredPiecesRoundsUpToWholePieces() {
        assertThat(JdStockUnitConverter.requiredPieces(new BigDecimal("2.000"), new BigDecimal("1.000")))
                .isEqualByComparingTo("2");
        // 2.4 盒 × 1 件/盒 → 3 件：京东按整件履约，必须向上取整
        assertThat(JdStockUnitConverter.requiredPieces(new BigDecimal("2.400"), new BigDecimal("1.000")))
                .isEqualByComparingTo("3");
        // 0.5 盒也要占用 1 件库存
        assertThat(JdStockUnitConverter.requiredPieces(new BigDecimal("0.500"), new BigDecimal("1.000")))
                .isEqualByComparingTo("1");
        assertThat(JdStockUnitConverter.requiredPieces(new BigDecimal("60.000"), new BigDecimal("0.500")))
                .isEqualByComparingTo("30");
        // 201 盒 × 0.5 件/盒 = 100.5 → 101 件
        assertThat(JdStockUnitConverter.requiredPieces(new BigDecimal("201.000"), new BigDecimal("0.500")))
                .isEqualByComparingTo("101");
    }

    @Test
    void factorDefaultsToOneAndParsesJsonValueShapes() {
        assertThat(JdStockUnitConverter.factorOrNull(null)).isEqualByComparingTo("1.000");
        assertThat(JdStockUnitConverter.factorOrNull(Map.of())).isEqualByComparingTo("1.000");
        assertThat(JdStockUnitConverter.factorOrNull(Map.of("other_key", 2))).isEqualByComparingTo("1.000");
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0.5)))
                .isEqualByComparingTo("0.5");
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, "2.000")))
                .isEqualByComparingTo("2.000");
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 1L)))
                .isEqualByComparingTo("1");
    }

    @Test
    void invalidFactorConfigurationIsRejectedAsUnparseable() {
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, -1))).isNull();
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0))).isNull();
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, "abc"))).isNull();
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, Boolean.TRUE)))
                .isNull();
    }

    @Test
    void explicitFactorNeverInventsAConversionWhenConfigurationIsMissing() {
        assertThat(JdStockUnitConverter.explicitFactorOrNull(null)).isNull();
        assertThat(JdStockUnitConverter.explicitFactorOrNull(Map.of())).isNull();
        assertThat(JdStockUnitConverter.explicitFactorOrNull(Map.of("other_key", 1))).isNull();
        assertThat(JdStockUnitConverter.explicitFactorOrNull(
                Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, "2.000")))
                .isEqualByComparingTo("2.000");
        assertThat(JdStockUnitConverter.explicitFactorOrNull(
                Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0))).isNull();
    }

    @Test
    void exactPiecesRejectsFractionsNonPositiveAndMissingValues() {
        assertThat(JdStockUnitConverter.exactPiecesOrNull(
                new BigDecimal("2.000"), new BigDecimal("0.500"))).isEqualByComparingTo("1");
        assertThat(JdStockUnitConverter.exactPiecesOrNull(
                new BigDecimal("1.000"), new BigDecimal("0.500"))).isNull();
        assertThat(JdStockUnitConverter.exactPiecesOrNull(BigDecimal.ZERO, BigDecimal.ONE)).isNull();
        assertThat(JdStockUnitConverter.exactPiecesOrNull(null, BigDecimal.ONE)).isNull();
        assertThat(JdStockUnitConverter.exactPiecesOrNull(BigDecimal.ONE, null)).isNull();
    }
}
