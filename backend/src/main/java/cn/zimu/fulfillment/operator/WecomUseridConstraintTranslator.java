package cn.zimu.fulfillment.operator;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 企微 userid 唯一索引违规的纯翻译器（Issue #89）。
 *
 * <p>{@link OperatorService#saveOperator} 的 {@code existsByWecomUserid} 预查读的是已提交快照，
 * 两个并发请求可同时通过预查后各自 flush，只有数据库唯一索引能兜底。撞
 * {@code uq_internal_operators_wecom_userid} 时翻译为稳定 409 {@code WECOM_USERID_EXISTS}；
 * 其他数据完整性违规（check/fk/其他唯一键）不翻译，调用方原样重抛。
 *
 * <p>本类无任何框架/持久化依赖，纯分类逻辑可被快速、确定性的单元测试直接覆盖，
 * 不必靠并发 HTTP 竞态去复现数据库兜底路径。
 */
final class WecomUseridConstraintTranslator {

    /** internal_operators 上企微 userid 的唯一 partial index；并发撞库时按此名翻译为 409。 */
    static final String WECOM_USERID_UNIQUE_CONSTRAINT = "uq_internal_operators_wecom_userid";

    /** PostgreSQL 唯一约束违规消息里的约束/索引名（"violates unique constraint \"name\""）。 */
    private static final Pattern UNIQUE_CONSTRAINT_NAME = Pattern.compile("violates unique constraint \"([^\"]+)\"");

    private WecomUseridConstraintTranslator() {}

    /**
     * 命中企微 userid 唯一索引时返回 409 {@code WECOM_USERID_EXISTS}；否则返回 {@code null}，
     * 由调用方把原始 {@link DataIntegrityViolationException} 原样重抛（不误翻译其他约束）。
     */
    static BusinessException translate(DataIntegrityViolationException exception) {
        if (isWecomUseridUniqueViolation(exception)) {
            return BusinessException.conflict(
                    OperatorRules.WECOM_USERID_EXISTS_ERROR_CODE,
                    "该企微 userid 已绑定其他运营人员，请先核对人员档案");
        }
        return null;
    }

    /** 沿异常因果链判定是否命中企微 userid 唯一索引（Hibernate 约束名或 PG 23505 消息名）。 */
    static boolean isWecomUseridUniqueViolation(DataIntegrityViolationException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation
                    && WECOM_USERID_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return WECOM_USERID_UNIQUE_CONSTRAINT.equals(uniqueConstraintName(sql.getMessage()));
            }
        }
        return false;
    }

    private static String uniqueConstraintName(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = UNIQUE_CONSTRAINT_NAME.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
