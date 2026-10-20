package com.erkasa.backend.features.promptvideo.render;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two RabbitMQ queues between the backend and the render worker (Node + Remotion).
 *
 *   backend --render.requests--> worker      one message per video to render
 *   backend <--render.results--- worker      progress (0-100 %) and the final result
 *
 * Both are durable: a message survives a RabbitMQ restart, and a request is only removed when
 * the worker acknowledges it, so a worker that crashes mid-render gets it again on restart.
 * The worker declares the same queues with the same settings (render-worker/src/worker.ts).
 */
@Configuration
public class RenderQueues {

    public static final String REQUESTS = "render.requests";
    public static final String RESULTS = "render.results";

    @Bean
    Queue renderRequests() {
        return QueueBuilder.durable(REQUESTS).build();
    }

    @Bean
    Queue renderResults() {
        return QueueBuilder.durable(RESULTS).build();
    }
}
