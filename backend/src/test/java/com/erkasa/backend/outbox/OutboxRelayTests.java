package com.erkasa.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.erkasa.backend.AbstractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V2 task A1: a message written to the outbox reaches RabbitMQ only if its transaction commits,
 * and it waits (sent_at = NULL) while RabbitMQ cannot take it, then goes out without anyone
 * doing anything. "RabbitMQ cannot take it" is simulated with a queue that does not exist yet:
 * RabbitMQ returns the message as unroutable, exactly the case the relay must not count as sent.
 * (Stopping the shared RabbitMQ container would break the other test classes.)
 */
class OutboxRelayTests extends AbstractIntegrationTest {

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRepository messages;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private ObjectMapper json;

    /**
     * Durable on purpose: RabbitMQ 4 refuses non-durable, non-exclusive queues (deprecated feature
     * "transient_nonexcl_queues"). Each test deletes its queue in finally.
     */
    private static Queue testQueue(String name) {
        return new Queue(name, true, false, false);
    }

    private OutboxMessage waitUntil(UUID id, Predicate<OutboxMessage> condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        OutboxMessage m = null;
        while (System.currentTimeMillis() < deadline) {
            m = messages.findById(id).orElseThrow();
            if (condition.test(m)) {
                return m;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Outbox message " + id + " never " + what + "; attempts=" + m.getAttempts()
                + ", lastError=" + m.getLastError() + ", sentAt=" + m.getSentAt());
    }

    @Test
    void enqueueNeedsATransaction() {
        assertThatThrownBy(() -> outbox.enqueue("outbox.test.no-tx", Map.of("a", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        long before = messages.count();
        UUID id = tx.execute(status -> {
            UUID queued = outbox.enqueue("outbox.test.rollback", Map.of("a", 1));
            status.setRollbackOnly();
            return queued;
        });
        assertThat(messages.findById(id)).isEmpty();
        assertThat(messages.count()).isEqualTo(before);
    }

    @Test
    void aMessageRabbitCannotRouteWaitsAndIsSentOnceItCan() throws Exception {
        String queue = "outbox.test." + UUID.randomUUID();
        UUID id = tx.execute(status -> outbox.enqueue(queue, Map.of("hello", "world", "n", 42)));

        // the queue does not exist: RabbitMQ returns the message, the relay keeps it unsent
        OutboxMessage waiting = waitUntil(id, m -> m.getAttempts() >= 1, "was attempted");
        assertThat(waiting.getSentAt()).isNull();
        assertThat(waiting.getLastError()).contains("Unroutable");
        assertThat(waiting.getNextAttemptAt()).isAfter(waiting.getCreatedAt());

        // "RabbitMQ is back": the next attempt goes through on its own
        admin.declareQueue(testQueue(queue));
        try {
            OutboxMessage sent = waitUntil(id, m -> m.getSentAt() != null, "was sent");
            assertThat(sent.getLastError()).isNull();

            Message received = rabbit.receive(queue, 5_000);
            assertThat(received).isNotNull();
            assertThat(received.getMessageProperties().getMessageId()).isEqualTo(id.toString());
            assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
            JsonNode body = json.readTree(new String(received.getBody(), StandardCharsets.UTF_8));
            assertThat(body.path("hello").asString()).isEqualTo("world");
            assertThat(body.path("n").asInt()).isEqualTo(42);

            // sent exactly once: nothing else is waiting in the queue
            assertThat(rabbit.receive(queue, 1_500)).isNull();
        } finally {
            admin.deleteQueue(queue);
        }
    }

    @Test
    void aSentMessageIsNotSentAgain() throws Exception {
        String queue = "outbox.test." + UUID.randomUUID();
        admin.declareQueue(testQueue(queue));
        try {
            UUID id = tx.execute(status -> outbox.enqueue(queue, Map.of("once", true)));
            waitUntil(id, m -> m.getSentAt() != null, "was sent");
            assertThat(rabbit.receive(queue, 5_000)).isNotNull();

            // more relay runs (scheduled and by hand) leave it alone
            for (int i = 0; i < 3; i++) {
                relay.relay();
            }
            assertThat(rabbit.receive(queue, 1_500)).isNull();
            assertThat(messages.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
        } finally {
            admin.deleteQueue(queue);
        }
    }
}
