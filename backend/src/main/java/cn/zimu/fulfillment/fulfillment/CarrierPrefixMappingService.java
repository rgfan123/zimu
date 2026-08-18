package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Versioned, idempotent and audited use case for runtime carrier-prefix maintenance. */
@Service
public class CarrierPrefixMappingService {

    private static final String SCOPE = "carrier_prefix_mapping.replace";
    private static final String OPERATION = "carrier_prefix_mapping.replace";
    private static final String SERVICE = "carrier-prefix-mapping";

    private final JdbcTemplate jdbc;
    private final CarrierPrefixProperties carriers;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final TransactionTemplate requiresNew;

    public CarrierPrefixMappingService(
            JdbcTemplate jdbc,
            CarrierPrefixProperties carriers,
            IdempotencyService idempotency,
            AuditLogService audits,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.carriers = carriers;
        this.idempotency = idempotency;
        this.audits = audits;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public CarrierPrefixMappingView get() {
        // 单语句快照：version 与 mappings 在同一条 SQL 的同一语句快照中读取，并发写提交前后保持一致。
        return jdbc.query(
                """
                SELECT s.lock_version, m.prefix, m.carrier_code
                FROM app.carrier_prefix_mapping_sets s
                LEFT JOIN app.carrier_prefix_mappings m ON TRUE
                WHERE s.singleton_id=1
                ORDER BY m.prefix, m.carrier_code
                """,
                resultSet -> {
                    Long version = null;
                    List<CarrierPrefixMappingEntry> mappings = new ArrayList<>();
                    while (resultSet.next()) {
                        if (version == null) {
                            long value = resultSet.getLong("lock_version");
                            version = resultSet.wasNull() ? null : value;
                        }
                        String prefix = resultSet.getString("prefix");
                        if (prefix != null) {
                            mappings.add(new CarrierPrefixMappingEntry(
                                    prefix, resultSet.getString("carrier_code")));
                        }
                    }
                    return new CarrierPrefixMappingView(version == null ? 0 : version, List.copyOf(mappings));
                });
    }

    @Transactional
    public IdempotentResult<CarrierPrefixMappingView> replace(
            CarrierPrefixMappingReplaceCommand command,
            String idempotencyKey,
            CommandContext context) {
        requireAuthenticatedOperator(command, context);
        List<CarrierPrefixMappingEntry> canonical = canonicalMappings(command.mappings());
        Map<String, Object> idempotencyPayload = new LinkedHashMap<>();
        idempotencyPayload.put("expected_version", command.expectedVersion());
        idempotencyPayload.put("mappings", canonical);
        return idempotency.execute(
                SCOPE,
                idempotencyKey,
                idempotencyPayload,
                200,
                () -> apply(command.expectedVersion(), canonical, context));
    }

    private CarrierPrefixMappingView apply(
            long expectedVersion,
            List<CarrierPrefixMappingEntry> mappings,
            CommandContext context) {
        CarrierPrefixMappingView before = getForUpdate();
        if (before.version() != expectedVersion) {
            throw BusinessException.conflict("VERSION_CONFLICT", "Carrier 前缀映射版本已变化，请刷新后重试");
        }
        int updated = jdbc.update(
                """
                UPDATE app.carrier_prefix_mapping_sets
                SET lock_version=lock_version+1, updated_by=?, updated_at=CURRENT_TIMESTAMP
                WHERE singleton_id=1 AND lock_version=?
                """,
                context.operator(),
                expectedVersion);
        if (updated != 1) {
            throw BusinessException.conflict("VERSION_CONFLICT", "Carrier 前缀映射版本已变化，请刷新后重试");
        }
        jdbc.update("DELETE FROM app.carrier_prefix_mappings");
        jdbc.batchUpdate(
                "INSERT INTO app.carrier_prefix_mappings(prefix, carrier_code) VALUES (?, ?)",
                mappings,
                mappings.size(),
                (statement, mapping) -> {
                    statement.setString(1, mapping.prefix());
                    statement.setString(2, mapping.carrierCode());
                });

        CarrierPrefixMappingView result = get();
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("old_version", before.version());
        auditPayload.put("new_version", result.version());
        auditPayload.put("rule_count", result.mappings().size());
        auditPayload.put("change_summary", changeSummary(before.mappings(), result.mappings()));
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service(SERVICE)
                .operation(OPERATION)
                .requestPayload(auditPayload)
                .responsePayload(result)
                .httpStatus(200)
                .businessCode("CARRIER_PREFIX_MAPPINGS_REPLACED"));
        return result;
    }

