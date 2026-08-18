package cn.zimu.fulfillment.customer;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.LinkedHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 系统幂等客户编码生成器：编码格式 {@code CUST-WECOM-0001} 起按序号递增，模型和操作员均
 * 不能指定或覆写。基于 customer_code 唯一约束 + 重试循环，并发创建不会产生重复编码。
 */
@Component
public class CustomerCodeGenerator {

    private static final String PREFIX = "CUST-WECOM";

    private final JdbcTemplate jdbc;
    private final CustomerRepository customers;

    public CustomerCodeGenerator(JdbcTemplate jdbc, CustomerRepository customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    /**
     * 创建名称已由人工确认的业务客户；编码由系统生成。使用 JDBC 直插规避 Hibernate flush
     * 失败污染持久化上下文的问题，唯一约束冲突时取下一个序号重试。
     */
    public Customer createBusinessCustomer(String customerName) {
        String name = customerName == null ? "" : customerName.trim();
        if (name.isEmpty()) {
            throw BusinessException.badRequest("CUSTOMER_NAME_REQUIRED", "新客户名称不能为空");
        }
        if (name.length() > 128) {
            throw BusinessException.badRequest("CUSTOMER_NAME_TOO_LONG", "客户名称不能超过 128 个字符");
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = nextCode();
            try {
                jdbc.update(
                        """
                        INSERT INTO app.customers (customer_code, customer_name, data_scope, status, profile)
                        VALUES (?, ?, 'BUSINESS', 'ACTIVE', '{}'::jsonb)
                        """,
                        code,
                        name);
                return customers
                        .findByCustomerCode(code)
                        .orElseThrow(() -> new IllegalStateException("customer not visible after insert: " + code));
            } catch (DuplicateKeyException ex) {
                // 并发创建撞号：唯一约束保证不重复，重试下一个序号
            }
        }
        throw BusinessException.conflict("CUSTOMER_CODE_EXHAUSTED", "客户编码生成冲突，请重试");
    }

    private String nextCode() {
        Long max = jdbc.queryForObject(
                """
                SELECT max(CAST(substring(customer_code from '([0-9]+)$') AS BIGINT))
                FROM app.customers
                WHERE customer_code LIKE ?
                """,
                Long.class,
                PREFIX + "%");
        long next = (max == null ? 0L : max) + 1;
        return PREFIX + "-" + String.format("%04d", next);
    }
}
