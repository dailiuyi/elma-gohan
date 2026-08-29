package com.elma.gohan.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Shadow 计算使用独立有界线程池，禁止反压回 HTTP 请求线程。 */
@Configuration
public class SafeRegretShadowConfiguration {

    @Bean(name = "safeRegretShadowExecutor")
    public Executor safeRegretShadowExecutor(
            SafeRegretShadowExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new HardShutdownTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("safe-regret-shadow-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        applyHardShutdown(executor, properties.getShutdownTimeoutSeconds());
        executor.initialize();
        return executor;
    }

    /**
     * 真正执行 capture 的池不排队：最多两个数据库调用在途；若调用失去响应，
     * 调度任务会在墙钟预算后取消它，后续任务直接失败而不会无限堆积线程或连接。
     */
    @Bean(name = "safeRegretShadowCaptureExecutor")
    public AsyncTaskExecutor safeRegretShadowCaptureExecutor(
            SafeRegretShadowExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new HardShutdownTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("safe-regret-shadow-capture-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        applyHardShutdown(executor, properties.getShutdownTimeoutSeconds());
        executor.initialize();
        return executor;
    }

    private void applyHardShutdown(ThreadPoolTaskExecutor executor, int timeoutSeconds) {
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(timeoutSeconds);
    }

    /**
     * Spring 6.2 的默认 early shutdown 只调用 shutdown()，不会中断在途任务；
     * awaitTermination 超时也只记录告警。本实现让 context-close、lifecycle-stop
     * 和 bean-destroy 都只触发一次 shutdownNow 路径，并在配置的等待上限后返回。
     */
    private static final class HardShutdownTaskExecutor
            extends ThreadPoolTaskExecutor {

        private final AtomicBoolean shutdownStarted = new AtomicBoolean();

        @Override
        protected void initiateEarlyShutdown() {
            hardShutdown();
        }

        @Override
        public void stop() {
            hardShutdown();
        }

        @Override
        public void stop(Runnable callback) {
            hardShutdown();
            callback.run();
        }

        @Override
        public void destroy() {
            hardShutdown();
        }

        private void hardShutdown() {
            if (shutdownStarted.compareAndSet(false, true)) {
                super.shutdown();
            }
        }
    }
}
