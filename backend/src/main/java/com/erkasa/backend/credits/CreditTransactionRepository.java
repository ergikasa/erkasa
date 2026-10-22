package com.erkasa.backend.credits;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    /** Charges (-) plus refunds (+) of one video: negative while it is paid, 0 when nothing is taken. */
    @Query("select coalesce(sum(t.amount), 0) from CreditTransaction t where t.projectId = :projectId")
    long sumForProject(@Param("projectId") UUID projectId);
}
