package cn.zimu.fulfillment.file;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 来源回填文件的企微投递目标解析。
 *
 * <p>取该批次所涉履约方配置的企微群（{@code config->>'wecomGroupChatId'}）。
 * 一个批次理论上可跨履约方，此时取第一个已配置的——回填文件本身是按批次产出的整体，
 * 不按履约方拆分，因此只需要一个可送达的会话。解析不到返回 null，调用方据此跳过。
 */
@Component
public class SourceReturnWecomRouteResolver {

    private final JdbcTemplate jdbc;

    public SourceReturnWecomRouteResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public String chatIdFor(long importBatchId) {
        List<String> found = jdbc.queryForList(
                """
                SELECT DISTINCT p.config->>'wecomGroupChatId'
                FROM app.raw_import_rows r
                JOIN app.order_lines ol ON ol.id = r.order_line_id
                JOIN app.fulfillments f ON f.order_line_id = ol.id
                JOIN app.fulfillment_providers p ON p.id = f.fulfillment_provider_id
                WHERE r.import_batch_id = ?
                  AND p.config->>'wecomGroupChatId' IS NOT NULL
                LIMIT 1
                """,
                String.class,
                importBatchId);
        return found.isEmpty() ? null : found.getFirst();
    }
}
