package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JdStockUnitConverterTest {

    @Test
    void requiredPiecesUsesExactIntegerMultiplicationWithoutRounding() {
        assertThat(JdStockUnitConverter.requiredPieces(2L, 1)).isEqualTo(2L);
        assertThat(JdStockUnitConverter.requiredPieces(3L, 2)).isEqualTo(6L);
    }

    @Test
    void factorDefaultsToOneAndParsesJsonValueShapes() {
        assertThat(JdStockUnitConverter.factorOrNull(null)).isEqualTo(1);
        assertThat(JdStockUnitConverter.factorOrNull(Map.of())).isEqualTo(1);
        assertThat(JdStockUnitConverter.factorOrNull(Map.of("other_key", 2))).isEqualTo(1);
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, "2.000")))
                .isEqualTo(2);
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 1L)))
                .isEqualTo(1);
    }

    @Test
    void invalidFactorConfigurationIsRejectedAsUnparseable() {
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, -1))).isNull();
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0))).isNull();
        assertThat(JdStockUnitConverter.factorOrNull(Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0.5))).isNull();
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
                .isEqualTo(2);
        assertThat(JdStockUnitConverter.explicitFactorOrNull(
                Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0))).isNull();
    }

    @Test
    void exactPiecesRejectsNonPositiveMissingAndOverflowValues() {
        assertThat(JdStockUnitConverter.exactPiecesOrNull(3L, 2)).isEqualTo(6L);
        assertThat(JdStockUnitConverter.exactPiecesOrNull(0L, 1)).isNull();
        assertThat(JdStockUnitConverter.exactPiecesOrNull(null, 1)).isNull();
        assertThat(JdStockUnitConverter.exactPiecesOrNull(1L, null)).isNull();
        assertThat(JdStockUnitConverter.exactPiecesOrNull(Long.MAX_VALUE, 2)).isNull();
    }

    @Test
    void outboundValidationDistinguishesMissingFractionalAndValidFactors() {
        assertThat(JdStockUnitConverter.validateOutboundFactor("件", Map.of()).status())
                .isEqualTo(JdStockUnitConverter.OutboundFactorStatus.DEFAULT_ONE);
        assertThat(JdStockUnitConverter.validateOutboundFactor("箱", Map.of()).status())
                .isEqualTo(JdStockUnitConverter.OutboundFactorStatus.MISSING);
        assertThat(JdStockUnitConverter.validateOutboundFactor(
                        "箱", Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, 0.5)).status())
                .isEqualTo(JdStockUnitConverter.OutboundFactorStatus.NON_INTEGER);
        assertThat(JdStockUnitConverter.validateOutboundFactor(
                        "箱", Map.of(JdStockUnitConverter.FACTOR_CONFIG_KEY, "3.000")).factor())
                .isEqualTo(3);
    }
}
