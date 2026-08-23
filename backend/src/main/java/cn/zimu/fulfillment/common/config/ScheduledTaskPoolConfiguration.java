package cn.zimu.fulfillment.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps one long-running scheduled worker from starving unrelated ticket workflows. */
@Configuration
public class ScheduledTaskPoolConfiguration {

    /** The application currently has ten independent {@code @Scheduled} trigger streams. */
    static final int MINIMUM_POOL_SIZE = 10;
    static final int MAXIMUM_POOL_SIZE = 32;

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(@Value("${app.scheduling.pool-size:10}") int configuredSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(MINIMUM_POOL_SIZE, Math.min(MAXIMUM_POOL_SIZE, configuredSize)));
        scheduler.setThreadNamePrefix("zimu-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(2);
        return scheduler;
    }
}
