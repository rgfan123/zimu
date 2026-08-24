package cn.zimu.fulfillment.connector.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** 稳定 JSON SHA-256：对象键排序，BigDecimal 去尾零，数组保持业务顺序。 */
@Component
public final class SourceSyncHash {

    private final ObjectMapper mapper;

    public SourceSyncHash(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(Object value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical(mapper.valueToTree(value)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (Exception exception) {
            throw new IllegalStateException("来源回传哈希序列化失败", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode result = mapper.createObjectNode();
            names.forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        if (value.isBigDecimal() || value.isFloatingPointNumber()) {
            BigDecimal normalized = value.decimalValue().stripTrailingZeros();
            return mapper.getNodeFactory().textNode(normalized.signum() == 0 ? "0" : normalized.toPlainString());
        }
        return value;
    }
}
