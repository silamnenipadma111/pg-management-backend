package com.pgmanagement.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/** Runs {@code @Async} work (emails, SMS) on the calling thread so tests can assert on it right away. */
@TestConfiguration(proxyBeanMethods = false)
public class SynchronousAsyncConfig {

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
