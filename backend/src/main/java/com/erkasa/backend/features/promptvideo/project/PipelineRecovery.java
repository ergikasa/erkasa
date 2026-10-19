package com.erkasa.backend.features.promptvideo.project;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.StepStatus;

/**
 * On startup: picks up jobs that were QUEUED or RUNNING when the server stopped (deploy, crash,
 * laptop closed). Steps caught RUNNING go back to PENDING and the job is started again;
 * SUCCEEDED steps are kept, so nothing that was finished is paid for twice.
 *
 * v1 runs ONE backend instance. With several instances (phase 9) this must move to a queue
 * (RabbitMQ) or a lease, or two servers would pick up the same job.
 *
 * erkasa.pipeline.recover-on-startup=false turns it off (integration tests do this, because
 * several test contexts share one database).
 */
@Component
public class PipelineRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRecovery.class);

    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final ScriptJobRunner scriptRunner;
    private final AssetsJobRunner assetsRunner;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public PipelineRecovery(JobRepository jobs, JobStepRepository steps, ScriptJobRunner scriptRunner,
                            AssetsJobRunner assetsRunner, TransactionTemplate tx,
                            @Value("${erkasa.pipeline.recover-on-startup:true}") boolean enabled) {
        this.jobs = jobs;
        this.steps = steps;
        this.scriptRunner = scriptRunner;
        this.assetsRunner = assetsRunner;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            recover();
        }
    }

    /** @return how many jobs were started again */
    public int recover() {
        List<Job> stuck = jobs.findByStatusInOrderByCreatedAtAsc(List.of(JobStatus.QUEUED, JobStatus.RUNNING));
        int restarted = 0;
        for (Job found : stuck) {
            if (found.getKind() == JobKind.RENDER) {
                continue; // phase 5
            }
            Boolean ok = tx.execute(status -> reset(found));
            if (Boolean.TRUE.equals(ok)) {
                if (found.getKind() == JobKind.SCRIPT) {
                    scriptRunner.start(found.getId());
                } else {
                    assetsRunner.start(found.getId());
                }
                restarted++;
            }
        }
        if (restarted > 0) {
            log.info("Recovered {} unfinished pipeline jobs", restarted);
        }
        return restarted;
    }

    /** RUNNING steps -> PENDING, RUNNING job -> QUEUED. */
    private boolean reset(Job found) {
        Job job = jobs.findById(found.getId()).orElse(null);
        if (job == null || !job.getStatus().isActive()) {
            return false;
        }
        for (JobStep step : steps.findByJobIdAndStatusInOrderByTypeAscSceneIndexAsc(job.getId(),
                List.of(StepStatus.RUNNING))) {
            step.moveTo(StepStatus.PENDING);
        }
        if (job.getStatus() == JobStatus.RUNNING) {
            job.moveTo(JobStatus.QUEUED);
        }
        log.info("Recovering {} job {} of project {}", job.getKind(), job.getId(), job.getProjectId());
        return true;
    }
}