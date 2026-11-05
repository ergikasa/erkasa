package com.erkasa.backend.outbox;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Write a message for RabbitMQ as part of the current database transaction.
 *
 * The message is published only if the transaction commits, and it is published even if RabbitMQ
 * is down right now or the backend stops a moment later ({@link OutboxRelay} keeps trying).
 * Never call RabbitTemplate.send() directly for messages that follow a state change.
 */
@Service
public class Outbox {

    private final OutboxRepository messages;
    private final ObjectMapper json;

    public Outbox(OutboxRepository messages, ObjectMapper json) {
        this.messages = messages;
        this.json = json;
    }

    /**
     * Queues {@code payload} (serialized to JSON) for {@code queue} on the default exchange.
     * Must run inside a transaction: without one, there is nothing to be atomic with.
     *
     * @return the outbox message id, also sent as the AMQP message-id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String queue, Object payload) {
        OutboxMessage message = OutboxMessage.create(queue, json.writeValueAsString(payload));
        messages.save(message);
        return message.getId();
    }
}
