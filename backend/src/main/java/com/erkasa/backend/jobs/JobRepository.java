package com.erkasa.backend.jobs;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Every job of a project, oldest first: the project's history. */
    List<Job> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /** The latest job of one kind, e.g. the current ASSETS job. */
    Optional<Job> findFirstByProjectIdAndKindOrderByCreatedAtDesc(UUID projectId, JobKind kind);

    /** Is a job of this kind already queued or running? Used to refuse double starts. */
    boolean existsByProjectIdAndKindAndStatusIn(UUID projectId, JobKind kind, Collection<JobStatus> statuses);

    long countByProjectIdAndKind(UUID projectId, JobKind kind);

    /** Jobs left QUEUED or RUNNING, e.g. after a restart (picked up again in 4.4). */
    List<Job> findByStatusInOrderByCreatedAtAsc(Collection<JobStatus> statuses);
}