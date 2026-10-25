package com.erkasa.backend.features.promptvideo.project;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.erkasa.backend.features.promptvideo.script.ScriptGenerator;
import com.erkasa.backend.features.promptvideo.script.SceneCatalog;
import com.erkasa.backend.features.promptvideo.script.ScriptRequest;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.PipelineWorkers;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.VideoOptions;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.StepType;
import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.ProviderException;

import tools.jackson.databind.ObjectMapper;

/**
 * Runs a SCRIPT job in the background:
 *
 *   1. (tx) job RUNNING, step RUNNING
 *   2.      ask the LLM (no transaction open while we wait for the provider)
 *   3. (tx) step SUCCEEDED with the script and its cost, job SUCCEEDED, project SCRIPT_REVIEW
 *      or, on error: step FAILED, job FAILED, project FAILED with the reason
 *
 * The project row is locked in step 3, so a cancel that arrived meanwhile wins: the script is
 * kept on the step (it was paid for) but the project stays CANCELLED.
 */
@Service
public class ScriptJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptJobRunner.class);

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final ScriptGenerator scripts;
    private final PipelineWorkers workers;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectEvents events;

    public ScriptJobRunner(ProjectRepository projects, JobRepository jobs, JobStepRepository steps,
                           ScriptGenerator scripts, PipelineWorkers workers, TransactionTemplate tx,
                           JdbcTemplate jdbc, ObjectMapper json, ProjectEvents events) {
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        this.scripts = scripts;
        this.workers = workers;
        this.tx = tx;
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    /** Call AFTER the transaction that created the job has committed. */
    public void start(UUID jobId) {
        workers.submit("script job " + jobId, () -> run(jobId));
    }

    void run(UUID jobId) {
        ScriptRequest request = tx.execute(status -> begin(jobId));
        if (request == null) {
            return; // job was cancelled before it started
        }
        Job job = jobs.findById(jobId).orElseThrow();
        Project project = projects.findById(job.getProjectId()).orElseThrow();
        CallContext ctx = new CallContext(project.getUserId(), jobId);

        try {
            VideoScript script = scripts.generate(request, ctx);
            String scriptJson = json.writeValueAsString(script);
            tx.executeWithoutResult(status -> succeed(jobId, script.title(), scriptJson));
            log.info("Script job {} done: '{}', {} scenes", jobId, script.title(), script.scenes().size());
        } catch (ProviderException e) {
            tx.executeWithoutResult(status -> fail(jobId, e.getCode(), e.getMessage()));
            log.warn("Script job {} failed: {}", jobId, e.getMessage());
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> fail(jobId, "INTERNAL_ERROR", "Unexpected error while writing the script"));
            log.error("Script job {} crashed", jobId, e);
        }
        events.changed(project.getId());
    }

    private ScriptRequest begin(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.QUEUED) {
            return null;
        }
        Project project = projects.findById(job.getProjectId()).orElseThrow();
        if (project.getStatus() != ProjectStatus.SCRIPT_PENDING) {
            job.moveTo(JobStatus.CANCELLED);
            stepOf(jobId).moveTo(StepStatus.SKIPPED);
            return null;
        }
        job.moveTo(JobStatus.RUNNING);
        stepOf(jobId).moveTo(StepStatus.RUNNING);
        // 9.2: a template the render worker cannot draw yet falls back to CLASSIC scenes;
        // purpose, audience and tone are still used.
        VideoOptions options = project.options();
        return new ScriptRequest(project.getIdea(), project.getLanguage(),
                project.getDurationSeconds(), project.getAspectRatio(),
                options.withTemplate(SceneCatalog.effective(options.template())));
    }

    private void succeed(UUID jobId, String title, String scriptJson) {
        Job job = jobs.findById(jobId).orElseThrow();
        Project project = projects.lockById(job.getProjectId()).orElseThrow();

        stepOf(jobId).succeed(null, scriptJson, costOf(jobId));
        job.moveTo(JobStatus.SUCCEEDED);
        if (project.getStatus() == ProjectStatus.SCRIPT_PENDING) {
            project.setScript(title, scriptJson);
            project.moveTo(ProjectStatus.SCRIPT_REVIEW);
        }
    }

    private void fail(UUID jobId, String code, String message) {
        Job job = jobs.findById(jobId).orElseThrow();
        Project project = projects.lockById(job.getProjectId()).orElseThrow();

        stepOf(jobId).fail(code, message, costOf(jobId));
        job.fail(code, message);
        if (project.getStatus() == ProjectStatus.SCRIPT_PENDING) {
            project.fail(code, message);
        }
    }

    private JobStep stepOf(UUID jobId) {
        return steps.findByJobIdAndTypeAndSceneIndex(jobId, StepType.SCRIPT, JobStep.WHOLE_VIDEO).orElseThrow();
    }

    /** Everything provider_usage recorded for this job: one or two LLM calls (ScriptGenerator retries once). */
    private BigDecimal costOf(UUID jobId) {
        BigDecimal cost = jdbc.queryForObject(
                "select coalesce(sum(cost_usd), 0) from provider_usage where job_id = ?", BigDecimal.class, jobId);
        return cost == null ? BigDecimal.ZERO : cost;
    }
}
