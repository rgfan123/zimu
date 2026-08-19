package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.FulfillmentHubApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 集成测试共用基座（T03 评审收敛 5 处重复的 Testcontainers boot/close）：真实 PostgreSQL +
 * 完整应用启动（Flyway 迁移含 V33 种子），暴露 {@link #jdbc} 供断言 DB 真源。子类只写测试，
 * 不再各自复制 boot/close 样板。
 */
@Testcontainers
public abstract class AgentTestcontainersBase {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    protected static ConfigurableApplicationContext context;
    protected static JdbcTemplate jdbc;

    @BeforeAll
    static void boot() {
        String[] properties = {
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.data.redis.repositories.enabled=false",
            "--spring.main.banner-mode=off"
        };
        context = new SpringApplicationBuilder(FulfillmentHubApplication.class)
                .web(WebApplicationType.NONE)
                .run(properties);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }
}
