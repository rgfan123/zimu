package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Validates the explicit, human-authorized Kehuzx write contract without deriving values. */
final class BusinessFollowUpExecutionPlan {

    private static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> SAMPLE_FIELDS = Set.of(
            "sample_name", "product_name", "quantity_per_unit", "quantity_unit", "unit_count",
            "requested_date", "expected_delivery_date", "testing_date", "specification",
            "requirements", "remark", "business_note", "commercial_terms");
    private static final Set<String> FORMAL_FIELDS = Set.of(
            "order_type", "name", "delivery_date", "delivery_address", "settlement_period",
            "settlement_method", "business_note", "commercial_terms", "items");
    private static final Set<String> FORMAL_ITEM_FIELDS = Set.of(
            "product_name", "quantity_per_unit", "quantity_unit", "unit_count");
    private static final Set<String> COMMERCIAL_TERM_FIELDS = Set.of(
            "payment_terms",
            "reconciliation_date",
            "payment_date",
            "credit_days",
            "invoice_requirement",
            "moq",
            "quoted_price",
            "target_price",
            "remark");

    private BusinessFollowUpExecutionPlan() {}

    static JsonNode validate(BusinessFollowUpBusinessKind kind, JsonNode raw) {
        if (kind == BusinessFollowUpBusinessKind.CUSTOMER) {
            if (raw != null && !raw.isNull()) {
                invalid("CUSTOMER 跟进不允许携带 execution_plan");
            }
            return null;
        }
        if (raw == null || !raw.isObject()) {
            invalid("SAMPLE/FORMAL 必须携带结构化 execution_plan");
        }
        if (raw.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            invalid("execution_plan 不能超过 64 KiB");
        }
        if (kind == BusinessFollowUpBusinessKind.SAMPLE) {
            validateSample(raw);
        } else {
            validateFormal(raw);
        }
        return raw.deepCopy();
    }

    private static void validateSample(JsonNode plan) {
        rejectUnknownFields(plan, SAMPLE_FIELDS, "SAMPLE execution_plan");
        requiredText(plan, "sample_name", 200);
        requiredText(plan, "product_name", 200);
        positiveDecimal(plan, "quantity_per_unit");
        requiredText(plan, "quantity_unit", 30);
        positiveInteger(plan, "unit_count");
        isoDate(plan, "requested_date", true);
        isoDate(plan, "expected_delivery_date", false);
        isoDate(plan, "testing_date", false);
        optionalText(plan, "specification");
        optionalText(plan, "requirements");
        optionalText(plan, "remark");
        optionalText(plan, "business_note");
        commercialTerms(plan.path("commercial_terms"));
    }

    private static void validateFormal(JsonNode plan) {
        rejectUnknownFields(plan, FORMAL_FIELDS, "FORMAL execution_plan");
        if (!plan.path("order_type").isTextual()
                || !"formal".equals(plan.path("order_type").asText())) {
            invalid("FORMAL execution_plan.order_type 必须严格为 formal");
        }
        requiredText(plan, "name", 200);
        isoDate(plan, "delivery_date", true);
        requiredText(plan, "delivery_address", 500);
        optionalText(plan, "settlement_period", 100);
        optionalText(plan, "settlement_method", 100);
        optionalText(plan, "business_note");
        commercialTerms(plan.path("commercial_terms"));
        JsonNode items = plan.path("items");
        if (!items.isArray() || items.isEmpty() || items.size() > 500) {
            invalid("FORMAL execution_plan.items 必须是 1..500 条的数组");
        }
        for (JsonNode item : items) {
            if (!item.isObject()) {
                invalid("FORMAL execution_plan.items 每一项必须是对象");
            }
            rejectUnknownFields(item, FORMAL_ITEM_FIELDS, "FORMAL execution_plan.items");
            requiredText(item, "product_name", 200);
            positiveDecimal(item, "quantity_per_unit");
            requiredText(item, "quantity_unit", 30);
            positiveInteger(item, "unit_count");
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String context) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                invalid(context + " 包含未授权字段: " + field);
            }
        });
    }

    private static void commercialTerms(JsonNode terms) {
        if (terms.isMissingNode()) {
            return;
        }
        if (terms.isNull()) {
            invalid("commercial_terms 不允许显式 null");
        }
        if (!terms.isObject() || terms.isEmpty()) {
            invalid("commercial_terms 必须是非空对象");
        }
        terms.fieldNames().forEachRemaining(field -> {
            if (!COMMERCIAL_TERM_FIELDS.contains(field)) {
                invalid("commercial_terms 包含未授权字段: " + field);
            }
            optionalText(terms, field);
        });
    }

    private static void requiredText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            invalid(field + " 必须是 1.." + maxLength + " 字符的非空文本");
        }
    }

    private static void optionalText(JsonNode node, String field) {
        optionalText(node, field, 4_000);
    }

    private static void optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) {
            return;
        }
        if (value.isNull()) {
            invalid(field + " 不允许显式 null");
        }
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            invalid(field + " 必须是 1.." + maxLength + " 字符的非空文本");
        }
    }

    private static void positiveDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            invalid(field + " 必须是 NUMERIC(14,3) 范围内的正数");
        }
        BigDecimal number;
        try {
            number = value.decimalValue().stripTrailingZeros();
        } catch (ArithmeticException | NumberFormatException invalidNumber) {
            invalid(field + " 必须是有限的 NUMERIC(14,3) 正数");
            return;
        }
        if (number.signum() <= 0
                || number.scale() > 3
                || number.compareTo(new BigDecimal("99999999999.999")) > 0) {
            invalid(field + " 必须是 NUMERIC(14,3) 范围内且最多 3 位小数的正数");
        }
    }

    private static void positiveInteger(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            invalid(field + " 必须是大于 0 的整数");
        }
    }

    private static void isoDate(JsonNode node, String field, boolean required) {
        JsonNode value = node.path(field);
        if (!required && value.isMissingNode()) {
            return;
        }
        if (value.isNull()) {
            invalid(field + " 不允许显式 null");
        }
        if (!value.isTextual()) {
            invalid(field + " 必须是 ISO-8601 日期");
        }
        try {
            LocalDate.parse(value.asText());
        } catch (DateTimeParseException ex) {
            invalid(field + " 必须是 ISO-8601 日期");
        }
    }

    private static void invalid(String message) {
        throw BusinessException.badRequest("FOLLOWUP_EXECUTION_PLAN_INVALID", message);
    }
}
