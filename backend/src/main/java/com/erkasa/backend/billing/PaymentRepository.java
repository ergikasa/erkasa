package com.erkasa.backend.billing;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByPaddleTransactionId(String paddleTransactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paddleTransactionId = :txn")
    Optional<Payment> lockByPaddleTransactionId(@Param("txn") String txn);
}
