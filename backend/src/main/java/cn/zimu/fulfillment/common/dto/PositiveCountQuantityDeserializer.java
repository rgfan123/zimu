package cn.zimu.fulfillment.common.dto;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigInteger;

/** 仅接受正整数 JSON token，禁止 Jackson 把字符串或小数静默强制为整数。 */
public final class PositiveCountQuantityDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Integer) context.handleUnexpectedToken(
                    Integer.class, parser.currentToken(), parser, "CountQuantity 必须是正整数 JSON 值");
        }

        BigInteger raw = parser.getBigIntegerValue();
        try {
            return CountQuantity.fromPositiveJsonInteger(raw);
        } catch (InvalidCountQuantityException exception) {
            return (Integer) context.handleWeirdNumberValue(
                    Integer.class, raw, exception.getMessage());
        }
    }
}
