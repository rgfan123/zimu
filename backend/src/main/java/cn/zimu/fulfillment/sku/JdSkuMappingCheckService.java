package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.CreateOperationalAlertCommand;
import cn.zimu.fulfillment.order.OperationalAlertService;
import cn.zimu.fulfillment.order.OperationalAlertSeverity;
import cn.zimu.fulfillment.order.dto.OperationalAlertDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 京东云仓 SKU 映射核对：拉取京东履约方的 provider_skus 映射，逐条调 queryGoodsInfo 核对，
 * 差异按 映射缺失/商品失效/名称不符 三类归类，每类差异写一条 JD_SKU_MAPPING 运营告警；
 * 只有 REAL 客户端返回同一、已启用 goodsNo 时，才在内部映射上固化可复核的核验凭证。
 *
 * <p>映射键语义（M3）：provider_skus.provider_sku_code ↔ 京东 goodsNo（京东商品编码）；
 * provider_skus.merchant_sku_code ↔ 京东 erpGoodsNo（商家 ERP 商品编码）；external_codes 存放其它京东侧编码。
 *
 * <p>当前 app.operational_alerts 的 CHECK 要求 order_id/order_line_id/fulfillment_id/shipment_id
 * 至少一个非空，而 SKU 映射告警无订单主体，因此本服务在无主体时降级到审计通道并在结果中标记
 * alertPersisted=false；等 schema 放宽（V10）后放开 emit 中的开关即可恢复告警落库。
 */
@Service
public class JdSkuMappingCheckService {

    private static final String SCOPE = "jd_sku_mapping.check";
    private static final String JD_PROVIDER_CODE = "JD";
    private static final String ALERT_TYPE = "JD_SKU_MAPPING";

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final JdGoodsReadOnlyVerifier goodsVerifier;
    private final OperationalAlertService alerts;
    private final String clientMode;

