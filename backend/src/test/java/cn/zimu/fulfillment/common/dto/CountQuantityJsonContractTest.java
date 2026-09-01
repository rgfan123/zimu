package cn.zimu.fulfillment.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.Test;

class CountQuantityJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyIntegerTokensAtPositiveAndNonNegativeCountBoundaries() throws Exception {
        Payload payload = mapper.readValue("{\"positive\":3,\"nonNegative\":0}", Payload.class);

        assertThat(payload.positive()).isEqualTo(3);
        assertThat(payload.nonNegative()).isZero();
    }

    @Test
    void rejectsStringsDecimalsNegativeValuesAndInt32OverflowWithoutCoercion() {
        for (String json : new String[] {
                "{\"positive\":\"3\",\"nonNegative\":0}",
                "{\"positive\":3.000,\"nonNegative\":0}",
                "{\"positive\":3,\"nonNegative\":\"0\"}",
                "{\"positive\":3,\"nonNegative\":0.0}",
                "{\"positive\":0,\"nonNegative\":0}",
                "{\"positive\":3,\"nonNegative\":-1}",
                "{\"positive\":2147483648,\"nonNegative\":0}",
                "{\"positive\":3,\"nonNegative\":2147483648}"
        }) {
            assertThatThrownBy(() -> mapper.readValue(json, Payload.class)).as(json).isInstanceOf(Exception.class);
        }
    }

    @Test
    void countProductsStayInt32AndReportOverflowAsADomainValidationError() {
        assertThat(CountQuantity.multiplyPositive(2, 3)).isEqualTo(6);

        assertThatThrownBy(() -> CountQuantity.multiplyPositive(2, Integer.MAX_VALUE))
                .isInstanceOf(CountQuantity.InvalidCountQuantityException.class)
                .satisfies(error -> assertThat(((CountQuantity.InvalidCountQuantityException) error).reason())
                        .isEqualTo(CountQuantity.InvalidReason.OUT_OF_RANGE));
    }

    private record Payload(
            @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer positive,
            @JsonDeserialize(using = NonNegativeCountQuantityDeserializer.class) Integer nonNegative) {}
}
