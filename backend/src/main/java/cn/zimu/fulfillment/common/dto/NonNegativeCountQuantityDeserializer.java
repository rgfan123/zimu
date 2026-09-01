package cn.zimu.fulfillment.common.dto;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigInteger;

/** 仅接受非负整数 JSON token，适用于允许零的离散数量。 */
public final class NonNegativeCountQuantityDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Integer) context.handleUnexpectedToken(
                    Integer.class, parser.currentToken(), parser, "CountQuantity 必须是非负整数 JSON 值");
        }

        BigInteger raw = parser.getBigIntegerValue();
        try {
            return CountQuantity.fromNonNegativeJsonInteger(raw);
        } catch (InvalidCountQuantityException exception) {
            return (Integer) context.handleWeirdNumberValue(
                    Integer.class, raw, exception.getMessage());
        }
    }
}
