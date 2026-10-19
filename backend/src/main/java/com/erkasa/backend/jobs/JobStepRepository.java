package com.erkasa.backend.jobs;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobStepRepository extends JpaRepository<JobStep, UUID> {

    /** All steps of a job in a stable order: by type, then scene 0, 1, 2... */
    List<JobStep> findByJobIdOrderByTypeAscSceneIndexAsc(UUID jobId);

    Optional<JobStep> findByJobIdAndTypeAndSceneIndex(UUID jobId, StepType type, int sceneIndex);

    /** Steps that still need work, e.g. PENDING and FAILED for a retry. */
    List<JobStep> findByJobIdAndStatusInOrderByTypeAscSceneIndexAsc(UUID jobId, Collection<StepStatus> statuses);

    long countByJobIdAndStatus(UUID jobId, StepStatus status);

    long countByJobId(UUID jobId);
}