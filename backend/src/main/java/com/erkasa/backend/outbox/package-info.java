/**
 * Transactional outbox (V2 task A1): messages for RabbitMQ are written to the database in the
 * same transaction as the change that caused them, and {@link com.erkasa.backend.outbox.OutboxRelay}
 * publishes them. Depends only on the database and RabbitMQ, never on feature packages.
 */
package com.erkasa.backend.outbox;
