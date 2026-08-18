package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V17 keeps pre-existing execution history honest instead of guessing MOCK or REAL. */
@Testcontainers
class ShipmentJdOutboundClientModeMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesExistingOutboundHistoryAsUnknownAndConstrainsFutureModes() throws Exception {
        flyway(MigrationVersion.fromVersion("16")).migrate();

        long shipmentId;
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            long providerId = id(statement.executeQuery(
                    "INSERT INTO app.fulfillment_providers"
                            + "(provider_code,provider_name,provider_type,inventory_managed_by_us) "
                            + "VALUES ('JDMODE','京东模式迁移','JD_WAREHOUSE',false) RETURNING id"));
            long customerId = id(statement.executeQuery(
                    "INSERT INTO app.customers(customer_code,customer_name) "
                            + "VALUES ('CUST-MODE','迁移验证客户') RETURNING id"));
            long orderId = id(statement.executeQuery(
                    "INSERT INTO app.orders(order_no,source_channel,source_ref,source_ref_kind,customer_id,"
                            + "order_status,data_scope,settlement_method,settlement_time,receiver_name,"
                            + "receiver_phone,receiver_address) VALUES "
                            + "('ORD-MODE','WECOM','WECOM-MODE','PROVIDED'," + customerId
                            + ",'VALIDATED','BUSINESS','MONTHLY',CURRENT_TIMESTAMP,'测试','13800000000','上海市') "
                            + "RETURNING id"));
            shipmentId = id(statement.executeQuery(
                    "INSERT INTO app.shipments(shipment_no,outbound_order_no,order_id,fulfillment_provider_id,"
                            + "shipment_sequence,receiver_name_snapshot,receiver_phone_snapshot,"
                            + "receiver_address_snapshot,shipment_status) VALUES "
                            + "('SHIP-MODE','202608140999'," + orderId + "," + providerId
                            + ",1,'测试','13800000000','上海市','CREATED') RETURNING id"));
            statement.executeUpdate(
                    "INSERT INTO app.shipment_jd_outbounds"
                            + "(shipment_id,erp_delivery_no,sync_status) VALUES ("
                            + shipmentId + ",'202608140999','NONE')");
        }

        flyway(null).migrate();

        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(
                    "SELECT client_mode FROM app.shipment_jd_outbounds WHERE shipment_id=" + shipmentId)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("UNKNOWN");
            }
            assertThat(statement.executeUpdate(
                    "UPDATE app.shipment_jd_outbounds SET client_mode='MOCK' WHERE shipment_id=" + shipmentId))
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
