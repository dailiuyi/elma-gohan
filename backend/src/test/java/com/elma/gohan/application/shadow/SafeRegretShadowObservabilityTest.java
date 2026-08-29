package com.elma.gohan.application.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.config.SafeRegretShadowExecutionProperties;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

class SafeRegretShadowObservabilityTest {

    @Test
    void productionConfigExposesAllFiveCountersThroughPrometheus() throws IOException {
        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new SafeRegretShadowDispatcher(
                mock(SafeRegretShadowService.class), new SafeRegretProperties(),
                Runnable::run, new TaskExecutorAdapter(new SyncTaskExecutor()),
                new SafeRegretShadowExecutionProperties(), registry);

        String scrape = registry.scrape();
        assertThat(scrape).contains("elma_safe_regret_shadow_dispatch_total");
        for (String outcome : new String[]{
                "queued", "completed", "retried", "failed", "dropped"}) {
            assertThat(scrape).contains("outcome=\"" + outcome + "\"");
        }

        String productionYaml = Files.readString(
                Path.of("src/main/resources/application.yml"));
        assertThat(productionYaml).contains("include: health,prometheus");
    }
}
