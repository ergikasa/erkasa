package com.erkasa.backend.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EmailTokenRepository extends JpaRepository<EmailToken, UUID> {

    /** Locks the row so the same link clicked twice at the same moment is used only once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EmailToken t where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<EmailToken> findForUpdate(@Param("hash") String tokenHash,
                                       @Param("purpose") EmailTokenPurpose purpose);

    /** When a new link is sent, older unused links of the same kind stop working. */
    @Modifying
    @Query("""
            update EmailToken t set t.usedAt = :now
            where t.userId = :userId and t.purpose = :purpose and t.usedAt is null
            """)
    int invalidateActive(@Param("userId") UUID userId,
                         @Param("purpose") EmailTokenPurpose purpose,
                         @Param("now") Instant now);
}