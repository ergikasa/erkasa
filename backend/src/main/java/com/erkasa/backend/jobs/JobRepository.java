package com.erkasa.backend.jobs;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Every job of a project, oldest first: the project's history. */
    List<Job> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /** The latest job of one kind, e.g. the current ASSETS job. */
    Optional<Job> findFirstByProjectIdAndKindOrderByCreatedAtDesc(UUID projectId, JobKind kind);

    /** Is a job of this kind already queued or running? Used to refuse double starts. */
    boolean existsByProjectIdAndKindAndStatusIn(UUID projectId, JobKind kind, Collection<JobStatus> statuses);

    /**
     * Only the project id, WITHOUT loading the job into the persistence context. Callers lock the
     * project first and read the job afterwards; loading the job before the lock would give them a
     * cached copy from before the lock, i.e. possibly stale state (V2 A3).
     */
    @Query("select j.projectId from Job j where j.id = :id")
    Optional<UUID> findProjectIdById(@Param("id") UUID id);

    long countByProjectIdAndKind(UUID projectId, JobKind kind);

    /** Jobs left QUEUED or RUNNING, e.g. after a restart (picked up again in 4.4). */
    List<Job> findByStatusInOrderByCreatedAtAsc(Collection<JobStatus> statuses);
}