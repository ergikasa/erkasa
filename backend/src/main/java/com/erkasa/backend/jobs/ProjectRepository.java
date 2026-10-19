package com.erkasa.backend.jobs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** A project only if it belongs to this user: other users' projects look like "not found". */
    Optional<Project> findByIdAndUserId(UUID id, UUID userId);

    /** The user's projects, newest first (dashboard, phase 6). */
    List<Project> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);
}