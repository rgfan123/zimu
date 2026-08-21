package cn.zimu.fulfillment.operator;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内部运营人员 CRUD 用例（Issue #89）：幂等写 + 审计 + 乐观锁，语义对齐主数据 seam。 */
@Service
public class OperatorService {

    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Sort ID_ASC = Sort.by("id").ascending();

    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final InternalOperatorRepository operators;
    private final EntityManager entityManager;

    public OperatorService(
            IdempotencyService idempotency,
            AuditLogService audit,
            InternalOperatorRepository operators,
            EntityManager entityManager) {
        this.idempotency = idempotency;
        this.audit = audit;
        this.operators = operators;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<OperatorDto> operators(int page, int size, String responsibleTeam, String query) {
        String team = OperatorRules.normalizeTeam(responsibleTeam);
        String pattern = query == null || query.isBlank() ? null : query.trim();
        Page<InternalOperator> result = team == null && pattern == null
                ? operators.findAll(page(page, size))
                : operators.search(team, pattern, page(page, size));
        return PageResponse.of(result.stream().map(this::operator).toList(), result);
    }

    @Transactional(readOnly = true)
    public OperatorDto operator(long id) {
        return operator(operators.findById(id)
                .orElseThrow(() -> BusinessException.notFound("运营人员不存在")));
    }

    @Transactional
    public IdempotentResult<OperatorDto> createOperator(OperatorWrite input, String key, CommandContext ctx) {
        String displayName = OperatorRules.requireDisplayName(input.displayName());
        String team = OperatorRules.requireTeam(input.responsibleTeam());
        String userid = OperatorRules.requireWecomUserid(input.wecomUserid());
        return write("operator.create", key, input, CREATED, ctx, () -> {
            requireUseridFree(userid, null);
            InternalOperator value = new InternalOperator();
            value.setDisplayName(displayName);
            value.setResponsibleTeam(team);
            value.setWecomUserid(userid);
            value.setActive(!Boolean.FALSE.equals(input.active()));
            return operator(refresh(saveOperator(value)));
        });
    }

    @Transactional
    public IdempotentResult<OperatorDto> patchOperator(long id, OperatorPatch input, String key, CommandContext ctx) {
        requireAny(input.displayName(), input.responsibleTeam(), input.wecomUserid(), input.active());
        InternalOperator current = operators.findById(id)
                .orElseThrow(() -> BusinessException.notFound("运营人员不存在"));
        // 幂等 payload 只含稳定请求内容（id + 显式出现的字段）；变更前 userid 是读库得到的
        // 可变状态，若混入幂等 payload，首次执行后重放会因 payload hash 变化被误判为
        // 同 key 不同请求（409），无法返回首次结果。wecom_userid_before 仅进审计：
        // 绑定变更可追溯操作人与前后值。
        Map<String, Object> idempotencyPayload = operatorPatchIdempotencyPayload(id, input);
        Map<String, Object> auditPayload = operatorPatchAuditPayload(id, current.getWecomUserid(), input);
        return write("operator.update", key, idempotencyPayload, auditPayload, OK, ctx, () -> {
            version(current.getLockVersion(), input.expectedVersion());
            // 先校验/查重、再变更实体：查询（existsByWecomUserid）不会命中脏状态，
            // Hibernate 不会在查询前自动 flush 提前发一次 UPDATE——每个成功 PATCH 恰好一次
            // 业务 UPDATE，版本只 +1
            String displayName =
                    input.displayName() == null ? null : OperatorRules.requireDisplayName(input.displayName());
            String team =
                    input.responsibleTeam() == null ? null : OperatorRules.requireTeam(input.responsibleTeam());
            String userid = null;
            if (input.wecomUserid() != null) {
                userid = OperatorRules.requireWecomUserid(input.wecomUserid());
                requireUseridFree(userid, current.getWecomUserid());
            }
            if (displayName != null) {
                current.setDisplayName(displayName);
            }
            if (team != null) {
                current.setResponsibleTeam(team);
            }
            if (input.wecomUserid() != null) {
                // 空串 = 显式清除绑定（userid 为 null）
                current.setWecomUserid(userid);
            }
            if (input.active() != null) {
                current.setActive(input.active());
            }
            return operator(refresh(saveOperator(current)));
        });
    }

    private void requireUseridFree(String userid, String currentUserid) {
        if (userid == null || userid.equals(currentUserid)) {
            return;
        }
        if (operators.existsByWecomUserid(userid)) {
            throw BusinessException.conflict(
                    OperatorRules.WECOM_USERID_EXISTS_ERROR_CODE,
                    "该企微 userid 已绑定其他运营人员，请先核对人员档案");
        }
    }

    /**
     * 包装 saveAndFlush：existsByWecomUserid 的预查是读已提交快照，两个并发请求可同时通过预查
     * 后各自 flush，只有数据库唯一索引能兜底。撞 {@code uq_internal_operators_wecom_userid} 时
     * 经 {@link WecomUseridConstraintTranslator} 翻译为稳定 409 {@code WECOM_USERID_EXISTS}；
     * 其他数据完整性违规（check/fk/其他唯一键）原样抛出，不误翻译。
     */
    private InternalOperator saveOperator(InternalOperator value) {
        try {
            return operators.saveAndFlush(value);
        } catch (DataIntegrityViolationException duplicate) {
            BusinessException translated = WecomUseridConstraintTranslator.translate(duplicate);
            if (translated != null) {
                throw translated;
            }
            throw duplicate;
        }
    }

    /**
     * 幂等 payload：只包含稳定请求内容（id + 显式出现的字段，显式空串 = 清除绑定，必须记录），
     * 不含任何读库得到的可变状态；同 key 重放（首次执行已提交后）才能命中同一 payload hash，
     * 返回首次结果而不是 409。字段用 snake_case 显式映射，避免 record 直接嵌入 Map 时丢失命名。
     */
    private static Map<String, Object> operatorPatchIdempotencyPayload(long id, OperatorPatch input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", input.expectedVersion());
        putNullable(body, "display_name", input.displayName());
        putNullable(body, "responsible_team", input.responsibleTeam());
        if (input.wecomUserid() != null) {
            body.put("wecom_userid", input.wecomUserid());
        }
        if (input.active() != null) {
            body.put("active", input.active());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("body", body);
        return payload;
    }

    /** 审计 payload：幂等 payload + 变更前 userid（仅审计用途，不参与幂等键）。 */
    private static Map<String, Object> operatorPatchAuditPayload(
            long id, String wecomUseridBefore, OperatorPatch input) {
        Map<String, Object> payload = operatorPatchIdempotencyPayload(id, input);
        if (input.wecomUserid() != null) {
            payload.put("wecom_userid_before", wecomUseridBefore);
        }
        return payload;
    }

    private static void putNullable(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private <T> IdempotentResult<T> write(
            String operation, String key, Object payload, int status, CommandContext ctx, Supplier<T> work) {
        return write(operation, key, payload, payload, status, ctx, work);
    }

    /**
     * 幂等写 + 审计：幂等键只绑定 {@code idempotencyPayload}（必须是仅由请求决定的稳定内容），
     * {@code auditPayload} 可额外携带读库得到的变更前状态用于审计追溯——变更前状态若混入幂等
     * payload，首次执行后重放会因 payload hash 变化被误判为冲突。
     */
    private <T> IdempotentResult<T> write(
            String operation, String key, Object idempotencyPayload, Object auditPayload,
            int status, CommandContext ctx, Supplier<T> work) {
        return idempotency.execute(operation, key, idempotencyPayload, status, () -> {
            T result = work.get();
            audit.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(ctx.requestId())
                    .traceId(ctx.traceId())
                    .operator(ctx.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("OperatorService")
                    .operation(operation)
                    .requestPayload(auditPayload)
                    .responsePayload(result)
                    .httpStatus(status)
                    .businessCode("SUCCESS"));
            return result;
        });
    }

    private OperatorDto operator(InternalOperator value) {
        return new OperatorDto(
                String.valueOf(value.getId()),
                value.getDisplayName(),
                value.getResponsibleTeam(),
                value.getWecomUserid(),
                value.isActive(),
                value.getLockVersion(),
                value.getCreatedAt(),
                value.getUpdatedAt());
    }

    private <T> T refresh(T value) {
        entityManager.refresh(value);
        return value;
    }

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, ID_ASC);
    }

    private static void version(Long actual, Long expected) {
        if (!actual.equals(expected)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "数据已被其他请求修改，请刷新后重试");
        }
    }

    private static void requireAny(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return;
            }
        }
        throw BusinessException.badRequest("PATCH_EMPTY", "至少需要修改一个业务字段");
    }
}
