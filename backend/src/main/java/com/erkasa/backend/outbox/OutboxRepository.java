package com.erkasa.backend.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Unsent messages that are due, oldest first, locked for this transaction.
     * SKIP LOCKED: if two backend instances run the relay at the same time, each takes
     * different rows instead of waiting for (or double-sending) the other's.
     */
    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE sent_at IS NULL AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM OutboxMessage m WHERE m.sentAt IS NOT NULL AND m.sentAt < :before")
    int deleteSentBefore(@Param("before") Instant before);
}
