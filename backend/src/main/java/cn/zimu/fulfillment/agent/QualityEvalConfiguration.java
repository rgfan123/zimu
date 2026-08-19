package cn.zimu.fulfillment.agent;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QUALITY 评测装配（meta-agent-platform-impl 09）：默认执行器 {@link NpxPromptfooRunner}
 * 始终注册（进程只在执行时拉起，测试注入 @Primary 假执行器即可），工作目录与超时可配。
 */
@Configuration
public class QualityEvalConfiguration {

    @Bean
    QualityEvalRunner qualityEvalRunner(
            @Value("${app.quality-eval.work-dir:${java.io.tmpdir}}") String workDir,
            @Value("${app.quality-eval.timeout-ms:180000}") long timeoutMillis) {
        return new NpxPromptfooRunner(Path.of(workDir), timeoutMillis);
    }
}
