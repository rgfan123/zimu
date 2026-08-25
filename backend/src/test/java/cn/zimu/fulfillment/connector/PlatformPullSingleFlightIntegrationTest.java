package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PlatformPullSingleFlightIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void sameChannelIsExclusiveWhileDifferentChannelsRemainIndependent() {
        try (HikariDataSource firstPool = dataSource("first");
                HikariDataSource secondPool = dataSource("second")) {
            PlatformPullSingleFlight first = new PlatformPullSingleFlight(firstPool);
            PlatformPullSingleFlight second = new PlatformPullSingleFlight(secondPool);

            try (PlatformPullSingleFlight.Lease firstChannel = first.tryAcquire("CAISHIXIAN")) {
                assertThat(firstChannel.acquired()).isTrue();

                try (PlatformPullSingleFlight.Lease overlap = second.tryAcquire("CAISHIXIAN")) {
                    assertThat(overlap.acquired()).isFalse();
                }
                try (PlatformPullSingleFlight.Lease otherChannel = second.tryAcquire("FEIXIANG")) {
                    assertThat(otherChannel.acquired()).isTrue();
                }
            }

            try (PlatformPullSingleFlight.Lease reacquired = second.tryAcquire("CAISHIXIAN")) {
                assertThat(reacquired.acquired()).isTrue();
            }
        }
    }

    @Test
    void exceptionalWorkStillReleasesTheSessionLock() {
        try (HikariDataSource firstPool = dataSource("exception-first");
                HikariDataSource secondPool = dataSource("exception-second")) {
            PlatformPullSingleFlight first = new PlatformPullSingleFlight(firstPool);
            PlatformPullSingleFlight second = new PlatformPullSingleFlight(secondPool);

            assertThatThrownBy(() -> {
                        try (PlatformPullSingleFlight.Lease lease = first.tryAcquire("JUFUBAO")) {
                            assertThat(lease.acquired()).isTrue();
                            throw new IllegalStateException("simulated pull failure");
                        }
                    })
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("simulated pull failure");

            try (PlatformPullSingleFlight.Lease reacquired = second.tryAcquire("JUFUBAO")) {
                assertThat(reacquired.acquired()).isTrue();
            }
        }
    }

    private static HikariDataSource dataSource(String suffix) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5_000L);
        config.setPoolName("platform-pull-single-flight-" + suffix);
        return new HikariDataSource(config);
    }
}
