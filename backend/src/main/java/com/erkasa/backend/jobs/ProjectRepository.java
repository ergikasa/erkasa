package com.erkasa.backend.jobs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Reads the project and locks its row until the transaction ends (SELECT ... FOR UPDATE).
     * Every status change uses this, so two clicks on "approve" at the same moment cannot both
     * succeed: the second waits, then sees the new status and is refused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> lockById(@Param("id") UUID id);

    /** A project only if it belongs to this user: other users' projects look like "not found". */
    Optional<Project> findByIdAndUserId(UUID id, UUID userId);

    /** The user's projects, newest first (dashboard, phase 6). */
    List<Project> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);
}