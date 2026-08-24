package cn.zimu.fulfillment.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ScheduledTaskPoolConfigurationTest {

    @Test
    void schedulerHasOneBoundedLanePerScheduledTriggerAndCannotBeConfiguredBackToOne() {
        ThreadPoolTaskScheduler scheduler = new ScheduledTaskPoolConfiguration().taskScheduler(1);
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(ScheduledTaskPoolConfiguration.MINIMUM_POOL_SIZE);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("zimu-scheduled-");
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void schedulerConfigurationCannotCreateAnUnboundedNumberOfThreads() {
        ThreadPoolTaskScheduler scheduler =
                new ScheduledTaskPoolConfiguration().taskScheduler(Integer.MAX_VALUE);
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(ScheduledTaskPoolConfiguration.MAXIMUM_POOL_SIZE);
        } finally {
            scheduler.destroy();
        }
    }
}
