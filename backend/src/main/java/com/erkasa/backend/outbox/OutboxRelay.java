package com.erkasa.backend.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the rows of outbox_messages to RabbitMQ (V2 task A1).
 *
 * Every second: lock up to 50 unsent, due messages (FOR UPDATE SKIP LOCKED), publish them on the
 * default exchange, wait for RabbitMQ's publisher confirm, and set sent_at only for confirmed ones.
 * A message that RabbitMQ refuses, cannot route (no such queue) or does not confirm within 10 s
 * stays unsent and is tried again after 2, 4, 8 ... up to 60 seconds.
 *
 * Needs (application.yml): spring.rabbitmq.publisher-confirm-type=correlated,
 * spring.rabbitmq.publisher-returns=true and spring.rabbitmq.template.mandatory=true.
 * Without them RabbitMQ never confirms and every message would time out.
 *
 * erkasa.outbox.relay-enabled=false stops the schedule (relay() can still be called by hand).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final int BATCH_SIZE = 50;
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);
    /** Sent messages are kept this long, to look at when something went wrong. */
    static final Duration KEEP_SENT = Duration.ofDays(7);

    private final OutboxRepository messages;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public OutboxRelay(OutboxRepository messages, RabbitTemplate rabbit, TransactionTemplate tx,
                       @Value("${erkasa.outbox.relay-enabled:true}") boolean enabled) {
        this.messages = messages;
        this.rabbit = rabbit;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${erkasa.outbox.relay-delay-ms:1000}",
            fixedDelayString = "${erkasa.outbox.relay-delay-ms:1000}")
    void scheduledRelay() {
        if (!enabled) {
            return;
        }
        try {
            relay();
        } catch (RuntimeException e) {
            // e.g. the database is down; the next run tries again
            log.warn("Outbox relay run failed: {}", e.getMessage());
        }
    }

    /** One run. @return how many messages RabbitMQ confirmed */
    public int relay() {
        Integer confirmed = tx.execute(status -> {
            List<OutboxMessage> due = messages.lockDue(Instant.now(), BATCH_SIZE);
            if (due.isEmpty()) {
                return 0;
            }
            List<Pending> pending = new ArrayList<>(due.size());
            String connectionError = null;
            for (OutboxMessage m : due) {
                if (connectionError != null) {
                    // RabbitMQ is unreachable: do not wait for a connect timeout once per message
                    m.markFailed(connectionError, Instant.now());
                    continue;
                }
                CorrelationData correlation = new CorrelationData(m.getId().toString());
                try {
                    rabbit.send("", m.getQueue(), toAmqp(m), correlation);
                    pending.add(new Pending(m, correlation));
                } catch (AmqpConnectException e) {
                    connectionError = "RabbitMQ unreachable: " + e.getMessage();
                    m.markFailed(connectionError, Instant.now());
                } catch (AmqpException e) {
                    m.markFailed("Publish failed: " + e.getMessage(), Instant.now());
                }
            }

            long deadline = System.nanoTime() + CONFIRM_TIMEOUT.toNanos();
            int ok = 0;
            for (Pending p : pending) {
                String error = awaitConfirm(p.correlation(), deadline);
                if (error == null) {
                    p.message().markSent(Instant.now());
                    ok++;
                } else {
                    p.message().markFailed(error, Instant.now());
                }
            }
            int failed = due.size() - ok;
            if (failed > 0) {
                log.warn("Outbox: {} of {} messages not confirmed, will retry (first error: {})", failed, due.size(),
                        due.stream().filter(m -> m.getSentAt() == null).findFirst().map(OutboxMessage::getLastError).orElse(""));
            } else {
                log.debug("Outbox: {} messages published", ok);
            }
            return ok;
        });
        return confirmed == null ? 0 : confirmed;
    }

    /** @return null if RabbitMQ confirmed and routed the message, otherwise why not */
    private static String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                // acked, but no queue took it (e.g. the queue does not exist): the message is lost
                return "Unroutable: " + returned.getReplyCode() + " " + returned.getReplyText()
                        + " (queue " + returned.getRoutingKey() + ")";
            }
            if (!confirm.isAck()) {
                return "Rejected by RabbitMQ: " + confirm.getReason();
            }
            return null;
        } catch (TimeoutException e) {
            return "No confirm from RabbitMQ within " + CONFIRM_TIMEOUT.toSeconds() + " s";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the confirm";
        } catch (ExecutionException e) {
            return "Confirm failed: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }

    private static Message toAmqp(OutboxMessage m) {
        return MessageBuilder.withBody(m.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                // the same id if the message is ever sent twice, so receivers can recognise it
                .setMessageId(m.getId().toString())
                .build();
    }

    /** Deletes sent messages older than 7 days, once an hour. */
    @Scheduled(initialDelayString = "${erkasa.outbox.cleanup-delay-ms:3600000}",
            fixedDelayString = "${erkasa.outbox.cleanup-delay-ms:3600000}")
    void cleanup() {
        try {
            Integer deleted = tx.execute(status -> messages.deleteSentBefore(Instant.now().minus(KEEP_SENT)));
            if (deleted != null && deleted > 0) {
                log.info("Outbox: deleted {} sent messages older than {} days", deleted, KEEP_SENT.toDays());
            }
        } catch (RuntimeException e) {
            log.warn("Outbox cleanup failed: {}", e.getMessage());
        }
    }

    private record Pending(OutboxMessage message, CorrelationData correlation) {
    }
}
