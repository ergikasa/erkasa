package com.erkasa.backend.jobs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * The threads that do pipeline work (LLM, images, voices) in the background, so HTTP requests
 * answer at once instead of waiting a minute for AI providers.
 *
 * Deliberately NOT a Spring TaskExecutor bean: that would replace the pool @Async email uses.
 * Phase 9 can move this work to RabbitMQ workers without changing the callers.
 */
@Component
public class PipelineWorkers {

    private static final Logger log = LoggerFactory.getLogger(PipelineWorkers.class);

    private final ExecutorService pool;

    public PipelineWorkers(@Value("${erkasa.pipeline.threads:4}") int threads) {
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "pipeline-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs the work on a pipeline thread. Errors are logged, never lost silently. */
    public void submit(String what, Runnable work) {
        pool.submit(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.error("Pipeline work '{}' crashed", what, e);
            }
        });
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Pipeline work still running at shutdown; it will be picked up again on restart (4.4)");
            pool.shutdownNow();
        }
    }
}