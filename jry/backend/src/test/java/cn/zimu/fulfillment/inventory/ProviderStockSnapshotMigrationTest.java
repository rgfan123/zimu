package cn.zimu.fulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V14+: existing append-only stock facts survive upgrade and JD read-only facts remain admissible. */
@Testcontainers
class ProviderStockSnapshotMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesHistoricalAppendOnlyFactsWithoutMutationAndAllowsExternalJdObservations() throws Exception {
        flyway(MigrationVersion.fromVersion("13")).migrate();

        long providerId;
        long skuId;
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var statement = connection.createStatement()) {
                long categoryId = id(statement.executeQuery(
                        "INSERT INTO app.categories(category_code,category_name) "
                                + "VALUES ('MIGRATION','迁移验证') RETURNING id"));
                providerId = id(statement.executeQuery(
                        "INSERT INTO app.fulfillment_providers"
                                + "(provider_code,provider_name,provider_type,inventory_managed_by_us) "
                                + "VALUES ('JDMIGRATION','京东迁移验证','JD_WAREHOUSE',false) RETURNING id"));
                long productId = id(statement.executeQuery(
                        "INSERT INTO app.products(product_code,product_name,category_id) "
                                + "VALUES ('PROD-MIGRATION','迁移验证商品'," + categoryId + ") RETURNING id"));
                skuId = id(statement.executeQuery(
                        "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit) "
                                + "VALUES (" + productId + "," + providerId + ",'历史规格','件') RETURNING id"));

                // The pre-V14 trigger only allows managed inventory. Seed a historical row under the
                // old rule, then restore the provider's actual external-JD ownership before upgrade.
                statement.executeUpdate(
                        "UPDATE app.fulfillment_providers SET inventory_managed_by_us=true WHERE id=" + providerId);
                statement.executeUpdate(
                        "INSERT INTO app.provider_stock_snapshots"
                                + "(fulfillment_provider_id,sku_id,warehouse_code,stock_num,usable_num,synced_at,raw_payload) "
                                + "VALUES (" + providerId + "," + skuId
                                + ",'WH-LEGACY',3,2,CURRENT_TIMESTAMP,'{\"normalized\":true}'::jsonb)");
                statement.executeUpdate(
                        "UPDATE app.fulfillment_providers SET inventory_managed_by_us=false WHERE id=" + providerId);
            }
        }

        flyway(null).migrate();

        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(
                    "SELECT quantity_unit,source_type FROM app.provider_stock_snapshots "
                            + "WHERE warehouse_code='WH-LEGACY'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("quantity_unit")).isEqualTo("UNKNOWN");
                assertThat(result.getString("source_type")).isEqualTo("UNKNOWN");
            }
            assertThat(statement.executeUpdate(
                    "INSERT INTO app.provider_stock_snapshots"
                            + "(fulfillment_provider_id,sku_id,warehouse_code,stock_num,usable_num,"
                            + " quantity_unit,source_type,synced_at,raw_payload) "
                            + "VALUES (" + providerId + "," + skuId
                            + ",'WH-JD-LIVE',4,4,'JD_PIECE','JD_ISC_QUERY_STOCK',CURRENT_TIMESTAMP,"
                            + "'{\"source\":\"jd_realtime\"}'::jsonb)"))
                    .isEqualTo(1);
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static long id(java.sql.ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
