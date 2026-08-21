package cn.zimu.fulfillment.operator;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Issue #89: 企微 userid 唯一索引违规翻译 seam 的确定性单元测试。
 *
 * <p>并发 HTTP 竞态（{@code OperatorCrudApiTest}）只同步了请求起点，两个事务可能靠
 * {@code existsByWecomUserid} 预查直接命中 409，不经过 {@code saveAndFlush} 的异常翻译。
 * 这里直接构造 {@link DataIntegrityViolationException} 因果链，确定性地覆盖翻译器：
 * 23505 + {@code uq_internal_operators_wecom_userid} → 409 {@code WECOM_USERID_EXISTS}；
 * 其他唯一/check 约束不翻译（调用方原样重抛）。集成竞态用例仍保留，兜底真实唯一索引。
 */
class WecomUseridConstraintTranslatorTest {

    @Test
    void sqlState23505WithPostgresMessageMapsToConflict() {
        // Hibernate constraintName 置空，仅靠 SQLSTATE 23505 + PostgreSQL 消息名回退分支命中
        DataIntegrityViolationException violation = violation(
                null,
                "23505",
                "ERROR: duplicate key value violates unique constraint \"uq_internal_operators_wecom_userid\"");

        BusinessException translated = WecomUseridConstraintTranslator.translate(violation);

        assertThat(translated).isNotNull();
        assertThat(translated.getHttpStatus()).isEqualTo(409);
        assertThat(translated.getBusinessCode()).isEqualTo("WECOM_USERID_EXISTS");
    }

    @Test
    void hibernateConstraintNameAlsoMapsToConflict() {
        DataIntegrityViolationException violation = violation("uq_internal_operators_wecom_userid", null, null);

        BusinessException translated = WecomUseridConstraintTranslator.translate(violation);

        assertThat(translated).isNotNull();
        assertThat(translated.getHttpStatus()).isEqualTo(409);
        assertThat(translated.getBusinessCode()).isEqualTo("WECOM_USERID_EXISTS");
    }

    @Test
    void otherUniqueConstraintIsNotTranslatedAndRethrownUnchanged() {
        DataIntegrityViolationException violation = violation(
                "uq_customers_email",
                "23505",
                "ERROR: duplicate key value violates unique constraint \"uq_customers_email\"");

        // 其他唯一键不命中企微 userid 索引：翻译返回 null，OperatorService 原样重抛该异常
        assertThat(WecomUseridConstraintTranslator.isWecomUseridUniqueViolation(violation)).isFalse();
        assertThat(WecomUseridConstraintTranslator.translate(violation)).isNull();
    }

    @Test
    void checkConstraintIsNotTranslatedAndRethrownUnchanged() {
        DataIntegrityViolationException violation = violation(
                "internal_operators_responsible_team_check",
                "23514",
                "ERROR: new row for relation \"internal_operators\" violates check constraint"
                        + " \"internal_operators_responsible_team_check\"");

        // check 违规（23514）不是 23505 唯一冲突，同样不翻译、原样重抛
        assertThat(WecomUseridConstraintTranslator.isWecomUseridUniqueViolation(violation)).isFalse();
        assertThat(WecomUseridConstraintTranslator.translate(violation)).isNull();
    }

    /** 构造 Hibernate 包装的约束违规因果链：DIVE → ConstraintViolationException → SQLException。 */
    private static DataIntegrityViolationException violation(String constraintName, String sqlState, String message) {
        SQLException root = sqlState == null ? null : new SQLException(message, sqlState);
        ConstraintViolationException cause = new ConstraintViolationException(
                "could not execute statement", root, constraintName);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }
}
