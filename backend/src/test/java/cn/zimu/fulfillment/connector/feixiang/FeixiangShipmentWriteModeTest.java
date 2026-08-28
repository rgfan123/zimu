package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 写门闩取值解析：宽进严出，配置写错一律回落 OFF，绝不意外开火。 */
class FeixiangShipmentWriteModeTest {

    @Test
    void unsetOrUnknownValuesFallBackToOff() {
        assertThat(FeixiangShipmentWriteMode.parse(null)).isEqualTo(FeixiangShipmentWriteMode.OFF);
        assertThat(FeixiangShipmentWriteMode.parse("")).isEqualTo(FeixiangShipmentWriteMode.OFF);
        assertThat(FeixiangShipmentWriteMode.parse("true")).isEqualTo(FeixiangShipmentWriteMode.OFF);
        assertThat(FeixiangShipmentWriteMode.parse("enabled")).isEqualTo(FeixiangShipmentWriteMode.OFF);
    }

    @Test
    void knownValuesAreCaseInsensitiveAndTrimmed() {
        assertThat(FeixiangShipmentWriteMode.parse(" on ")).isEqualTo(FeixiangShipmentWriteMode.ON);
        assertThat(FeixiangShipmentWriteMode.parse("dry_run")).isEqualTo(FeixiangShipmentWriteMode.DRY_RUN);
        assertThat(FeixiangShipmentWriteMode.parse("Armed")).isEqualTo(FeixiangShipmentWriteMode.ARMED);
    }

    @Test
    void onlyArmedAndOnAreCapableAndEmitWrites() {
        assertThat(FeixiangShipmentWriteMode.OFF.pushCapable()).isFalse();
        assertThat(FeixiangShipmentWriteMode.DRY_RUN.pushCapable()).isFalse();
        assertThat(FeixiangShipmentWriteMode.ARMED.pushCapable()).isTrue();
        assertThat(FeixiangShipmentWriteMode.ON.pushCapable()).isTrue();

        assertThat(FeixiangShipmentWriteMode.DRY_RUN.emitsExternalWrite()).isFalse();
        assertThat(FeixiangShipmentWriteMode.ARMED.firstWriteOnly()).isTrue();
        assertThat(FeixiangShipmentWriteMode.ON.firstWriteOnly()).isFalse();
    }
}