    public JdSkuMappingCheckService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuditLogService audits,
            JdGoodsReadOnlyVerifier goodsVerifier,
            OperationalAlertService alerts,
            @Value("${app.jd.client-mode:MOCK}") String clientMode) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audits = audits;
        this.goodsVerifier = goodsVerifier;
        this.alerts = alerts;
        this.clientMode = clientMode == null ? "MOCK" : clientMode.trim().toUpperCase(Locale.ROOT);
    }

    /** 手动触发一次核对；同一幂等键重放返回首次结果，不重复写告警。 */
    public IdempotentResult<JdSkuMappingCheckResult> run(String idempotencyKey, CommandContext context) {
        long providerId = resolveJdProviderId();
        List<SkuMappingRow> mappings = loadMappings(providerId);
        Map<Long, JdGoodsReadOnlyVerifier.Verification> remoteFacts = new LinkedHashMap<>();
        for (SkuMappingRow mapping : mappings) {
            remoteFacts.put(mapping.id(), goodsVerifier.verify(mapping.providerSkuCode()));
        }
        return idempotency.execute(
                SCOPE, idempotencyKey, Map.of("provider_code", JD_PROVIDER_CODE), 200,
                () -> check(mappings, remoteFacts, context));
    }

    private JdSkuMappingCheckResult check(
            List<SkuMappingRow> mappings,
            Map<Long, JdGoodsReadOnlyVerifier.Verification> remoteFacts,
            CommandContext context) {
        String checkRunNo = "CHK-" + token();
        List<DiffItem> missing = new ArrayList<>();
        List<DiffItem> invalid = new ArrayList<>();
        List<DiffItem> statusUnknown = new ArrayList<>();
        List<DiffItem> nameMismatch = new ArrayList<>();
        for (SkuMappingRow mapping : mappings) {
            JdGoodsReadOnlyVerifier.Verification verification = remoteFacts.get(mapping.id());
            recordVerification(mapping, verification);
            classify(mapping, verification, missing, invalid, statusUnknown, nameMismatch);
        }
        List<CategoryDiff> categories = new ArrayList<>();
        emit(checkRunNo, DiffCategory.MAPPING_MISSING, missing, context, categories);
        emit(checkRunNo, DiffCategory.GOODS_INVALID, invalid, context, categories);
        emit(checkRunNo, DiffCategory.GOODS_STATUS_UNKNOWN, statusUnknown, context, categories);
        emit(checkRunNo, DiffCategory.NAME_MISMATCH, nameMismatch, context, categories);
        JdSkuMappingCheckResult result =
                new JdSkuMappingCheckResult(checkRunNo, JD_PROVIDER_CODE, mappings.size(), categories);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("JdSkuMappingCheckService")
                .operation(SCOPE)
                .requestPayload(Map.of("provider_code", JD_PROVIDER_CODE, "mapping_count", mappings.size()))
                .responsePayload(result)
                .httpStatus(200)
                .businessCode(categories.isEmpty() ? "JD_SKU_MAPPING_CONSISTENT" : "JD_SKU_MAPPING_DIFF_FOUND"));
        return result;
    }

    /**
     * REAL 客户端返回同一、已启用 goodsNo 时固化凭证；后续 REAL 成功查询给出未找到、
     * goodsNo 漂移或明确未启用（enableFlag=1）时撤销旧凭证。传输失败、状态缺失和
     * 未文档化状态不冒充权威否定。
     */
    private void recordVerification(
            SkuMappingRow mapping, JdGoodsReadOnlyVerifier.Verification verification) {
        if (JdGoodsVerificationEvidence.canRecord(clientMode, mapping.providerSkuCode(), verification)) {
            persistPositiveVerification(mapping, verification);
            return;
        }
        if (!JdGoodsVerificationEvidence.shouldRevoke(
                clientMode, mapping.providerSkuCode(), verification)) {
            return;
        }
        revokeVerification(mapping);
    }

    private void persistPositiveVerification(
            SkuMappingRow mapping, JdGoodsReadOnlyVerifier.Verification verification) {
        int updated = jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(
                        external_codes,
                        '{jd_goods_verification}',
                        jsonb_build_object(
                            'goods_no', ?,
                            'source', ?,
                            'client_mode', ?,
                            'enable_flag', ?,
                            'verified_at', CURRENT_TIMESTAMP,
                            'request_id', ?),
                        TRUE),
                    lock_version=lock_version+1,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND active=TRUE AND provider_sku_code=?
                """,
                verification.goodsNo(),
                JdGoodsVerificationEvidence.SOURCE,
                clientMode,
                verification.enableFlag(),
                verification.requestId(),
                mapping.id(),
                mapping.providerSkuCode());
        if (updated != 1) {
            throw mappingChangedDuringCheck();
        }
    }

    private void revokeVerification(SkuMappingRow mapping) {
        int updated = jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes = external_codes - 'jd_goods_verification',
                    lock_version=lock_version+1,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND active=TRUE AND provider_sku_code=?
                  AND jsonb_exists(external_codes, 'jd_goods_verification')
                """,
                mapping.id(),
                mapping.providerSkuCode());
        if (updated == 1) {
            return;
        }
        Boolean mappingUnchanged = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app.provider_skus "
                        + "WHERE id=? AND active=TRUE AND provider_sku_code=?)",
                Boolean.class,
                mapping.id(),
                mapping.providerSkuCode());
        if (!Boolean.TRUE.equals(mappingUnchanged)) {
            throw mappingChangedDuringCheck();
        }
    }

    private BusinessException mappingChangedDuringCheck() {
        return BusinessException.conflict(
                "JD_SKU_MAPPING_CHANGED_DURING_CHECK",
                "京东商品只读核验期间映射已变化，请刷新后重新核验");
    }

    private void classify(
            SkuMappingRow mapping,
            JdGoodsReadOnlyVerifier.Verification result,
            List<DiffItem> missing,
            List<DiffItem> invalid,
            List<DiffItem> statusUnknown,
            List<DiffItem> nameMismatch) {
        if (!result.querySucceeded()) {
            missing.add(diff(mapping, "QUERY_FAILED",
                    "京东商品查询失败（" + result.businessCode() + "），无法核对映射"));
            return;
        }
        if (!result.found()) {
            missing.add(diff(mapping, "NOT_FOUND", "京东未查到该商品编码（goodsNo）对应的商品"));
            return;
        }
        if (!Objects.equals(mapping.providerSkuCode(), result.goodsNo())) {
            missing.add(diff(mapping, "GOODS_NO_MISMATCH",
                    "京东返回 goodsNo=" + result.goodsNo()
                            + "，与系统映射 " + mapping.providerSkuCode() + " 不一致"));
            return;
        }
        // enableFlag 官方语义（快照 2026-08-11，docs/research/jdl-api-367/json/1610-queryGoodsInfo.json）：
        // 「启用标志，1：未启用，2：启用」。京东 ISC 惯例同为 1 否 / 2 是（同文档 storeSaleFlag、afterSaleFlag）。
        // 官方未定义 0；未文档化取值只告警不判失效（fail-open），真正的硬关卡是 queryStock 库存校验。
        if (result.enableFlag() == null) {
            invalid.add(diff(mapping, "STATUS_MISSING", "京东商品响应缺少可用状态"));
            return;
        }
        if (result.enableFlag() == 1) {
            invalid.add(diff(mapping, "DISABLED", "京东商品未启用（enableFlag=" + result.enableFlag() + "）"));
            return;
        }
        if (result.enableFlag() != 2) {
            statusUnknown.add(diff(mapping, "STATUS_UNKNOWN",
                    "京东返回未文档化的启用标志（enableFlag=" + result.enableFlag()
                            + "），官方仅定义 1=未启用/2=启用；仅提示，不判失效"));
            return;
        }
        String goodsName = result.goodsName();
        if (goodsName != null && !nameMatches(mapping, goodsName)) {
            nameMismatch.add(diff(mapping, "NAME_MISMATCH",
                    "京东商品名『" + goodsName + "』与系统名称（规格/履约方名称）不一致"));
        }
    }

    /** 每类差异写一条告警；无订单侧主体时降级到审计通道（告警结果随本次运行审计落审计日志）。 */
    private void emit(
            String checkRunNo,
            DiffCategory category,
            List<DiffItem> items,
            CommandContext context,
            List<CategoryDiff> output) {
        if (items.isEmpty()) {
            return;
        }
        String message = String.format(category.messageTemplate(), items.size());
        CreateOperationalAlertCommand command = new CreateOperationalAlertCommand(
                ALERT_TYPE, OperationalAlertSeverity.YELLOW,
                null, null, null, null,
                message,
                alertDetail(checkRunNo, category, items));
        if (!hasBusinessSubject(command)) {
            // 无订单侧主体：app.operational_alerts 的 CHECK 要求至少一个主体非空，SKU 映射告警暂不能落库；
            // 差异已完整记录在本次运行结果与审计中。schema 放宽（V10）后放开此处即可恢复告警落库。
            output.add(new CategoryDiff(category.name(), message, false, "AUDIT", null,
                    "operational_alerts 要求订单侧主体，SKU 映射告警无主体，已降级审计通道", items));
            return;
        }
        IdempotentResult<OperationalAlertDto> created =
                alerts.create(command, checkRunNo + ":" + category.name(), context);
        output.add(new CategoryDiff(category.name(), message, true, "OPERATIONAL_ALERTS",
                created.result().alertNo(), null, items));
    }

    static boolean hasBusinessSubject(CreateOperationalAlertCommand command) {
        return command != null && (command.orderId() != null || command.orderLineId() != null
                || command.fulfillmentId() != null || command.shipmentId() != null);
    }

    private long resolveJdProviderId() {
        List<Long> ids = jdbc.query(
                "SELECT id FROM app.fulfillment_providers "
                        + "WHERE provider_code=? AND provider_type='JD_WAREHOUSE' AND active",
                (rs, rowNum) -> rs.getLong(1),
                JD_PROVIDER_CODE);
        if (ids.isEmpty()) {
            throw BusinessException.notFound("未找到启用的京东云仓履约方（provider_code=JD）");
        }
        return ids.getFirst();
    }

    private List<SkuMappingRow> loadMappings(long providerId) {
        return jdbc.query(
                """
                SELECT ps.id, ps.sku_id, ps.provider_sku_code, ps.merchant_sku_code,
                       ps.external_codes->>'provider_sku_name' AS provider_sku_name,
                       s.sku_code, s.specification
                FROM app.provider_skus ps
                JOIN app.skus s ON s.id=ps.sku_id
                WHERE ps.fulfillment_provider_id=? AND ps.active
                ORDER BY ps.id
                """,
                (rs, rowNum) -> new SkuMappingRow(
                        rs.getLong("id"),
                        rs.getLong("sku_id"),
                        rs.getString("provider_sku_code"),
                        rs.getString("merchant_sku_code"),
                        rs.getString("provider_sku_name"),
                        rs.getString("sku_code"),
                        rs.getString("specification")),
                providerId);
    }

    /** 名称比对：系统侧参照名（规格 / external_codes.provider_sku_name）任一命中即视为一致。 */
    private boolean nameMatches(SkuMappingRow mapping, String jdGoodsName) {
        String normalizedJd = normalize(jdGoodsName);
        List<String> references = new ArrayList<>();
        if (mapping.specification() != null && !mapping.specification().isBlank()) {
            references.add(mapping.specification());
        }
        if (mapping.providerSkuName() != null && !mapping.providerSkuName().isBlank()) {
            references.add(mapping.providerSkuName());
        }
        if (references.isEmpty()) {
            return true; // 系统侧无参照名，不做名称比对，避免误报
        }
        for (String reference : references) {
            String normalizedReference = normalize(reference);
            if (normalizedReference.isEmpty()) {
                continue;
            }
            if (normalizedJd.equals(normalizedReference)
                    || normalizedJd.contains(normalizedReference)
                    || normalizedReference.contains(normalizedJd)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> alertDetail(String checkRunNo, DiffCategory category, List<DiffItem> items) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("check_run_no", checkRunNo);
        detail.put("category", category.name());
        detail.put("provider_code", JD_PROVIDER_CODE);
        detail.put("diff_count", items.size());
        detail.put("diffs", items);
        return detail;
    }

    private DiffItem diff(SkuMappingRow mapping, String reason, String message) {
        return new DiffItem(
                String.valueOf(mapping.skuId()),
                mapping.skuCode(),
                mapping.providerSkuCode(),
                reason,
                message);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /** 差异类别与告警文案模板。 */
    public enum DiffCategory {
        MAPPING_MISSING("京东 SKU 映射核对发现 %d 个商品编码在京东查不到（映射缺失），请检查 provider_skus 映射或京东商品状态"),
        GOODS_INVALID("京东 SKU 映射核对发现 %d 个商品在京东已失效（非上架），请及时处理避免建单失败"),
        GOODS_STATUS_UNKNOWN("京东 SKU 映射核对发现 %d 个商品返回未文档化的启用标志（官方仅定义 1=未启用/2=启用），请确认京东侧商品状态"),
        NAME_MISMATCH("京东 SKU 映射核对发现 %d 个商品名称与系统名称不一致，请确认是否需要更新映射");

        private final String messageTemplate;

        DiffCategory(String messageTemplate) {
            this.messageTemplate = messageTemplate;
        }

        String messageTemplate() {
            return messageTemplate;
        }
    }

    /** 一次核对运行的结果。 */
    public record JdSkuMappingCheckResult(
            String checkRunNo,
            String providerCode,
            int checkedCount,
            List<CategoryDiff> categories) {}

    /** 单个差异类别的告警结果（每类一条）。 */
    public record CategoryDiff(
            String category,
            String message,
            boolean alertPersisted,
            String alertChannel,
            String alertNo,
            String fallbackReason,
            List<DiffItem> items) {}

    /** 单个 SKU 映射的差异明细；不含个人信息。 */
    public record DiffItem(
            String skuId, String skuCode, String providerSkuCode, String reason, String message) {}

    /** provider_skus × skus 的读取行。 */
    record SkuMappingRow(
            long id,
            long skuId,
            String providerSkuCode,
            String merchantSkuCode,
            String providerSkuName,
            String skuCode,
            String specification) {}

}
