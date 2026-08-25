package cn.zimu.fulfillment.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps one long-running scheduled worker from starving unrelated ticket workflows. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTaskPoolConfiguration {

    /** The application currently has eleven independent {@code @Scheduled} trigger streams. */
    static final int MINIMUM_POOL_SIZE = 12;
    static final int MAXIMUM_POOL_SIZE = 32;

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(@Value("${app.scheduling.pool-size:12}") int configuredSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(MINIMUM_POOL_SIZE, Math.min(MAXIMUM_POOL_SIZE, configuredSize)));
        scheduler.setThreadNamePrefix("zimu-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(2);
        return scheduler;
    }
}