    private CarrierPrefixMappingView getForUpdate() {
        Long version = jdbc.queryForObject(
                "SELECT lock_version FROM app.carrier_prefix_mapping_sets WHERE singleton_id=1 FOR UPDATE",
                Long.class);
        List<CarrierPrefixMappingEntry> mappings = jdbc.query(
                "SELECT prefix, carrier_code FROM app.carrier_prefix_mappings ORDER BY prefix, carrier_code",
                (resultSet, rowNum) -> new CarrierPrefixMappingEntry(
                        resultSet.getString("prefix"), resultSet.getString("carrier_code")));
        return new CarrierPrefixMappingView(version == null ? 0 : version, List.copyOf(mappings));
    }

    private List<CarrierPrefixMappingEntry> canonicalMappings(List<CarrierPrefixMappingEntry> requested) {
        if (requested == null || requested.size() > 200) {
            throw BusinessException.badRequest(
                    "CARRIER_PREFIX_MAPPING_COUNT_INVALID", "Carrier 前缀映射必须包含 0至200 条规则");
        }
        Map<String, String> byPrefix = new TreeMap<>();
        for (CarrierPrefixMappingEntry entry : requested) {
            if (entry == null) {
                throw BusinessException.badRequest("CARRIER_PREFIX_MAPPING_INVALID", "Carrier 前缀映射不能为空");
            }
            String prefix = normalize(entry.prefix());
            String carrierCode = normalize(entry.carrierCode());
            if (!prefix.matches("^[A-Z]{1,16}$")) {
                throw BusinessException.badRequest(
                        "CARRIER_PREFIX_INVALID", "Carrier 前缀必须为 1至16 位英文字母");
            }
            requireEnabledCarrier(carrierCode);
            String existing = byPrefix.putIfAbsent(prefix, carrierCode);
            if (existing != null && !existing.equals(carrierCode)) {
                throw BusinessException.badRequest(
                        "CARRIER_PREFIX_DUPLICATE", "归一化后的同一前缀不能指向不同 Carrier");
            }
        }
        return byPrefix.entrySet().stream()
                .map(entry -> new CarrierPrefixMappingEntry(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void requireEnabledCarrier(String carrierCode) {
        CarrierPrefixProperties.CarrierEntry carrier = carriers.getCarriers().entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(carrierCode))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (carrier == null || !carrier.isEnabled()) {
            throw BusinessException.badRequest(
                    "CARRIER_NOT_ENABLED", "Carrier 前缀映射只能指向已启用的 Carrier 主数据");
        }
    }

    private static Map<String, Object> changeSummary(
            List<CarrierPrefixMappingEntry> before,
            List<CarrierPrefixMappingEntry> after) {
        Set<String> oldRules = ruleSet(before);
        Set<String> newRules = ruleSet(after);
        List<String> added = newRules.stream().filter(rule -> !oldRules.contains(rule)).toList();
        List<String> removed = oldRules.stream().filter(rule -> !newRules.contains(rule)).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("added", added);
        summary.put("removed", removed);
        return summary;
    }

    private static Set<String> ruleSet(List<CarrierPrefixMappingEntry> mappings) {
        Set<String> rules = new LinkedHashSet<>();
        mappings.forEach(mapping -> rules.add(mapping.prefix() + "=" + mapping.carrierCode()));
        return rules;
    }

    private void requireAuthenticatedOperator(
            CarrierPrefixMappingReplaceCommand command,
            CommandContext context) {
        if (context.authenticatedOperator() != null
                && Objects.equals(context.authenticatedOperator(), context.operator())) {
            return;
        }
        String businessCode = "CARRIER_PREFIX_OPERATOR_UNAUTHORIZED";
        try {
            requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.authenticatedOperator() == null ? "unauthenticated" : context.authenticatedOperator())
                    .actorType(AuditActorType.HUMAN)
                    .service(SERVICE)
                    .operation(OPERATION)
                    .requestPayload(Map.of("expected_version", command.expectedVersion()))
                    .httpStatus(403)
                    .businessCode(businessCode)));
        } catch (RuntimeException ignored) {
            // 拒绝审计失败不能覆盖原始授权拒绝。
        }
        throw new BusinessException(403, businessCode, "Carrier 前缀映射必须由服务端已认证且身份一致的操作员维护");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
