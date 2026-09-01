package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 京东销售出库写后读回的唯一事实核验器。
 *
 * <p>核验只消费提交意图已经冻结的非 PII 事实：商户/京东引用、查询授权 pin、ownerNo、
 * warehouseNo，以及 orderLine + goodsNo + planQuantity 货品集合。pin 用于发起同租户查询；
 * 远端租户回显以 customerInfo.ownerNo 为准（可选 pinAccount 是下单操作人，不是 pin 回显）。
 * 远端多行、少行、重复行、类型异常或任一事实漂移都不是“部分成功”，而是不可自动归属的对账事实。
 */
final class JdOutboundReadbackVerifier {

    private static final int MAX_TEXT_LENGTH = 128;
    private static final int MAX_CARGOS = 1_000;

    private JdOutboundReadbackVerifier() {
    }

    static Expected expected(JdShipmentSubmissionPlan plan, String jdDeliveryNo) {
        return frozen(
                plan.erpDeliveryNo(),
                jdDeliveryNo,
                plan.pin(),
                plan.ownerNo(),
                plan.request().get("warehouseNo"),
                plan.request().get("cargoInfos"));
    }

    /** 从数据库冻结列或首次提交计划构造同一严格事实，不允许调用方各写一套解析。 */
    static Expected frozen(
            Object erpDeliveryNo,
            String jdDeliveryNo,
            Object pin,
            Object ownerNo,
            Object warehouseNo,
            Object rawCargos) {
        List<Cargo> cargos = new ArrayList<>();
        if (rawCargos instanceof Collection<?> values && values.size() <= MAX_CARGOS) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> row)) {
                    return Expected.invalid(expectedText(erpDeliveryNo), jdDeliveryNo);
                }
                String orderLine = expectedText(row.get("orderLine"));
                String goodsNo = expectedText(row.get("goodsNo"));
                Integer planQuantity = exactPositiveInt(row.get("planQuantity"));
                if (orderLine == null || goodsNo == null || planQuantity == null) {
                    return Expected.invalid(expectedText(erpDeliveryNo), jdDeliveryNo);
                }
                cargos.add(new Cargo(orderLine, goodsNo, planQuantity));
            }
        } else {
            return Expected.invalid(expectedText(erpDeliveryNo), jdDeliveryNo);
        }
        String stableErpDeliveryNo = expectedText(erpDeliveryNo);
        String stablePin = expectedText(pin);
        String stableOwnerNo = expectedText(ownerNo);
        String stableWarehouseNo = expectedText(warehouseNo);
        return new Expected(
                stableErpDeliveryNo,
                jdDeliveryNo,
                stablePin,
                stableOwnerNo,
                stableWarehouseNo,
                List.copyOf(cargos),
                stableErpDeliveryNo != null
                        && stablePin != null
                        && stableOwnerNo != null
                        && stableWarehouseNo != null
                        && !cargos.isEmpty());
    }

    static Verification verify(Expected expected, JdResult result) {
        if (!expected.valid()) {
            return Verification.malformed(List.of("local_frozen_facts"));
        }
        if (result == null || !result.success()) {
            return Verification.queryFailed();
        }
        try {
            Map<String, Object> response = responseEnvelope(result.data());
            Set<String> mismatches = new LinkedHashSet<>();
            compare(mismatches, "erp_delivery_no", expected.erpDeliveryNo(),
                    requiredText(response.get("erpDeliveryNo")));
            String remoteDeliveryNo = requiredText(response.get("deliveryNo"));
            if (expected.jdDeliveryNo() != null) {
                compare(mismatches, "jd_delivery_no", expected.jdDeliveryNo(), remoteDeliveryNo);
            }
            Map<String, Object> customerInfo = requiredMap(response.get("customerInfo"));
            compare(mismatches, "owner_no", expected.ownerNo(),
                    requiredText(customerInfo.get("ownerNo")));
            compare(mismatches, "warehouse_no", expected.warehouseNo(),
                    requiredText(response.get("warehouseNo")));
            if (!cargoMatches(expected.cargos(), requiredListOfMaps(response.get("deliveryItemList")))) {
                mismatches.add("cargo");
            }
            return mismatches.isEmpty()
                    ? Verification.matched(remoteDeliveryNo)
                    : Verification.mismatched(List.copyOf(mismatches));
        } catch (MalformedRemoteResponse ignored) {
            return Verification.malformed(List.of("remote_response"));
        }
    }

    private static boolean cargoMatches(List<Cargo> expected, List<Map<String, Object>> actual) {
        if (expected.isEmpty() || expected.size() != actual.size()) {
            return false;
        }
        Map<String, Cargo> byKey = new LinkedHashMap<>();
        for (Cargo cargo : expected) {
            if (byKey.putIfAbsent(cargo.key(), cargo) != null) {
                return false;
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : actual) {
            String orderLine = requiredText(row.get("orderLine"));
            String goodsNo = requiredText(row.get("goodsNo"));
            Integer planQuantity = exactPositiveInt(row.get("planQuantity"));
            Cargo expectedCargo = byKey.get(Cargo.key(orderLine, goodsNo));
            if (expectedCargo == null
                    || planQuantity == null
                    || expectedCargo.planQuantity() != planQuantity
                    || !seen.add(expectedCargo.key())) {
                return false;
            }
        }
        return seen.size() == byKey.size();
    }

    private static void compare(Set<String> mismatches, String field, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(field);
        }
    }

    private static Map<String, Object> responseEnvelope(Object raw) {
        Map<String, Object> data = requiredMap(raw);
        return data.containsKey("response") ? requiredMap(data.get("response")) : data;
    }

    private static Map<String, Object> requiredMap(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw MalformedRemoteResponse.INSTANCE;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Map<String, Object>> requiredListOfMaps(Object raw) {
        if (!(raw instanceof Collection<?> values) || values.size() > MAX_CARGOS) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (Object value : values) {
            result.add(requiredMap(value));
        }
        return List.copyOf(result);
    }

    private static String requiredText(Object raw) {
        String result = strictText(raw);
        if (result == null) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        return result;
    }

    private static String expectedText(Object raw) {
        try {
            return strictText(raw);
        } catch (MalformedRemoteResponse ignored) {
            return null;
        }
    }

    private static String strictText(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        String result = value.trim();
        if (result.isEmpty()
                || result.length() > MAX_TEXT_LENGTH
                || result.codePoints().anyMatch(Character::isISOControl)) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        return result;
    }

    private static Integer exactPositiveInt(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return cn.zimu.fulfillment.common.domain.CountQuantity.fromPositiveFileValue(raw.toString());
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException ignored) {
            return null;
        }
    }

    record Expected(
            String erpDeliveryNo,
            String jdDeliveryNo,
            String pin,
            String ownerNo,
            String warehouseNo,
            List<Cargo> cargos,
            boolean valid) {

        Expected withJdDeliveryNo(String value) {
            return new Expected(erpDeliveryNo, value, pin, ownerNo, warehouseNo, cargos, valid);
        }

        boolean sameFrozenFacts(Expected other) {
            return other != null
                    && valid
                    && other.valid
                    && Objects.equals(erpDeliveryNo, other.erpDeliveryNo)
                    && Objects.equals(pin, other.pin)
                    && Objects.equals(ownerNo, other.ownerNo)
                    && Objects.equals(warehouseNo, other.warehouseNo)
                    && Objects.equals(cargos, other.cargos);
        }

        private static Expected invalid(String erpDeliveryNo, String jdDeliveryNo) {
            return new Expected(erpDeliveryNo, jdDeliveryNo, null, null, null, List.of(), false);
        }
    }

    record Cargo(String orderLine, String goodsNo, int planQuantity) {
        String key() {
            return key(orderLine, goodsNo);
        }

        private static String key(String orderLine, String goodsNo) {
            return orderLine + "\u0000" + goodsNo;
        }
    }

    enum Status {
        MATCHED,
        MISMATCHED,
        MALFORMED,
        QUERY_FAILED
    }

    record Verification(Status status, String deliveryNo, List<String> mismatchFields) {
        static Verification matched(String deliveryNo) {
            return new Verification(Status.MATCHED, deliveryNo, List.of());
        }

        static Verification mismatched(List<String> mismatchFields) {
            return new Verification(Status.MISMATCHED, null, List.copyOf(mismatchFields));
        }

        static Verification malformed(List<String> mismatchFields) {
            return new Verification(Status.MALFORMED, null, List.copyOf(mismatchFields));
        }

        static Verification queryFailed() {
            return new Verification(Status.QUERY_FAILED, null, List.of());
        }

        boolean matched() {
            return status == Status.MATCHED;
        }
    }

    private static final class MalformedRemoteResponse extends RuntimeException {
        private static final MalformedRemoteResponse INSTANCE = new MalformedRemoteResponse();

        private MalformedRemoteResponse() {
            super(null, null, false, false);
        }
    }
}
