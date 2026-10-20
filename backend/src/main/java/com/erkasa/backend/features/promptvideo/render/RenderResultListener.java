package com.erkasa.backend.features.promptvideo.render;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Reads progress and results from the render worker (queue render.results).
 * Reads the raw bytes and parses JSON itself, so it does not depend on message converters
 * or on the content type the Node worker sets.
 */
@Component
public class RenderResultListener {

    private static final Logger log = LoggerFactory.getLogger(RenderResultListener.class);

    private final RenderJobService renders;
    private final ObjectMapper json;

    public RenderResultListener(RenderJobService renders, ObjectMapper json) {
        this.renders = renders;
        this.json = json;
    }

    @RabbitListener(queues = RenderQueues.RESULTS)
    public void onMessage(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            renders.onResult(json.readValue(body, RenderMessages.Result.class));
        } catch (RuntimeException e) {
            // Never throw here: RabbitMQ would redeliver the same bad message forever.
            log.error("Could not handle render result: {}", body, e);
        }
    }
}
