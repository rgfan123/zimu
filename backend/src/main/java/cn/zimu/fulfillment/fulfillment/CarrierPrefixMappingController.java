package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.WriteCommands;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public management contract for Ticket 09's runtime carrier-prefix authority. */
@RestController
@RequestMapping("/api/v1/carrier-prefix-mappings")
@Validated
public class CarrierPrefixMappingController {

    private final CarrierPrefixMappingService service;
    private final ObjectMapper objectMapper;

    public CarrierPrefixMappingController(CarrierPrefixMappingService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public CarrierPrefixMappingView get() {
        return service.get();
    }

    @PutMapping
    public ResponseEntity<String> replace(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody CarrierPrefixMappingReplaceCommand command) {
        IdempotentResult<CarrierPrefixMappingView> result = service.replace(
                command,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator));
        JsonNode body = result.replayed()
                ? result.replayedBody()
                : objectMapper.valueToTree(result.result());
        return ResponseEntity.status(result.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(writeCanonical(body));
    }

    /** Both first execution and PostgreSQL JSONB replay pass through the same byte ordering. */
    private String writeCanonical(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonical(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Carrier 前缀映射响应序列化失败", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode sorted = objectMapper.createObjectNode();
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            value.forEach(item -> ordered.add(canonical(item)));
            return ordered;
        }
        return value;
    }
}
