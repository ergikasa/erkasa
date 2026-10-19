package com.erkasa.backend.features.promptvideo.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/**
 * What the API returns for a project.
 *
 * @param script           null while the script is being written (status SCRIPT_PENDING)
 * @param estimatedCredits what the video will cost the user: 1 credit per second (charged in phase 7)
 * @param jobs             the project's history, oldest first
 */
public record ProjectResponse(UUID id,
                              ProjectStatus status,
                              String idea,
                              Language language,
                              int durationSeconds,
                              AspectRatio aspectRatio,
                              String voiceId,
                              String title,
                              VideoScript script,
                              int estimatedCredits,
                              String errorCode,
                              String errorMessage,
                              Instant createdAt,
                              Instant updatedAt,
                              List<JobSummary> jobs) {

    public record JobSummary(UUID id, JobKind kind, JobStatus status, String errorCode,
                             Instant startedAt, Instant finishedAt) {

        static JobSummary of(Job job) {
            return new JobSummary(job.getId(), job.getKind(), job.getStatus(), job.getErrorCode(),
                    job.getStartedAt(), job.getFinishedAt());
        }
    }

    static ProjectResponse of(Project p, VideoScript script, List<Job> jobs) {
        return new ProjectResponse(p.getId(), p.getStatus(), p.getIdea(), p.getLanguage(), p.getDurationSeconds(),
                p.getAspectRatio(), p.getVoiceId(), p.getTitle(), script, p.getDurationSeconds(),
                p.getErrorCode(), p.getErrorMessage(), p.getCreatedAt(), p.getUpdatedAt(),
                jobs.stream().map(JobSummary::of).toList());
    }

    /** For lists: no script and no jobs, to keep the answer small. */
    static ProjectResponse brief(Project p) {
        return new ProjectResponse(p.getId(), p.getStatus(), p.getIdea(), p.getLanguage(), p.getDurationSeconds(),
                p.getAspectRatio(), p.getVoiceId(), p.getTitle(), null, p.getDurationSeconds(),
                p.getErrorCode(), p.getErrorMessage(), p.getCreatedAt(), p.getUpdatedAt(), List.of());
    }
}