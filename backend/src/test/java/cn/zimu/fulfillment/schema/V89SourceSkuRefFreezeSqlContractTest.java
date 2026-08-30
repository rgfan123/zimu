package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** V89 的静态并发/快照契约；防止后续重构重新打开同语句或并发分配绕过窗口。 */
class V89SourceSkuRefFreezeSqlContractTest {

    private static final String SCRIPT = "V89__freeze_allocated_order_line_source_sku_ref.sql";

    @Test
    void freezeUsesNewCommittedStateAndFulfillmentWritesLockTheParentLine() throws Exception {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration", SCRIPT));

        assertThat(migration)
                .contains("NEW.fulfillment_committed_at IS NOT NULL")
                .contains("OLD.fulfillment_committed_at IS NOT NULL")
                .contains("CREATE FUNCTION app.lock_fulfillment_order_line_identity()")
                .contains("PERFORM 1 FROM app.order_lines WHERE id=NEW.order_line_id FOR UPDATE")
                .contains("CREATE TRIGGER trg_fulfillment_identity_row_lock")
                .contains("BEFORE INSERT OR UPDATE ON app.fulfillments");
    }

    @Test
    void authoritativeSchemaEmbedsV89Verbatim() throws Exception {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration", SCRIPT)).trim();
        String schema = Files.readString(Path.of("..", "docs", "schema.sql"));
        String begin = "-- BEGIN " + SCRIPT;
        String end = "-- END " + SCRIPT;
        int start = schema.indexOf(begin);
        int finish = schema.indexOf(end, start);

        assertThat(start).as("docs/schema.sql must contain the V89 begin marker").isGreaterThanOrEqualTo(0);
        assertThat(finish).as("docs/schema.sql must contain the V89 end marker").isGreaterThan(start);
        String embedded = schema.substring(start + begin.length(), finish).trim();
        assertThat(embedded).isEqualTo(migration);
    }
}
